#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::env;
use std::fs;
use std::fs::OpenOptions;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::io::Read;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use rand::TryRngCore;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use tauri::Manager;
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

/// The app icon, embedded in the binary (this build is unbundled, so there is
/// no .app/.icns for macOS to read).
const APP_ICON_PNG: &[u8] = include_bytes!("../icons/icon.png");

/// Set the macOS Dock icon at runtime via AppKit. An unbundled binary has no
/// .app bundle, so macOS would otherwise show the generic executable icon;
/// `WebviewWindow::set_icon` does not affect the Dock on macOS. AppKit and
/// Foundation are already linked by tauri/tao.
#[cfg(target_os = "macos")]
// objc 0.2's msg_send!/sel! macros expand to a legacy `cfg(cargo-clippy)`
// that modern rustc flags via the unexpected_cfgs lint; it's dependency
// macro noise, not a real cfg of ours.
#[allow(unexpected_cfgs)]
fn set_macos_dock_icon(png: &[u8]) {
    use objc::runtime::Object;
    use objc::{class, msg_send, sel, sel_impl};
    type Id = *mut Object;
    unsafe {
        let data: Id = msg_send![class!(NSData),
            dataWithBytes: png.as_ptr() as *const std::ffi::c_void
            length: png.len()];
        if data.is_null() {
            return;
        }
        let image: Id = msg_send![class!(NSImage), alloc];
        let image: Id = msg_send![image, initWithData: data];
        if image.is_null() {
            return;
        }
        let app: Id = msg_send![class!(NSApplication), sharedApplication];
        let _: () = msg_send![app, setApplicationIconImage: image];
    }
}

#[tauri::command]
fn health() -> &'static str {
    "ok"
}

#[derive(Debug, Clone, Serialize)]
struct ControlFile {
    pid: u32,
    port: u16,
    token: String,
    version: String,
}

#[derive(Debug, Clone, Serialize)]
struct WatchDescriptor {
    path: String,
    format: String,
    revision: u64,
}

type WatchRegistry = Arc<Mutex<HashMap<String, WatchDescriptor>>>;
type WatchControllers = Arc<Mutex<HashMap<String, WatchController>>>;
type RecentWriteHashes = Arc<Mutex<HashMap<String, u64>>>;

#[derive(Debug, Clone)]
struct AccessPolicy {
    allowed_roots: Vec<PathBuf>,
    denied_roots: Vec<PathBuf>,
}

#[derive(Debug, Clone)]
struct RequestLimits {
    max_body_bytes: usize,
    rate_limit_max_requests: usize,
    rate_limit_window: Duration,
}

#[derive(Clone)]
struct RequestRateLimiter {
    state: Arc<Mutex<Vec<Instant>>>,
    max_requests: usize,
    window: Duration,
}

#[derive(Clone)]
struct AuditLogger {
    file_path: PathBuf,
    write_lock: Arc<Mutex<()>>,
}

#[derive(Serialize)]
struct AuditEventRecord {
    #[serde(rename = "timestampMs")]
    timestamp_ms: u64,
    action: String,
    path: Option<String>,
    source: Option<String>,
    result: String,
    revision: Option<u64>,
    message: Option<String>,
}

struct WatchController {
    stop_tx: std::sync::mpsc::Sender<()>,
}

fn main() {
    let control = write_runtime_file().expect("failed to write control runtime file");
    let access_policy = load_access_policy().expect("failed to load access policy");
    let request_limits = load_request_limits().expect("failed to load request limits");
    let audit_logger = init_audit_logger().expect("failed to initialize audit logger");
    let rate_limiter = RequestRateLimiter::new(
        request_limits.rate_limit_max_requests,
        request_limits.rate_limit_window,
    );
    let watch_registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
    let watch_controllers: WatchControllers = Arc::new(Mutex::new(HashMap::new()));
    let recent_write_hashes: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));

    tauri::Builder::default()
        .setup({
            let control = control.clone();
            let access_policy = access_policy.clone();
            let request_limits = request_limits.clone();
            let audit_logger = audit_logger.clone();
            let rate_limiter = rate_limiter.clone();
            let watch_registry = watch_registry.clone();
            let watch_controllers = watch_controllers.clone();
            let recent_write_hashes = recent_write_hashes.clone();
            move |app| {
                spawn_control_server(
                    control.clone(),
                    access_policy.clone(),
                    request_limits.clone(),
                    audit_logger.clone(),
                    rate_limiter.clone(),
                    watch_registry.clone(),
                    watch_controllers.clone(),
                    recent_write_hashes.clone(),
                    app.handle().clone(),
                )
                .map_err(|err| -> Box<dyn std::error::Error> { err.into() })?;

                // Apply the real app icon. Window icon is cross-platform
                // (Windows taskbar / Linux); the macOS Dock needs the AppKit
                // call since this binary is unbundled.
                if let Some(window) = app.get_webview_window("main") {
                    if let Ok(icon) = tauri::image::Image::from_bytes(APP_ICON_PNG) {
                        let _ = window.set_icon(icon);
                    }
                }
                #[cfg(target_os = "macos")]
                set_macos_dock_icon(APP_ICON_PNG);

                Ok(())
            }
        })
        .invoke_handler(tauri::generate_handler![health])
        .run(tauri::generate_context!())
        .expect("error while running graph explorer desktop");
}

fn init_audit_logger() -> Result<AuditLogger> {
    let runtime_dir = runtime_dir_path()?;
    fs::create_dir_all(&runtime_dir)
        .with_context(|| format!("failed to create runtime dir {}", runtime_dir.display()))?;
    let file_path = runtime_dir.join("audit.log.jsonl");
    if !file_path.exists() {
        fs::write(&file_path, b"")
            .with_context(|| format!("failed to initialize audit file {}", file_path.display()))?;
        set_owner_only_permissions(&file_path)?;
    }
    Ok(AuditLogger {
        file_path,
        write_lock: Arc::new(Mutex::new(())),
    })
}

impl RequestRateLimiter {
    fn new(max_requests: usize, window: Duration) -> Self {
        Self {
            state: Arc::new(Mutex::new(Vec::new())),
            max_requests,
            window,
        }
    }

    fn allow(&self) -> bool {
        let now = Instant::now();
        let mut state = match self.state.lock() {
            Ok(value) => value,
            Err(_) => return true,
        };
        state.retain(|seen_at| now.duration_since(*seen_at) <= self.window);
        if state.len() >= self.max_requests {
            return false;
        }
        state.push(now);
        true
    }
}

impl AuditLogger {
    fn log_event(
        &self,
        action: &str,
        path: Option<&str>,
        source: Option<&str>,
        result: &str,
        revision: Option<u64>,
        message: Option<&str>,
    ) {
        let _guard = match self.write_lock.lock() {
            Ok(value) => value,
            Err(err) => {
                eprintln!("audit logger lock poisoned: {err}");
                return;
            }
        };

        let record = AuditEventRecord {
            timestamp_ms: current_time_ms(),
            action: action.to_string(),
            path: path.map(|value| value.to_string()),
            source: source.map(|value| value.to_string()),
            result: result.to_string(),
            revision,
            message: message.map(|value| value.to_string()),
        };
        let line = match serde_json::to_string(&record) {
            Ok(value) => value,
            Err(err) => {
                eprintln!("failed to serialize audit event: {err}");
                return;
            }
        };

        let mut file = match OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.file_path)
        {
            Ok(value) => value,
            Err(err) => {
                eprintln!(
                    "failed to open audit file {}: {err}",
                    self.file_path.display()
                );
                return;
            }
        };

        if let Err(err) = writeln!(file, "{line}") {
            eprintln!("failed to append audit event: {err}");
            return;
        }
        let _ = set_owner_only_permissions(&self.file_path);
    }
}

fn load_access_policy() -> Result<AccessPolicy> {
    let allowed_roots = env::var_os("GX_ALLOWED_ROOTS")
        .or_else(|| env::var_os("GRAPH_EXPLORER_ALLOWED_ROOTS"))
        .map(|raw| {
            env::split_paths(&raw)
                .filter(|path| !path.as_os_str().is_empty())
                .map(normalize_policy_root)
                .collect::<Result<Vec<_>>>()
        })
        .transpose()?
        .unwrap_or_default();

    let mut denied_roots = default_denied_roots();
    let extra_denied_roots = env::var_os("GX_DENY_ROOTS")
        .or_else(|| env::var_os("GRAPH_EXPLORER_DENY_ROOTS"))
        .map(|raw| {
            env::split_paths(&raw)
                .filter(|path| !path.as_os_str().is_empty())
                .map(normalize_policy_root)
                .collect::<Result<Vec<_>>>()
        })
        .transpose()?
        .unwrap_or_default();
    denied_roots.extend(extra_denied_roots);

    Ok(AccessPolicy {
        allowed_roots,
        denied_roots,
    })
}

fn load_request_limits() -> Result<RequestLimits> {
    let max_body_bytes = parse_env_usize("GX_MAX_REQUEST_BODY_BYTES", 1_048_576)?;
    let rate_limit_max_requests = parse_env_usize("GX_RATE_LIMIT_MAX_REQUESTS", 240)?;
    let rate_limit_window_ms = parse_env_u64("GX_RATE_LIMIT_WINDOW_MS", 10_000)?;
    Ok(RequestLimits {
        max_body_bytes,
        rate_limit_max_requests,
        rate_limit_window: Duration::from_millis(rate_limit_window_ms),
    })
}

fn normalize_policy_root(path: PathBuf) -> Result<PathBuf> {
    let absolute = if path.is_absolute() {
        path
    } else {
        std::env::current_dir()
            .context("failed to read current working directory for policy path")?
            .join(path)
    };
    Ok(fs::canonicalize(&absolute).unwrap_or(absolute))
}

fn default_denied_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    for static_path in [
        "/System",
        "/Library/Keychains",
        "/private/etc",
        "/private/var/db",
        "/bin",
        "/sbin",
        "/usr/bin",
        "/usr/sbin",
    ] {
        roots.push(PathBuf::from(static_path));
    }

    if let Some(home) = dirs::home_dir() {
        roots.push(home.join(".ssh"));
        roots.push(home.join(".gnupg"));
        roots.push(home.join(".aws"));
        roots.push(home.join(".kube"));
    }

    roots
}

fn parse_env_usize(key: &str, default: usize) -> Result<usize> {
    match env::var(key) {
        Ok(raw) => raw
            .parse::<usize>()
            .with_context(|| format!("invalid numeric value for {key}: {raw}")),
        Err(_) => Ok(default),
    }
}

fn parse_env_u64(key: &str, default: u64) -> Result<u64> {
    match env::var(key) {
        Ok(raw) => raw
            .parse::<u64>()
            .with_context(|| format!("invalid numeric value for {key}: {raw}")),
        Err(_) => Ok(default),
    }
}

fn write_runtime_file() -> Result<ControlFile> {
    let runtime_file_path = runtime_file_path()?;
    let runtime_dir = runtime_dir_path()?;
    fs::create_dir_all(&runtime_dir)
        .with_context(|| format!("failed to create runtime dir {}", runtime_dir.display()))?;

    let control = ControlFile {
        pid: std::process::id(),
        port: find_open_port()?,
        token: generate_token()?,
        version: env!("CARGO_PKG_VERSION").to_string(),
    };

    let temp_path = runtime_file_path.with_extension("json.tmp");
    let payload =
        serde_json::to_vec_pretty(&control).context("failed to serialize control file")?;
    fs::write(&temp_path, payload)
        .with_context(|| format!("failed to write temp runtime file {}", temp_path.display()))?;
    set_owner_only_permissions(&temp_path)?;

    fs::rename(&temp_path, &runtime_file_path).with_context(|| {
        format!(
            "failed to move runtime file into place {}",
            runtime_file_path.display()
        )
    })?;
    set_owner_only_permissions(&runtime_file_path)?;

    Ok(control)
}

fn runtime_file_path() -> Result<PathBuf> {
    Ok(runtime_dir_path()?.join("control.json"))
}

fn runtime_dir_path() -> Result<PathBuf> {
    let home_dir = dirs::home_dir().context("could not locate user home directory")?;
    Ok(home_dir.join(".graph-explorer").join("runtime"))
}

fn find_open_port() -> Result<u16> {
    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
    let listener = TcpListener::bind(addr).context("failed to bind temporary loopback socket")?;
    let port = listener
        .local_addr()
        .context("failed to read local socket address")?
        .port();
    Ok(port)
}

fn generate_token() -> Result<String> {
    let mut bytes = [0_u8; 32];
    rand::rngs::OsRng
        .try_fill_bytes(&mut bytes)
        .map_err(|err| anyhow::anyhow!("failed to generate random token: {err}"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

fn spawn_control_server(
    control: ControlFile,
    access_policy: AccessPolicy,
    request_limits: RequestLimits,
    audit_logger: AuditLogger,
    rate_limiter: RequestRateLimiter,
    watch_registry: WatchRegistry,
    watch_controllers: WatchControllers,
    recent_write_hashes: RecentWriteHashes,
    app_handle: tauri::AppHandle,
) -> Result<JoinHandle<()>> {
    let addr = format!("127.0.0.1:{}", control.port);
    let server = Server::http(&addr)
        .map_err(|err| anyhow::anyhow!("failed to bind local control server on {addr}: {err}"))?;
    let handle = std::thread::spawn(move || {
        for request in server.incoming_requests() {
            handle_request(
                request,
                &control,
                &access_policy,
                &request_limits,
                &audit_logger,
                &rate_limiter,
                &watch_registry,
                &watch_controllers,
                &recent_write_hashes,
                &app_handle,
            );
        }
    });
    Ok(handle)
}

#[derive(Debug, Deserialize)]
struct PushTextRequest {
    text: String,
}

#[derive(Debug, Deserialize)]
struct WatchRequest {
    path: String,
    #[serde(rename = "openInUi")]
    _open_in_ui: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct UnwatchRequest {
    path: String,
}

#[derive(Debug, Deserialize)]
struct PutDocumentRequest {
    path: String,
    text: String,
    #[serde(rename = "baseRevision")]
    base_revision: u64,
    source: Option<String>,
}

#[derive(Debug, Serialize)]
struct DocumentSnapshot {
    path: String,
    text: String,
    format: String,
    revision: u64,
    #[serde(rename = "timestampMs")]
    timestamp_ms: u64,
}

#[derive(Debug, Serialize)]
struct DocumentChangedEventPayload {
    text: String,
    path: Option<String>,
    revision: Option<u64>,
    port: Option<u16>,
    token: Option<String>,
}

fn handle_request(
    mut request: Request,
    control: &ControlFile,
    access_policy: &AccessPolicy,
    request_limits: &RequestLimits,
    audit_logger: &AuditLogger,
    rate_limiter: &RequestRateLimiter,
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    recent_write_hashes: &RecentWriteHashes,
    app_handle: &tauri::AppHandle,
) {
    if !rate_limiter.allow() {
        audit_logger.log_event(
            "request.rate_limited",
            None,
            None,
            "rejected",
            None,
            Some("local request rate limit exceeded"),
        );
        #[derive(Serialize)]
        struct RateLimitedBody {
            ok: bool,
            code: &'static str,
            message: &'static str,
        }
        let response = json_response(
            StatusCode(429),
            &RateLimitedBody {
                ok: false,
                code: "RATE_LIMITED",
                message: "local request rate limit exceeded",
            },
        );
        if let Err(err) = request.respond(response) {
            eprintln!("control server response error: {err}");
        }
        return;
    }

    let request_url = request.url().to_string();
    let response = match (request.method(), request_url.as_str()) {
        (&Method::Get, "/v1/status") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                #[derive(Serialize)]
                struct StatusBody {
                    ok: bool,
                    running: bool,
                    version: String,
                    pid: u32,
                    port: u16,
                    watches: Vec<WatchDescriptor>,
                    #[serde(rename = "allowedRoots")]
                    allowed_roots: Vec<String>,
                    #[serde(rename = "deniedRoots")]
                    denied_roots: Vec<String>,
                    #[serde(rename = "maxBodyBytes")]
                    max_body_bytes: usize,
                    #[serde(rename = "rateLimitMaxRequests")]
                    rate_limit_max_requests: usize,
                    #[serde(rename = "rateLimitWindowMs")]
                    rate_limit_window_ms: u64,
                }

                let watches = watched_items(watch_registry);
                let allowed_roots = configured_allowed_roots(access_policy);
                let denied_roots = configured_denied_roots(access_policy);
                json_response(
                    StatusCode(200),
                    &StatusBody {
                        ok: true,
                        running: true,
                        version: control.version.clone(),
                        pid: control.pid,
                        port: control.port,
                        watches,
                        allowed_roots,
                        denied_roots,
                        max_body_bytes: request_limits.max_body_bytes,
                        rate_limit_max_requests: request_limits.rate_limit_max_requests,
                        rate_limit_window_ms: request_limits.rate_limit_window.as_millis() as u64,
                    },
                )
            }
        }
        (&Method::Get, path) if path.starts_with("/v1/document") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                match parse_document_path_from_url(path) {
                    Some(path) => match get_document_snapshot(watch_registry, &path) {
                        Ok(snapshot) => {
                            #[derive(Serialize)]
                            struct GetDocumentResponse {
                                ok: bool,
                                document: DocumentSnapshot,
                            }
                            json_response(
                                StatusCode(200),
                                &GetDocumentResponse {
                                    ok: true,
                                    document: snapshot,
                                },
                            )
                        }
                        Err(err) => {
                            #[derive(Serialize)]
                            struct GetDocumentError {
                                ok: bool,
                                code: &'static str,
                                message: String,
                            }
                            json_response(
                                StatusCode(400),
                                &GetDocumentError {
                                    ok: false,
                                    code: "DOCUMENT_READ_FAILED",
                                    message: err.to_string(),
                                },
                            )
                        }
                    },
                    None => {
                        #[derive(Serialize)]
                        struct BadRequestBody {
                            ok: bool,
                            code: &'static str,
                            message: String,
                        }
                        json_response(
                            StatusCode(400),
                            &BadRequestBody {
                                ok: false,
                                code: "INVALID_REQUEST",
                                message: "missing query parameter: path".to_string(),
                            },
                        )
                    }
                }
            }
        }
        (&Method::Put, "/v1/document") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                match parse_put_document_request(&mut request, request_limits.max_body_bytes) {
                    Ok(payload) => match put_document_snapshot(
                        control,
                        watch_registry,
                        recent_write_hashes,
                        audit_logger,
                        app_handle,
                        payload,
                    ) {
                        Ok(snapshot) => {
                            #[derive(Serialize)]
                            struct PutDocumentResponse {
                                ok: bool,
                                document: DocumentSnapshot,
                            }
                            json_response(
                                StatusCode(200),
                                &PutDocumentResponse {
                                    ok: true,
                                    document: snapshot,
                                },
                            )
                        }
                        Err(PutDocumentError::Conflict {
                            current_revision,
                            attempted_base_revision,
                        }) => {
                            #[derive(Serialize)]
                            struct ConflictBody {
                                ok: bool,
                                code: &'static str,
                                #[serde(rename = "currentRevision")]
                                current_revision: u64,
                                #[serde(rename = "attemptedBaseRevision")]
                                attempted_base_revision: u64,
                            }
                            json_response(
                                StatusCode(409),
                                &ConflictBody {
                                    ok: false,
                                    code: "DOCUMENT_CONFLICT",
                                    current_revision,
                                    attempted_base_revision,
                                },
                            )
                        }
                        Err(PutDocumentError::Other(err)) => {
                            #[derive(Serialize)]
                            struct PutDocumentErrorBody {
                                ok: bool,
                                code: &'static str,
                                message: String,
                            }
                            json_response(
                                StatusCode(400),
                                &PutDocumentErrorBody {
                                    ok: false,
                                    code: "DOCUMENT_WRITE_FAILED",
                                    message: err.to_string(),
                                },
                            )
                        }
                    },
                    Err(err) => request_parse_error_response(err),
                }
            }
        }
        (&Method::Post, "/v1/watch") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                match parse_watch_request(&mut request, request_limits.max_body_bytes) {
                    Ok(payload) => match add_watch(
                        control,
                        access_policy,
                        watch_registry,
                        watch_controllers,
                        recent_write_hashes,
                        audit_logger,
                        app_handle,
                        &payload.path,
                    ) {
                        Ok(watch) => {
                            #[derive(Serialize)]
                            struct WatchResponse {
                                ok: bool,
                                watch: WatchDescriptor,
                            }
                            json_response(StatusCode(200), &WatchResponse { ok: true, watch })
                        }
                        Err(err) => {
                            audit_logger.log_event(
                                "watch.rejected",
                                Some(&payload.path),
                                Some("api"),
                                "rejected",
                                None,
                                Some(&err.to_string()),
                            );
                            #[derive(Serialize)]
                            struct WatchErrorBody {
                                ok: bool,
                                code: &'static str,
                                message: String,
                            }
                            json_response(
                                StatusCode(400),
                                &WatchErrorBody {
                                    ok: false,
                                    code: "WATCH_FAILED",
                                    message: err.to_string(),
                                },
                            )
                        }
                    },
                    Err(err) => request_parse_error_response(err),
                }
            }
        }
        (&Method::Post, "/v1/unwatch") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                match parse_unwatch_request(&mut request, request_limits.max_body_bytes) {
                    Ok(payload) => {
                        match remove_watch(
                            watch_registry,
                            watch_controllers,
                            audit_logger,
                            &payload.path,
                        ) {
                            Ok(removed) => {
                                #[derive(Serialize)]
                                struct UnwatchResponse {
                                    ok: bool,
                                    removed: bool,
                                }
                                json_response(
                                    StatusCode(200),
                                    &UnwatchResponse { ok: true, removed },
                                )
                            }
                            Err(err) => {
                                #[derive(Serialize)]
                                struct UnwatchErrorBody {
                                    ok: bool,
                                    code: &'static str,
                                    message: String,
                                }
                                json_response(
                                    StatusCode(400),
                                    &UnwatchErrorBody {
                                        ok: false,
                                        code: "UNWATCH_FAILED",
                                        message: err.to_string(),
                                    },
                                )
                            }
                        }
                    }
                    Err(err) => request_parse_error_response(err),
                }
            }
        }
        (&Method::Post, "/v1/push-text") => {
            if !is_authorized(&request, &control.token) {
                text_response(StatusCode(401), "unauthorized")
            } else {
                match parse_push_text_request(&mut request, request_limits.max_body_bytes) {
                    Ok(payload) => {
                        let event_payload = DocumentChangedEventPayload {
                            text: payload.text,
                            path: None,
                            revision: None,
                            port: Some(control.port),
                            token: Some(control.token.clone()),
                        };
                        match emit_document_changed_event(app_handle, &event_payload) {
                            Ok(()) => {
                                #[derive(Serialize)]
                                struct PushOkBody {
                                    ok: bool,
                                }
                                json_response(StatusCode(200), &PushOkBody { ok: true })
                            }
                            Err(err) => {
                                #[derive(Serialize)]
                                struct PushErrorBody {
                                    ok: bool,
                                    code: &'static str,
                                    message: String,
                                }
                                json_response(
                                    StatusCode(500),
                                    &PushErrorBody {
                                        ok: false,
                                        code: "PUSH_FAILED",
                                        message: err.to_string(),
                                    },
                                )
                            }
                        }
                    }
                    Err(err) => request_parse_error_response(err),
                }
            }
        }
        // CORS preflight for the in-app webview's cross-origin fetch.
        (&Method::Options, _) => text_response(StatusCode(204), ""),
        _ => text_response(StatusCode(404), "not found"),
    };

    if let Err(err) = request.respond(response) {
        eprintln!("control server response error: {err}");
    }
}

fn watched_items(watch_registry: &WatchRegistry) -> Vec<WatchDescriptor> {
    match watch_registry.lock() {
        Ok(registry) => {
            let mut items: Vec<WatchDescriptor> = registry.values().cloned().collect();
            items.sort_by(|a, b| a.path.cmp(&b.path));
            items
        }
        Err(err) => {
            eprintln!("watch registry lock poisoned while reading status: {err}");
            Vec::new()
        }
    }
}

fn configured_allowed_roots(access_policy: &AccessPolicy) -> Vec<String> {
    access_policy
        .allowed_roots
        .iter()
        .map(|root| root.to_string_lossy().to_string())
        .collect()
}

fn configured_denied_roots(access_policy: &AccessPolicy) -> Vec<String> {
    access_policy
        .denied_roots
        .iter()
        .map(|root| root.to_string_lossy().to_string())
        .collect()
}

fn parse_watch_request(request: &mut Request, max_body_bytes: usize) -> Result<WatchRequest> {
    parse_json_request(request, max_body_bytes)
}

fn parse_unwatch_request(request: &mut Request, max_body_bytes: usize) -> Result<UnwatchRequest> {
    parse_json_request(request, max_body_bytes)
}

fn parse_put_document_request(
    request: &mut Request,
    max_body_bytes: usize,
) -> Result<PutDocumentRequest> {
    parse_json_request(request, max_body_bytes)
}

fn parse_document_path_from_url(url: &str) -> Option<String> {
    let (_, query) = url.split_once('?')?;
    query
        .split('&')
        .filter_map(|entry| entry.split_once('='))
        .find_map(|(key, value)| {
            if key == "path" {
                Some(value.replace("%2F", "/"))
            } else {
                None
            }
        })
}

fn get_document_snapshot(watch_registry: &WatchRegistry, path: &str) -> Result<DocumentSnapshot> {
    let normalized = normalize_path(path)?;
    let watch = watch_registry
        .lock()
        .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?
        .get(&normalized)
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("path is not currently watched: {normalized}"))?;

    let text = fs::read_to_string(&normalized)
        .with_context(|| format!("failed to read {}", normalized))?;
    Ok(DocumentSnapshot {
        path: normalized,
        text,
        format: watch.format,
        revision: watch.revision,
        timestamp_ms: current_time_ms(),
    })
}

enum PutDocumentError {
    Conflict {
        current_revision: u64,
        attempted_base_revision: u64,
    },
    Other(anyhow::Error),
}

fn put_document_snapshot(
    control: &ControlFile,
    watch_registry: &WatchRegistry,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
    app_handle: &tauri::AppHandle,
    payload: PutDocumentRequest,
) -> std::result::Result<DocumentSnapshot, PutDocumentError> {
    let normalized = normalize_path(&payload.path).map_err(PutDocumentError::Other)?;
    let mut registry = watch_registry.lock().map_err(|err| {
        PutDocumentError::Other(anyhow::anyhow!("watch registry lock poisoned: {err}"))
    })?;

    let watch = registry.get_mut(&normalized).ok_or_else(|| {
        PutDocumentError::Other(anyhow::anyhow!(
            "path is not currently watched: {}",
            normalized
        ))
    })?;

    if payload.base_revision != watch.revision {
        audit_logger.log_event(
            "document.conflict",
            Some(&normalized),
            payload.source.as_deref(),
            "rejected",
            Some(watch.revision),
            Some(&format!(
                "attemptedBaseRevision={}, currentRevision={}",
                payload.base_revision, watch.revision
            )),
        );
        return Err(PutDocumentError::Conflict {
            current_revision: watch.revision,
            attempted_base_revision: payload.base_revision,
        });
    }

    write_file_atomic(&normalized, &payload.text).map_err(PutDocumentError::Other)?;
    let content_hash = hash_string(&payload.text);
    if let Ok(mut writes) = recent_write_hashes.lock() {
        writes.insert(normalized.clone(), content_hash);
    }

    watch.revision = watch.revision.saturating_add(1);
    let revision = watch.revision;
    let format = watch.format.clone();
    let source = payload
        .source
        .clone()
        .unwrap_or_else(|| "unknown".to_string());
    drop(registry);

    let event_payload = DocumentChangedEventPayload {
        text: payload.text.clone(),
        path: Some(normalized.clone()),
        revision: Some(revision),
        port: Some(control.port),
        token: Some(control.token.clone()),
    };
    emit_document_changed_event(app_handle, &event_payload).map_err(PutDocumentError::Other)?;

    audit_logger.log_event(
        "document.written",
        Some(&normalized),
        Some(&source),
        "ok",
        Some(revision),
        None,
    );

    Ok(DocumentSnapshot {
        path: normalized,
        text: payload.text,
        format,
        revision,
        timestamp_ms: current_time_ms(),
    })
}

fn write_file_atomic(path: &str, text: &str) -> Result<()> {
    let target = PathBuf::from(path);
    let parent = target
        .parent()
        .context("target path does not have a parent directory")?;
    let temp_name = format!(
        ".{}.tmp",
        target
            .file_name()
            .and_then(|v| v.to_str())
            .unwrap_or("graph-explorer")
    );
    let temp_path = parent.join(temp_name);
    fs::write(&temp_path, text)
        .with_context(|| format!("failed to write temporary file {}", temp_path.display()))?;
    set_owner_only_permissions(&temp_path)?;
    fs::rename(&temp_path, &target)
        .with_context(|| format!("failed to move temporary file into {}", target.display()))?;
    set_owner_only_permissions(&target)?;
    Ok(())
}

fn current_time_ms() -> u64 {
    let now = std::time::SystemTime::now();
    now.duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn normalize_path(path: &str) -> Result<String> {
    let input = PathBuf::from(path);
    let absolute = if input.is_absolute() {
        input
    } else {
        std::env::current_dir()
            .context("failed to read current working directory")?
            .join(input)
    };
    let normalized = fs::canonicalize(&absolute).unwrap_or(absolute);
    Ok(normalized.to_string_lossy().to_string())
}

fn ensure_watch_target_is_file(path: &Path) -> Result<()> {
    let metadata = fs::metadata(path).with_context(|| {
        format!(
            "watch target does not exist or is not accessible: {}",
            path.display()
        )
    })?;
    if !metadata.is_file() {
        return Err(anyhow::anyhow!(
            "watch target must be a regular file: {}",
            path.display()
        ));
    }
    Ok(())
}

fn ensure_path_allowed(access_policy: &AccessPolicy, path: &Path) -> Result<()> {
    if access_policy.allowed_roots.is_empty() {
        return Ok(());
    }

    let allowed = access_policy
        .allowed_roots
        .iter()
        .any(|root| path.starts_with(root));
    if allowed {
        Ok(())
    } else {
        let roots = access_policy
            .allowed_roots
            .iter()
            .map(|root| root.to_string_lossy().to_string())
            .collect::<Vec<_>>()
            .join(", ");
        Err(anyhow::anyhow!(
            "path is outside configured allowlist: {} (allowed roots: {})",
            path.display(),
            roots
        ))
    }
}

fn ensure_path_not_denied(access_policy: &AccessPolicy, path: &Path) -> Result<()> {
    if let Some(denied_root) = access_policy
        .denied_roots
        .iter()
        .find(|root| path.starts_with(root))
    {
        return Err(anyhow::anyhow!(
            "path is blocked by denylist: {} (matched root: {})",
            path.display(),
            denied_root.display()
        ));
    }
    Ok(())
}

fn infer_format_from_path(path: &str) -> String {
    if path.ends_with(".mmd") {
        "mermaid".to_string()
    } else {
        "dot".to_string()
    }
}

fn add_watch(
    control: &ControlFile,
    access_policy: &AccessPolicy,
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
    app_handle: &tauri::AppHandle,
    path: &str,
) -> Result<WatchDescriptor> {
    let normalized = normalize_path(path)?;
    let normalized_path = PathBuf::from(&normalized);
    ensure_watch_target_is_file(&normalized_path)?;
    ensure_path_not_denied(access_policy, &normalized_path)?;
    ensure_path_allowed(access_policy, &normalized_path)?;

    {
        let registry = watch_registry
            .lock()
            .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?;
        if let Some(existing) = registry.get(&normalized) {
            return Ok(existing.clone());
        }
    }

    let watch = WatchDescriptor {
        format: infer_format_from_path(&normalized),
        path: normalized.clone(),
        revision: 1,
    };

    let mut registry = watch_registry
        .lock()
        .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?;
    registry.insert(normalized, watch.clone());
    drop(registry);

    if let Ok(text) = fs::read_to_string(&watch.path) {
        let initial_event = DocumentChangedEventPayload {
            text,
            path: Some(watch.path.clone()),
            revision: Some(watch.revision),
            port: Some(control.port),
            token: Some(control.token.clone()),
        };
        let _ = emit_document_changed_event(app_handle, &initial_event);
    }

    let controller = spawn_watch_loop(
        control.clone(),
        watch_registry.clone(),
        recent_write_hashes.clone(),
        app_handle.clone(),
        watch.path.clone(),
    );
    let mut controllers = watch_controllers
        .lock()
        .map_err(|err| anyhow::anyhow!("watch controller lock poisoned: {err}"))?;
    controllers.insert(watch.path.clone(), controller);

    audit_logger.log_event(
        "watch.added",
        Some(&watch.path),
        Some("api"),
        "ok",
        Some(watch.revision),
        None,
    );

    Ok(watch)
}

fn remove_watch(
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    audit_logger: &AuditLogger,
    path: &str,
) -> Result<bool> {
    let normalized = normalize_path(path)?;

    if let Ok(mut controllers) = watch_controllers.lock() {
        if let Some(controller) = controllers.remove(&normalized) {
            let _ = controller.stop_tx.send(());
        }
    }

    let mut registry = watch_registry
        .lock()
        .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?;
    let removed = registry.remove(&normalized).is_some();
    audit_logger.log_event(
        "watch.removed",
        Some(&normalized),
        Some("api"),
        if removed { "ok" } else { "noop" },
        None,
        None,
    );
    Ok(removed)
}

// Disk -> UI propagation budget (LC2-T5) is <=300ms median. Detection latency
// is bounded by the poll interval, plus the debounce window, plus loop
// granularity. Tuned to 15/50ms so the median stays well under budget even on
// slow/oversubscribed CI runners (macOS); 50ms still coalesces a multi-write
// editor save (those land within a few ms) into one update.
const WATCH_POLL_INTERVAL: Duration = Duration::from_millis(15);
const WATCH_DEBOUNCE: Duration = Duration::from_millis(50);

fn spawn_watch_loop(
    control: ControlFile,
    watch_registry: WatchRegistry,
    recent_write_hashes: RecentWriteHashes,
    app_handle: tauri::AppHandle,
    path: String,
) -> WatchController {
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
    std::thread::spawn(move || {
        let mut previous_hash: Option<u64> = hash_file_contents(&path).ok();
        let mut pending: Option<(Instant, String, u64)> = None;

        loop {
            if stop_rx.try_recv().is_ok() {
                break;
            }

            if let Ok(contents) = fs::read_to_string(&path) {
                let current_hash = hash_string(&contents);
                let pending_hash = pending.as_ref().map(|(_, _, hash)| *hash);

                let is_self_write = recent_write_hashes
                    .lock()
                    .ok()
                    .and_then(|mut writes| {
                        let matched = writes.get(&path).copied() == Some(current_hash);
                        if matched {
                            writes.remove(&path);
                            Some(true)
                        } else {
                            Some(false)
                        }
                    })
                    .unwrap_or(false);

                if is_self_write {
                    previous_hash = Some(current_hash);
                    pending = None;
                    std::thread::sleep(WATCH_POLL_INTERVAL);
                    continue;
                }

                if Some(current_hash) != previous_hash && Some(current_hash) != pending_hash {
                    pending = Some((Instant::now(), contents, current_hash));
                }
            }

            if let Some((seen_at, text, hash)) = pending.as_ref() {
                if seen_at.elapsed() >= WATCH_DEBOUNCE {
                    if let Ok(mut registry) = watch_registry.lock() {
                        if let Some(watch) = registry.get_mut(&path) {
                            watch.revision = watch.revision.saturating_add(1);
                            let event_payload = DocumentChangedEventPayload {
                                text: text.clone(),
                                path: Some(path.clone()),
                                revision: Some(watch.revision),
                                port: Some(control.port),
                                token: Some(control.token.clone()),
                            };
                            if let Err(err) =
                                emit_document_changed_event(&app_handle, &event_payload)
                            {
                                eprintln!("watch loop failed to emit event for {path}: {err}");
                            }
                        }
                    }
                    previous_hash = Some(*hash);
                    pending = None;
                }
            }

            std::thread::sleep(WATCH_POLL_INTERVAL);
        }
    });

    WatchController { stop_tx }
}

fn hash_file_contents(path: &str) -> Result<u64> {
    let text = fs::read_to_string(path)
        .with_context(|| format!("failed to read watched file {}", path))?;
    Ok(hash_string(&text))
}

fn hash_string(value: &str) -> u64 {
    let mut hasher = DefaultHasher::new();
    value.hash(&mut hasher);
    hasher.finish()
}

fn parse_push_text_request(
    request: &mut Request,
    max_body_bytes: usize,
) -> Result<PushTextRequest> {
    parse_json_request(request, max_body_bytes)
}

fn parse_json_request<T: DeserializeOwned>(
    request: &mut Request,
    max_body_bytes: usize,
) -> Result<T> {
    let mut body = Vec::new();
    request
        .as_reader()
        .take((max_body_bytes + 1) as u64)
        .read_to_end(&mut body)
        .context("failed to read request body")?;
    if body.len() > max_body_bytes {
        return Err(anyhow::anyhow!(
            "request body too large (max {} bytes)",
            max_body_bytes
        ));
    }
    serde_json::from_slice(&body).context("failed to parse JSON request body")
}

fn request_parse_error_response(err: anyhow::Error) -> Response<std::io::Cursor<Vec<u8>>> {
    #[derive(Serialize)]
    struct RequestErrorBody {
        ok: bool,
        code: &'static str,
        message: String,
    }

    let message = err.to_string();
    if message.contains("request body too large") {
        json_response(
            StatusCode(413),
            &RequestErrorBody {
                ok: false,
                code: "PAYLOAD_TOO_LARGE",
                message,
            },
        )
    } else {
        json_response(
            StatusCode(400),
            &RequestErrorBody {
                ok: false,
                code: "INVALID_REQUEST",
                message,
            },
        )
    }
}

fn emit_document_changed_event(
    app_handle: &tauri::AppHandle,
    payload: &DocumentChangedEventPayload,
) -> Result<()> {
    let payload_json =
        serde_json::to_string(payload).context("failed to serialize event payload")?;
    let script = format!(
        "window.dispatchEvent(new CustomEvent('ge:document.changed', {{ detail: {payload} }}));\
         window.dispatchEvent(new CustomEvent('document.changed', {{ detail: {payload} }}));",
        payload = payload_json
    );

    let windows = app_handle.webview_windows();
    if windows.is_empty() {
        return Err(anyhow::anyhow!("no webview windows are available"));
    }

    windows.values().try_for_each(|webview| {
        webview
            .eval(&script)
            .map_err(|err| anyhow::anyhow!("failed to evaluate event script: {err}"))
    })?;
    Ok(())
}

fn is_authorized(request: &Request, token: &str) -> bool {
    request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .and_then(|header| header.value.as_str().strip_prefix("Bearer "))
        .map(|value| value == token)
        .unwrap_or(false)
}

// The in-app webview reaches this server via a cross-origin fetch
// (tauri://localhost -> http://127.0.0.1:<port>). A PUT with an
// Authorization header is a non-simple request, so the webview sends a CORS
// preflight; without these headers the preflight 404s and the browser blocks
// the request ("TypeError: Load failed"). `*` is safe here: loopback control
// server, bearer-token auth, no credentialed cookies.
fn cors_headers() -> Vec<Header> {
    vec![
        Header::from_bytes("Access-Control-Allow-Origin", "*").expect("valid static header"),
        Header::from_bytes("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
            .expect("valid static header"),
        Header::from_bytes("Access-Control-Allow-Headers", "Authorization, Content-Type")
            .expect("valid static header"),
    ]
}

fn json_response<T: Serialize>(
    status: StatusCode,
    payload: &T,
) -> Response<std::io::Cursor<Vec<u8>>> {
    let json = serde_json::to_vec(payload)
        .unwrap_or_else(|_| b"{\"ok\":false,\"message\":\"serialization-error\"}".to_vec());
    let mut headers = cors_headers();
    headers.push(
        Header::from_bytes("Content-Type", "application/json; charset=utf-8")
            .expect("valid static header"),
    );
    Response::new(
        status,
        headers,
        std::io::Cursor::new(json.clone()),
        Some(json.len()),
        None,
    )
}

fn text_response(status: StatusCode, body: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    let bytes = body.as_bytes().to_vec();
    let mut headers = cors_headers();
    headers.push(
        Header::from_bytes("Content-Type", "text/plain; charset=utf-8")
            .expect("valid static header"),
    );
    Response::new(
        status,
        headers,
        std::io::Cursor::new(bytes.clone()),
        Some(bytes.len()),
        None,
    )
}

#[cfg(unix)]
fn set_owner_only_permissions(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;

    let mut permissions = fs::metadata(path)
        .with_context(|| format!("failed to read metadata for {}", path.display()))?
        .permissions();
    permissions.set_mode(0o600);
    fs::set_permissions(path, permissions)
        .with_context(|| format!("failed to set file permissions on {}", path.display()))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_owner_only_permissions(_path: &Path) -> Result<()> {
    Ok(())
}
