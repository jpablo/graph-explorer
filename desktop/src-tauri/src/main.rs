#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::env;
use std::fs;
use std::fs::OpenOptions;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use tauri::Manager;

// AF_UNIX on every platform, rather than D4's "unix socket / named pipe".
// Windows has supported AF_UNIX since 1803, and one transport means ONE client
// implementation in `gx` instead of two that can drift — the same class of risk
// V-13 exists to contain. `uds_windows` mirrors the std API, so the only
// difference between platforms is this import.
#[cfg(unix)]
use std::os::unix::net::{UnixListener, UnixStream};
#[cfg(windows)]
use uds_windows::{UnixListener, UnixStream};

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

/// Everything a Tauri command is allowed to reach.
///
/// Note what is *absent*: `ControlFile`. The webview's three verbs (D3) cannot
/// leak a credential because they cannot see one — V-11 was a property of this
/// struct's shape before P5, and since P5 there is no credential anywhere to
/// leak. `ConnectionContext` is the socket's equivalent; the two share only the
/// registries.
#[derive(Clone)]
struct IpcState {
    access_policy: AccessPolicy,
    pending_sessions: PendingSessions,
    pending_opens: PendingOpens,
    page_ready: PageReady,
    audit_logger: AuditLogger,
    watch_registry: WatchRegistry,
    watch_controllers: WatchControllers,
    recent_write_hashes: RecentWriteHashes,
    /// Whether the page holds an edit that is not on disk (§7.4).
    ///
    /// Reported by the page as it changes, rather than asked for at close
    /// time. A question asked during teardown has to be answered during
    /// teardown, and §7.4 rules that out: IPC completion is not guaranteed
    /// then. A flag that is already correct needs no round trip.
    unsaved: Arc<AtomicBool>,
    /// Set by `confirm_close` once the person has answered, so the close that
    /// follows is allowed through instead of asking again.
    closing: Arc<AtomicBool>,
}

/// The error shape a rejected `invoke()` delivers to the page. Serialized as
/// the promise's rejection value, so the UI branches on `code`.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct IpcError {
    code: &'static str,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_revision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attempted_base_revision: Option<String>,
}

impl IpcError {
    fn new(code: &'static str, message: impl std::fmt::Display) -> Self {
        Self {
            code,
            message: message.to_string(),
            current_revision: None,
            attempted_base_revision: None,
        }
    }
}

/// Open a diagram for the UI: apply policy, start watching, return the bytes.
///
/// Policy is enforced here exactly as it is for a socket client — the webview
/// is an untrusted principal (§2), so its verbs are audited and checked rather
/// than trusted.
#[tauri::command(rename_all = "camelCase")]
fn open_document(
    app: tauri::AppHandle,
    state: tauri::State<'_, IpcState>,
    path: String,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    add_watch(
        &state.access_policy,
        &state.watch_registry,
        &state.watch_controllers,
        &state.recent_write_hashes,
        &state.audit_logger,
        &app,
        &path,
        "ui",
    )
    .map_err(|err| IpcError::new("WATCH_FAILED", err))?;

    // The UI reads the bytes from the snapshot below, so it needs no activation
    // event either way.
    get_document_snapshot(&state.watch_registry, &path)
        .map_err(|err| IpcError::new("DOCUMENT_READ_FAILED", err))
}

/// Compare-and-swap write from the UI. `baseRevision` is the revision the UI
/// last saw; a mismatch is a conflict, not a clobber.
#[tauri::command(rename_all = "camelCase")]
fn save_document(
    app: tauri::AppHandle,
    state: tauri::State<'_, IpcState>,
    path: String,
    text: String,
    base_revision: String,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    let app_handle = app.clone();
    put_document_snapshot(
        &state.watch_registry,
        &state.recent_write_hashes,
        &state.audit_logger,
        &|payload| emit_document_changed_event(&app_handle, payload),
        PutDocumentRequest {
            path,
            text,
            base_revision,
            source: Some("ui".to_string()),
        },
    )
    .map_err(|err| match err {
        PutDocumentError::Conflict {
            current_revision,
            attempted_base_revision,
        } => IpcError {
            code: "DOCUMENT_CONFLICT",
            message: "file changed on disk since it was loaded".to_string(),
            current_revision: Some(current_revision),
            attempted_base_revision: Some(attempted_base_revision),
        },
        PutDocumentError::Other(err) => IpcError::new("DOCUMENT_WRITE_FAILED", err),
    })
}

/// What the desktop currently has open. The UI's view of its own documents no
/// longer requires a status call against a credentialed HTTP endpoint.
#[tauri::command]
fn list_documents(state: tauri::State<'_, IpcState>) -> Vec<WatchDescriptor> {
    watched_items(&state.watch_registry)
}

/// What a client needs to find the desktop.
///
/// v1 carried a `port` and a `token`. Both are gone (D4): there is no port to
/// connect to, and the socket's own permissions decide who may speak to it, so
/// there is no credential left to hand out, leak, or rotate. `socket` is here
/// so a client never has to reconstruct the path — it is discovered, not
/// derived.
#[derive(Debug, Clone, Serialize)]
struct ControlFile {
    pid: u32,
    socket: String,
    version: String,
}

#[derive(Debug, Clone, Serialize)]
struct WatchDescriptor {
    path: String,
    format: String,
    /// D1: the file's content hash, not a counter.
    ///
    /// Any process can compute this from the bytes on disk without asking us,
    /// it survives a restart, and `baseRevision` becomes `If-Match` rather than
    /// "the number I was told last time". The cost D1 states plainly: an
    /// A -> B -> A edit returns to its original revision. For conflict
    /// detection that is correct — if what I based my edit on is what is there
    /// now, my edit is safe.
    revision: String,
}

type WatchRegistry = Arc<Mutex<HashMap<String, WatchDescriptor>>>;
type WatchControllers = Arc<Mutex<HashMap<String, WatchController>>>;
/// Path -> the content hash this process last wrote there.
///
/// A hex SHA-256 since V-13, not a u64: it is the same value `gx-core` computes
/// for the same bytes, which is what lets the two sides talk about a document's
/// identity at all.
type RecentWriteHashes = Arc<Mutex<HashMap<String, String>>>;

/// Session requests awaiting the page's answer, by id.
///
/// The session tier is the only one the shell cannot answer itself (D2.5: it
/// knows nothing about diagrams), so a request is parked here while the webview
/// runs it. The channel is how the socket thread waits without holding a lock.
type PendingSessions = Arc<Mutex<HashMap<u64, std::sync::mpsc::Sender<SessionOutcome>>>>;

/// Open requests awaiting the page's acknowledgment, by request id.
///
/// Same shape as [[PendingSessions]] and for the same reason: dispatching an
/// event proves only that the shell spoke, never that the right view mounted
/// and displayed the target (§4). The channel is how the socket thread waits
/// for the page without holding a lock.
type PendingOpens = Arc<Mutex<HashMap<u64, std::sync::mpsc::Sender<OpenOutcome>>>>;

/// Set once the webview has registered itself, with a condvar so a request that
/// arrives during startup WAITS instead of failing.
///
/// §4.1: the socket answers from the moment the process starts — it is bound
/// before the webview, which on Windows can take half a minute — so an open
/// arriving in that window used to be dispatched into a page with no listener
/// and silently lost.
type PageReady = Arc<(Mutex<bool>, std::sync::Condvar)>;

/// The page's answer to an open request.
#[derive(Debug, Clone)]
struct OpenOutcome {
    status: String,
    code: Option<String>,
    message: Option<String>,
    /// What the page says it displayed (§4): a diagram id, or a document
    /// session and its revision. Evidence of WHICH view answered, not merely
    /// that one did.
    view: Option<serde_json::Value>,
}

#[derive(Debug, Clone)]
struct SessionOutcome {
    ok: bool,
    result: serde_json::Value,
    /// The page's own code, so "nothing is open" reaches the caller as
    /// `NO_SESSION` rather than as a generic failure it would have to read the
    /// prose to recognise.
    code: String,
    message: String,
}

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
    revision: Option<String>,
    message: Option<String>,
}

struct WatchController {
    stop_tx: std::sync::mpsc::Sender<()>,
}

fn main() {
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
    let pending_sessions: PendingSessions = Arc::new(Mutex::new(HashMap::new()));
    let pending_opens: PendingOpens = Arc::new(Mutex::new(HashMap::new()));
    let page_ready: PageReady = Arc::new((Mutex::new(false), std::sync::Condvar::new()));

    // The control channel comes up BEFORE tauri, and this ordering is the whole
    // point of the split above.
    //
    // The socket used to be bound inside `setup`, which runs only after the
    // webview is initialized — on a cold Windows machine that is routinely 15s
    // and has been measured at over 30. For that entire window `connect` was
    // refused, so `gx status` said "no desktop is running" about a desktop the
    // user could see starting. Refused is indistinguishable from absent, so the
    // one state the user most wanted named was the one they could not get.
    //
    // Binding here makes `connect` succeed immediately. What the socket can DO
    // is then a second question, answered honestly per method: `status` needs
    // no window and works at once, and anything that does need one gets a typed
    // STARTING refusal rather than a hang. See `ConnectionContext::app`.
    let app_handle: SharedAppHandle = Arc::new(OnceLock::new());

    // §7.4: the page reports unsaved work as it changes, and a close is refused
    // while it stands. Declared here so BOTH front doors see the same flag —
    // the webview's commands set it, and `status` reports it.
    let unsaved = Arc::new(AtomicBool::new(false));
    let closing = Arc::new(AtomicBool::new(false));

    let listener = bind_control_socket().expect("failed to bind the control socket");

    spawn_control_server(
        listener,
        access_policy.clone(),
        pending_sessions.clone(),
        pending_opens.clone(),
        page_ready.clone(),
        request_limits.clone(),
        audit_logger.clone(),
        rate_limiter.clone(),
        watch_registry.clone(),
        watch_controllers.clone(),
        recent_write_hashes.clone(),
        app_handle.clone(),
        unsaved.clone(),
    );

    // AFTER the bind, not before. The file names a socket, so writing it first
    // published a path that did not answer yet — the state that made a starting
    // desktop look like a dead one. Now the file's existence and the socket's
    // existence are the same fact.
    write_runtime_file().expect("failed to write control runtime file");

    // The registries are Arcs, so the webview's IPC verbs and the socket's RPC
    // operate on the same state — two front doors, one house. Neither carries a
    // credential now: the webview is gated by an enumerated command list (D3),
    // the socket by its file permissions (D4).
    let ipc_state = IpcState {
        access_policy: access_policy.clone(),
        pending_sessions: pending_sessions.clone(),
        pending_opens: pending_opens.clone(),
        page_ready: page_ready.clone(),
        audit_logger: audit_logger.clone(),
        watch_registry: watch_registry.clone(),
        watch_controllers: watch_controllers.clone(),
        recent_write_hashes: recent_write_hashes.clone(),
        unsaved: unsaved.clone(),
        closing: closing.clone(),
    };

    tauri::Builder::default()
        .setup({
            let recent_write_hashes = recent_write_hashes.clone();
            let app_handle = app_handle.clone();
            move |app| {
                app.manage(ipc_state);

                // The socket has been accepting since before tauri started; this
                // is the moment its handlers stop answering STARTING. `set`
                // returns Err only if called twice, and `setup` runs once.
                let _ = app_handle.set(app.handle().clone());

                // macOS routes ⌘Q through the app menu's Quit item, and
                // Tauri's default one is `PredefinedMenuItem::quit`, which
                // calls the native terminate directly. That bypasses the event
                // loop entirely: neither `CloseRequested` nor `ExitRequested`
                // fires, and §7.4's question never gets asked — measured, not
                // assumed. The run loop reported `Exit` and nothing before it.
                //
                // So Quit becomes an item we own. Everything else in the menu
                // stays predefined: an app that edits text and lost ⌘C/⌘V would
                // be a far worse trade than the bug this fixes.
                #[cfg(target_os = "macos")]
                install_menu_with_asking_quit(app)?;

                // D7.3: the library on disk is the live state, so a record
                // written by `gx` with no window open has to reach the page
                // once there is one. Nothing sends a message — the shell
                // notices the directory moved and the page re-reads it.
                spawn_library_watcher(app.handle().clone(), recent_write_hashes.clone());

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
        .invoke_handler(tauri::generate_handler![
            health,
            open_document,
            save_document,
            list_documents,
            session_reply,
            viewer_ready,
            complete_open,
            hash_text,
            set_unsaved,
            confirm_close,
            library_list,
            library_read,
            library_write,
            library_delete
        ])
        .on_menu_event({
            let unsaved = unsaved.clone();
            let closing = closing.clone();
            move |app, event| {
                if event.id() == MENU_QUIT {
                    // The item this app owns, in place of the predefined Quit
                    // that terminates without asking anything.
                    if close_is_allowed(
                        closing.load(std::sync::atomic::Ordering::Relaxed),
                        unsaved.load(std::sync::atomic::Ordering::Relaxed),
                    ) {
                        app.exit(0);
                    } else {
                        ask_the_page_about_leaving(app);
                    }
                }
            }
        })
        .on_window_event({
            let unsaved = unsaved.clone();
            let closing = closing.clone();
            move |window, event| {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    if close_is_allowed(
                        closing.load(std::sync::atomic::Ordering::Relaxed),
                        unsaved.load(std::sync::atomic::Ordering::Relaxed),
                    ) {
                        return;
                    }

                    // §7.4: the person chooses Save, Discard or Cancel. The
                    // window stays until they do. Tauri closes without asking
                    // anything by default, and the page's `beforeunload` cannot
                    // serve here — it offers one generic prompt, not three
                    // answers, and a save it started during teardown is not
                    // guaranteed to finish.
                    api.prevent_close();
                    // Through the app handle: a `Window` cannot evaluate script,
                    // and the page lives in the webview it hosts.
                    ask_the_page_about_leaving(window.app_handle());
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while running graph explorer desktop")
        .run({
            let unsaved = unsaved.clone();
            let closing = closing.clone();
            move |app_handle, event| {
                // ⌘Q is an app QUIT, not a window close, and macOS routes the
                // two differently: a window's red button raises
                // `CloseRequested` on the window, while a quit raises
                // `ExitRequested` on the app. Handling only the first meant the
                // most common way to leave — ⌘Q — discarded an unsaved edit
                // with no prompt at all.
                if let tauri::RunEvent::ExitRequested { api, .. } = event {
                    if !close_is_allowed(
                        closing.load(std::sync::atomic::Ordering::Relaxed),
                        unsaved.load(std::sync::atomic::Ordering::Relaxed),
                    ) {
                        api.prevent_exit();
                        ask_the_page_about_leaving(app_handle);
                    }
                }
            }
        });
}

/// The macOS menu, with a Quit that asks before it goes (§7.4).
///
/// Built by hand because only the Quit item needs to change and Tauri offers no
/// way to swap one item of the default menu. Every other item is the predefined
/// one, so Edit keeps working exactly as it did.
#[cfg(target_os = "macos")]
fn install_menu_with_asking_quit(app: &tauri::App) -> Result<()> {
    use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};

    let handle = app.handle();
    let name = "Graph Explorer Desktop";

    let quit = MenuItem::with_id(handle, MENU_QUIT, "Quit Graph Explorer Desktop", true, Some("Cmd+Q"))?;

    let app_menu = Submenu::with_items(
        handle,
        name,
        true,
        &[
            &PredefinedMenuItem::about(handle, Some(name), None)?,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::hide(handle, None)?,
            &PredefinedMenuItem::hide_others(handle, None)?,
            &PredefinedMenuItem::show_all(handle, None)?,
            &PredefinedMenuItem::separator(handle)?,
            &quit,
        ],
    )?;

    // The one submenu a text editor cannot lose.
    let edit_menu = Submenu::with_items(
        handle,
        "Edit",
        true,
        &[
            &PredefinedMenuItem::undo(handle, None)?,
            &PredefinedMenuItem::redo(handle, None)?,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::cut(handle, None)?,
            &PredefinedMenuItem::copy(handle, None)?,
            &PredefinedMenuItem::paste(handle, None)?,
            &PredefinedMenuItem::select_all(handle, None)?,
        ],
    )?;

    let window_menu = Submenu::with_items(
        handle,
        "Window",
        true,
        &[
            &PredefinedMenuItem::minimize(handle, None)?,
            &PredefinedMenuItem::close_window(handle, None)?,
        ],
    )?;

    app.set_menu(Menu::with_items(handle, &[&app_menu, &edit_menu, &window_menu])?)?;
    Ok(())
}

/// The id of the Quit item this app owns, rather than the predefined one.
const MENU_QUIT: &str = "gx-quit";

/// Raise §7.4's question in the page: Save, Discard or Cancel.
///
/// One function for both ways out. The question does not depend on whether a
/// window was closed or the app was quit, and the page's answer — `confirm_close`
/// — releases both.
fn ask_the_page_about_leaving(app: &tauri::AppHandle) {
    for webview in app.webview_windows().values() {
        let _ = webview.eval("window.dispatchEvent(new CustomEvent('ge:close.requested'));");
    }
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
        revision: Option<String>,
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

    // The user's REAL home, deliberately -- not graph_explorer_dir(). $GX_HOME
    // relocates gx's own data; it must not be able to un-deny a secret.
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
        socket: control_socket_path()?.to_string_lossy().to_string(),
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

/// Where Graph Explorer keeps its own state: `library/` and `runtime/`.
///
/// `$GX_HOME` replaces that directory wholesale. Default: `~/.graph-explorer`.
///
/// It exists because the two halves could not be pointed at the same place.
/// This side follows `$HOME` (that is what `dirs::home_dir` does); `gx` is a
/// GraalVM native image reading `user.home`, which on macOS comes from the
/// password database and ignores `$HOME`. So a test that launched a desktop
/// under a redirected `$HOME` and then asked `gx` about it was silently asking
/// about the REAL desktop. One variable both sides read closes that.
///
/// NOT a general home override: the access policy's denied roots (`~/.ssh`,
/// `~/.gnupg`, ...) deliberately keep reading the true home, because relocating
/// gx's own data must never quietly un-deny a secret.
fn graph_explorer_dir() -> Result<PathBuf> {
    Ok(graph_explorer_dir_from(
        env::var_os("GX_HOME"),
        dirs::home_dir(),
    )?)
}

/// The decision, separated from the process it reads. Env vars are global and
/// a test that set one would race every other test in the binary; this way the
/// rule is exercised directly.
fn graph_explorer_dir_from(
    gx_home:  Option<std::ffi::OsString>,
    home_dir: Option<PathBuf>,
) -> Result<PathBuf> {
    if let Some(dir) = gx_home {
        // A set-but-BLANK variable means "unset" here. `export GX_HOME="$X"`
        // with X unset is how a shell script passes through an absent value,
        // and taking it literally would put the library at the filesystem root.
        // Whitespace counts as blank, matching GxHome.resolve on the Scala side
        // — the two must agree about this or one half relocates and the other
        // does not.
        let trimmed = dir.to_string_lossy().trim().to_string();
        if !trimmed.is_empty() {
            let dir = PathBuf::from(trimmed);
            // Refused, not absolutised. Absolutising resolves against the
            // reading process's working directory, and the two halves do not
            // share one: `gx` runs from the user's shell, a GUI-launched
            // desktop from wherever the launcher put it. A relative value would
            // name two different libraries, each half internally consistent
            // about the wrong one. Same rule, same wording as GxHome.resolve.
            if dir.is_relative() {
                anyhow::bail!(
                    "GX_HOME must be an absolute path, but is '{}'. A relative value resolves \
                     against each process's working directory, and gx and the desktop do not \
                     share one — they would use different libraries without saying so.",
                    dir.display()
                );
            }
            // The file-scope helper, which also refuses to pop past the
            // root — `/..` is `/`, not an escape.
            return Ok(normalize_dot_segments(&dir));
        }
    }
    let home_dir = home_dir.context("could not locate user home directory")?;
    Ok(home_dir.join(".graph-explorer"))
}

fn runtime_dir_path() -> Result<PathBuf> {
    Ok(graph_explorer_dir()?.join("runtime"))
}

fn control_socket_path() -> Result<PathBuf> {
    Ok(runtime_dir_path()?.join("control.sock"))
}

/// A unix socket address is a fixed-size struct: `sun_path` is 104 bytes on
/// macOS and 108 on Linux, and a longer path fails at `bind` with a bare
/// `EINVAL`. Home directories are usually short enough, but a CI runner or a
/// containerized `$HOME` need not be — so the limit is checked where it can be
/// explained rather than discovered as an unexplained startup failure.
const MAX_SOCKET_PATH_BYTES: usize = 100;

fn check_socket_path_length(path: &Path) -> Result<()> {
    let len = path.as_os_str().to_string_lossy().as_bytes().len();
    if len > MAX_SOCKET_PATH_BYTES {
        return Err(anyhow::anyhow!(
            "control socket path is {len} bytes, over the {MAX_SOCKET_PATH_BYTES}-byte limit \
             a unix socket address allows: {}",
            path.display()
        ));
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
struct PushTextRequest {
    text: String,
    /// REQUIRED. A push with no session names no document, and the page would
    /// have to guess which view the caller meant.
    #[serde(rename = "sessionId")]
    session_id: String,
}

#[derive(Debug, Deserialize)]
struct WatchRequest {
    path: String,
    #[serde(rename = "openInUi")]
    _open_in_ui: Option<bool>,
}

/// What `gx open` decided a reference means (§3.1).
///
/// `show` carries this instead of a bare path, because a library record and a
/// loose file have different owners and different persistence rules, and the
/// path alone cannot tell them apart. Resolving both to a path is what let an
/// open request lose its record identity — and let an unbound record be
/// unopenable, since it has no path to resolve to.
///
/// Typed only, by decision: `gx` and this shell ship in one release, so a
/// mixed pair is a version mismatch rather than a shape to negotiate.
#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
enum OpenTarget {
    Library {
        #[serde(rename = "diagramId")]
        diagram_id: String,
    },
    File {
        path: String,
    },
}

#[derive(Debug, Deserialize)]
struct ShowRequest {
    target: OpenTarget,
}

#[derive(Debug, Deserialize)]
struct UnwatchRequest {
    path: String,
}

/// A path, and nothing else. In v1 this travelled as a URL query parameter and
/// had to be percent-decoded on arrival — the decode that handled `%2F` and
/// nothing else, so a space or any Windows separator survived mangled and the
/// lookup missed. As a JSON string there is no encoding step to get wrong.
#[derive(Debug, Deserialize)]
struct DocumentRefRequest {
    path: String,
}

#[derive(Debug, Deserialize)]
struct PutDocumentRequest {
    path: String,
    text: String,
    #[serde(rename = "baseRevision")]
    base_revision: String,
    source: Option<String>,
}

#[derive(Debug, Serialize)]
struct DocumentSnapshot {
    path: String,
    text: String,
    format: String,
    revision: String,
    #[serde(rename = "timestampMs")]
    timestamp_ms: u64,
}

/// What the page is told when a document changes.
///
/// It used to carry `port` and `token` so the webview could `fetch()` back into
/// the control server. Under D3 the page talks over IPC instead, so the payload
/// is a pure document reference — the credential is not merely unused here, it
/// is not present (V-11).
#[derive(Debug, Serialize)]
struct DocumentChangedEventPayload {
    text: String,
    path: Option<String>,
    revision: Option<String>,
}

/// The control channel: a unix socket carrying newline-delimited JSON frames.
///
/// v1 was loopback HTTP with a bearer token. The token existed because a TCP
/// port is reachable by every process on the machine, so something had to
/// distinguish callers — and the credential then had to be handed to the
/// webview, which is what D3 spent a phase undoing. A unix socket moves that
/// decision to the OS: the socket is 0600 in a per-user directory, so the
/// permission check happens at `connect` and there is no secret to hold.
///
/// Two consequences worth naming, because they retire whole classes of bug:
///
/// - **A webview cannot `fetch()` a unix socket.** D3 stops relying on the page
///   to behave and becomes a property of the transport.
/// - **There are no URLs.** The percent-decoding hazard that produced the
///   Windows blocker (a path with a space, or any canonical Windows path,
///   arriving mangled) cannot recur: a path is a JSON string.
/// Bind the control socket. Separate from serving it, because binding is what
/// makes `connect` succeed and that must happen at the top of `main` -- long
/// before tauri has a window.
fn bind_control_socket() -> Result<UnixListener> {
    let socket_path = control_socket_path()?;
    check_socket_path_length(&socket_path)?;

    // A socket file outlives the process that made it, so a crashed desktop
    // leaves one behind. Removing it before binding is what makes a restart
    // work; a client that connects to the stale one gets ECONNREFUSED, which
    // is a better liveness signal than the file's existence.
    let _ = fs::remove_file(&socket_path);

    let listener = UnixListener::bind(&socket_path).with_context(|| {
        format!(
            "failed to bind control socket at {}",
            socket_path.display()
        )
    })?;
    set_owner_only_permissions(&socket_path)?;
    Ok(listener)
}

fn spawn_control_server(
    listener: UnixListener,
    access_policy: AccessPolicy,
    pending_sessions: PendingSessions,
    pending_opens: PendingOpens,
    page_ready: PageReady,
    request_limits: RequestLimits,
    audit_logger: AuditLogger,
    rate_limiter: RequestRateLimiter,
    watch_registry: WatchRegistry,
    watch_controllers: WatchControllers,
    recent_write_hashes: RecentWriteHashes,
    app_handle: SharedAppHandle,
    unsaved: Arc<AtomicBool>,
) -> JoinHandle<()> {
    let handle = std::thread::spawn(move || {
        for stream in listener.incoming() {
            let stream = match stream {
                Ok(stream) => stream,
                Err(err) => {
                    eprintln!("control socket accept failed: {err}");
                    continue;
                }
            };

            // A thread per connection, because a connection is long-lived: a
            // client may hold one open and issue many requests. Handling them
            // on the accept loop would let one idle client block every other.
            let context = ConnectionContext {
                access_policy: access_policy.clone(),
                pending_sessions: pending_sessions.clone(),
                pending_opens: pending_opens.clone(),
                page_ready: page_ready.clone(),
                request_limits: request_limits.clone(),
                audit_logger: audit_logger.clone(),
                rate_limiter: rate_limiter.clone(),
                watch_registry: watch_registry.clone(),
                watch_controllers: watch_controllers.clone(),
                recent_write_hashes: recent_write_hashes.clone(),
                app_handle: app_handle.clone(),
                unsaved: unsaved.clone(),
            };
            std::thread::spawn(move || {
                if let Err(err) = serve_connection(stream, &context) {
                    eprintln!("control connection ended: {err}");
                }
            });
        }
    });
    handle
}

/// The window layer, which does not exist until tauri's `setup` runs.
///
/// The control socket is bound and accepting BEFORE that (see `main`), so a
/// request can arrive while this is still empty. Handlers ask through
/// `ConnectionContext::app` and get a typed STARTING refusal rather than
/// blocking on a webview that may take half a minute to come up.
type SharedAppHandle = Arc<OnceLock<tauri::AppHandle>>;

#[derive(Clone)]
struct ConnectionContext {
    access_policy: AccessPolicy,
    pending_sessions: PendingSessions,
    pending_opens: PendingOpens,
    page_ready: PageReady,
    request_limits: RequestLimits,
    audit_logger: AuditLogger,
    rate_limiter: RequestRateLimiter,
    watch_registry: WatchRegistry,
    watch_controllers: WatchControllers,
    recent_write_hashes: RecentWriteHashes,
    app_handle: SharedAppHandle,
    /// Whether the page holds an edit that is not on disk (§7.4). Reported in
    /// `status` so a script can ask before it does anything disruptive, and so
    /// the close rule has an observable that does not need a window.
    unsaved: Arc<AtomicBool>,
}

impl ConnectionContext {
    /// The window layer, or a typed refusal while it is still starting.
    ///
    /// Every caller of this needs a window. `status` deliberately does not call
    /// it: answering "who are you and are you up" is the one question that must
    /// work before the webview does, because it is how `gx` tells a starting
    /// desktop from an absent one.
    fn app(&self) -> std::result::Result<&tauri::AppHandle, (&'static str, String)> {
        self.app_handle.get().ok_or((
            "STARTING",
            "the desktop is starting; its window is not up yet".to_string(),
        ))
    }
}

/// One request per line, one response per line, in order. JSON strings cannot
/// contain a raw newline, so the line IS the frame — no length prefix, and a
/// human can read the traffic.
fn serve_connection(stream: UnixStream, context: &ConnectionContext) -> Result<()> {
    let mut writer = stream.try_clone().context("failed to clone control stream")?;
    let reader = BufReader::new(stream);

    for line in reader.split(b'\n') {
        let line = line.context("failed to read control frame")?;
        if line.len() > context.request_limits.max_body_bytes {
            let response = RpcResponse::failure(
                None,
                "PAYLOAD_TOO_LARGE",
                format!(
                    "request frame is {} bytes (max {})",
                    line.len(),
                    context.request_limits.max_body_bytes
                ),
            );
            write_frame(&mut writer, &response)?;
            // The frame boundary is now untrustworthy — a body that overran the
            // limit may have been truncated mid-object — so the connection ends
            // rather than trying to resynchronize.
            return Ok(());
        }

        // V-16: the wire is UTF-8, named. A platform-default decode would make
        // an accented path a different path on Windows.
        let text = match String::from_utf8(line) {
            Ok(text) => text,
            Err(err) => {
                let response =
                    RpcResponse::failure(None, "INVALID_REQUEST", format!("frame is not UTF-8: {err}"));
                write_frame(&mut writer, &response)?;
                continue;
            }
        };
        if text.trim().is_empty() {
            continue;
        }

        let response = dispatch_frame(&text, context);
        write_frame(&mut writer, &response)?;
    }
    Ok(())
}

fn write_frame(writer: &mut UnixStream, response: &RpcResponse) -> Result<()> {
    let mut line = serde_json::to_vec(response).unwrap_or_else(|_| {
        br#"{"ok":false,"error":{"code":"INTERNAL","message":"serialization-error"}}"#.to_vec()
    });
    line.push(b'\n');
    writer.write_all(&line).context("failed to write control frame")?;
    writer.flush().context("failed to flush control frame")?;
    Ok(())
}

#[derive(Debug, Deserialize)]
struct RpcRequest {
    /// Echoed back so a client that pipelines can match answers to questions.
    #[serde(default)]
    id: Option<serde_json::Value>,
    method: String,
    #[serde(default)]
    params: serde_json::Value,
}

#[derive(Debug, Serialize)]
struct RpcResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<serde_json::Value>,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<RpcError>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RpcError {
    code: String,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_revision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attempted_base_revision: Option<String>,
}

impl RpcResponse {
    fn success(id: Option<serde_json::Value>, result: serde_json::Value) -> Self {
        Self {
            id,
            ok: true,
            result: Some(result),
            error: None,
        }
    }

    fn failure(
        id: Option<serde_json::Value>,
        code: &str,
        message: impl std::fmt::Display,
    ) -> Self {
        Self {
            id,
            ok: false,
            result: None,
            error: Some(RpcError {
                code: code.to_string(),
                message: message.to_string(),
                current_revision: None,
                attempted_base_revision: None,
            }),
        }
    }

    fn conflict(
        id: Option<serde_json::Value>,
        current_revision: String,
        attempted_base_revision: String,
    ) -> Self {
        Self {
            id,
            ok: false,
            result: None,
            error: Some(RpcError {
                code: "DOCUMENT_CONFLICT".to_string(),
                message: "file changed on disk since it was loaded".to_string(),
                current_revision: Some(current_revision),
                attempted_base_revision: Some(attempted_base_revision),
            }),
        }
    }
}

fn dispatch_frame(text: &str, context: &ConnectionContext) -> RpcResponse {
    let request: RpcRequest = match serde_json::from_str(text) {
        Ok(request) => request,
        Err(err) => {
            return RpcResponse::failure(None, "INVALID_REQUEST", format!("malformed frame: {err}"))
        }
    };
    let id = request.id.clone();

    // The rate limit survives the transport change, with a narrower job. It is
    // no longer a defence against other processes — the socket's permissions
    // are that — but a runaway agent in a loop is exactly the mistake D6 says
    // guardrails are for.
    if !context.rate_limiter.allow() {
        context.audit_logger.log_event(
            "request.rate_limited",
            None,
            None,
            "rejected",
            None,
            Some("local request rate limit exceeded"),
        );
        return RpcResponse::failure(id, "RATE_LIMITED", "local request rate limit exceeded");
    }

    dispatch_method(id, &request.method, request.params, context)
}

fn dispatch_method(
    id: Option<serde_json::Value>,
    method: &str,
    params: serde_json::Value,
    context: &ConnectionContext,
) -> RpcResponse {
    match method {
        "status" => match serde_json::to_value(status_body(context)) {
            Ok(value) => RpcResponse::success(id, value),
            Err(err) => RpcResponse::failure(id, "INTERNAL", err),
        },

        // `show` carries a TYPED target (§3.1); `watch` is a plain file
        // operation and keeps its path. They stopped being the same request the
        // moment an open could name a library record that has no path at all.
        "show" => {
            let request: ShowRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };

            let app = match context.app() {
                Ok(app) => app,
                Err((code, message)) => return RpcResponse::failure(id, code, message),
            };

            match request.target {
                // A record is the page's to resolve: it may have no origin, and
                // even with one the RECORD is authoritative (§2). Nothing is
                // watched here.
                OpenTarget::Library { diagram_id } => {
                    let target =
                        serde_json::json!({ "kind": "library", "diagramId": diagram_id });
                    match await_open(context, app, target, &format!("'{diagram_id}'")) {
                        Ok(_) => {
                            // Raised only AFTER the page confirmed it displayed
                            // the record. Focusing first would bring forward a
                            // window still showing the previous diagram.
                            let focused = focus_main_window(app);
                            context.audit_logger.log_event(
                                "show.library",
                                Some(&diagram_id),
                                Some("open"),
                                "ok",
                                None,
                                None,
                            );
                            RpcResponse::success(
                                id,
                                serde_json::json!({
                                    "kind": "library",
                                    "diagramId": diagram_id,
                                    "focused": focused
                                }),
                            )
                        }
                        Err((code, message)) => {
                            context.audit_logger.log_event(
                                "show.rejected",
                                Some(&diagram_id),
                                Some("open"),
                                "rejected",
                                None,
                                Some(&message),
                            );
                            RpcResponse::failure(id, code, message)
                        }
                    }
                }

                OpenTarget::File { path } => show_file(id, &path, context, app),
            }
        }

        "watch" => {
            let request: WatchRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            // Watching means telling a page, so this one needs the window.
            let app = match context.app() {
                Ok(app) => app,
                Err((code, message)) => return RpcResponse::failure(id, code, message),
            };
            match add_watch(
                &context.access_policy,
                &context.watch_registry,
                &context.watch_controllers,
                &context.recent_write_hashes,
                &context.audit_logger,
                app,
                &request.path,
                "api",
            ) {
                // Registration only. `watch` is resource observation, not
                // display (§12), so it never activates a document and never
                // changes what the window is showing.
                Ok(outcome) => watch_response(id, &outcome.watch, true, serde_json::Value::Null),
                Err(err) => {
                    context.audit_logger.log_event(
                        "watch.rejected",
                        Some(&request.path),
                        Some("api"),
                        "rejected",
                        None,
                        Some(&err.to_string()),
                    );
                    RpcResponse::failure(id, "WATCH_FAILED", err)
                }
            }
        }

        "unwatch" => {
            let request: UnwatchRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            match remove_watch(
                &context.watch_registry,
                &context.watch_controllers,
                &context.audit_logger,
                &request.path,
            ) {
                Ok(removed) => RpcResponse::success(id, serde_json::json!({ "removed": removed })),
                Err(err) => RpcResponse::failure(id, "UNWATCH_FAILED", err),
            }
        }

        "get-document" => {
            let request: DocumentRefRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            match get_document_snapshot(&context.watch_registry, &request.path) {
                Ok(snapshot) => match serde_json::to_value(snapshot) {
                    Ok(value) => RpcResponse::success(id, serde_json::json!({ "document": value })),
                    Err(err) => RpcResponse::failure(id, "INTERNAL", err),
                },
                Err(err) => RpcResponse::failure(id, "DOCUMENT_READ_FAILED", err),
            }
        }

        "put-document" => {
            let request: PutDocumentRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            let app_handle = match context.app() {
                Ok(app) => app.clone(),
                Err((code, message)) => return RpcResponse::failure(id, code, message),
            };
            match put_document_snapshot(
                &context.watch_registry,
                &context.recent_write_hashes,
                &context.audit_logger,
                &|payload| emit_document_changed_event(&app_handle, payload),
                request,
            ) {
                Ok(snapshot) => match serde_json::to_value(snapshot) {
                    Ok(value) => RpcResponse::success(id, serde_json::json!({ "document": value })),
                    Err(err) => RpcResponse::failure(id, "INTERNAL", err),
                },
                Err(PutDocumentError::Conflict {
                    current_revision,
                    attempted_base_revision,
                }) => RpcResponse::conflict(id, current_revision, attempted_base_revision),
                Err(PutDocumentError::Other(err)) => {
                    RpcResponse::failure(id, "DOCUMENT_WRITE_FAILED", err)
                }
            }
        }

        // A push NAMES the document it is aimed at, and goes through the
        // session tier so the page can refuse it.
        //
        // It used to emit a `document.changed` with a null path — text with no
        // addressee — and the page put it into whichever viewer was on screen.
        // That is the same defect class as an open reporting success for a file
        // no viewer showed: the caller was told "pushed: true" whatever it hit.
        //
        // The session tier already answers with the view's own state and
        // reports NO_SESSION when there is no view, so routing through it makes
        // the answer honest without inventing a second reply mechanism.
        "push-text" => {
            let request: PushTextRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            let frame = serde_json::json!({
                "command": "push-text",
                "params": { "sessionId": request.session_id, "text": request.text },
            });
            match run_session_command(context, frame) {
                Ok(_) => RpcResponse::success(id, serde_json::json!({ "pushed": true })),
                Err((code, message)) => RpcResponse::failure(id, code, message),
            }
        }

        "session" => match run_session_command(context, params) {
            Ok(value) => RpcResponse::success(id, value),
            Err((code, message)) => RpcResponse::failure(id, code, message),
        },

        other => RpcResponse::failure(id, "UNKNOWN_METHOD", format!("unknown method '{other}'")),
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatusBody {
    /// Whether the window layer is up. This was hardcoded `true` — it could
    /// only ever be read by a client that had already connected, so it said
    /// nothing. It now distinguishes a desktop that can show you something
    /// from one that is still starting.
    running: bool,
    /// "starting" until tauri's setup has run, then "running". A field rather
    /// than an inference, because `gx` decides liveness by whether the call
    /// SUCCEEDED and would otherwise read a starting desktop as a ready one.
    state: String,
    version: String,
    pid: u32,
    socket: String,
    watches: Vec<WatchDescriptor>,
    allowed_roots: Vec<String>,
    denied_roots: Vec<String>,
    max_body_bytes: usize,
    rate_limit_max_requests: usize,
    rate_limit_window_ms: u64,
    /// Whether the page holds an edit that is not on disk (§7.4). A close is
    /// refused while this is true, so it is worth being able to ask.
    unsaved_in_page: bool,
}

fn status_body(context: &ConnectionContext) -> StatusBody {
    // Deliberately does NOT go through `ConnectionContext::app`: this is the
    // one method that has to answer before the window exists, because it is how
    // a caller tells "starting" from "not there at all".
    let up = context.app_handle.get().is_some();
    StatusBody {
        running: up,
        state: if up { "running" } else { "starting" }.to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        pid: std::process::id(),
        socket: control_socket_path()
            .map(|path| path.to_string_lossy().to_string())
            .unwrap_or_default(),
        watches: watched_items(&context.watch_registry),
        allowed_roots: configured_allowed_roots(&context.access_policy),
        denied_roots: configured_denied_roots(&context.access_policy),
        max_body_bytes: context.request_limits.max_body_bytes,
        rate_limit_max_requests: context.request_limits.rate_limit_max_requests,
        rate_limit_window_ms: context.request_limits.rate_limit_window.as_millis() as u64,
        unsaved_in_page: context.unsaved.load(std::sync::atomic::Ordering::Relaxed),
    }
}

/// How long to wait for the page to answer a session command.
///
/// Generous enough that a busy render does not look like a failure, short
/// enough that a wedged page does not hang a socket client indefinitely — the
/// failure mode that matters, since a CLI with no timeout is a CLI that has to
/// be killed.
const SESSION_TIMEOUT: Duration = Duration::from_secs(5);

static SESSION_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Relay a session command to the webview and wait for its answer.
///
/// This is the one place the shell is a MIDDLEMAN rather than an implementer.
/// D2.5 keeps it diagram-ignorant and D3 keeps the page capability-free, so a
/// question about the live view has to cross both boundaries: the shell knows
/// who asked and how to answer them, the page knows what the answer is.
fn run_session_command(
    context: &ConnectionContext,
    params: serde_json::Value,
) -> std::result::Result<serde_json::Value, (&'static str, String)> {
    let command = params
        .get("command")
        .and_then(|v| v.as_str())
        .ok_or(("INVALID_REQUEST", "a session call needs a 'command'".to_string()))?;

    let id = SESSION_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let (sender, receiver) = std::sync::mpsc::channel::<SessionOutcome>();

    match context.pending_sessions.lock() {
        Ok(mut pending) => {
            pending.insert(id, sender);
        }
        Err(err) => return Err(("INTERNAL", format!("session registry lock poisoned: {err}"))),
    }

    // Registered BEFORE the page is told, so a reply that arrives immediately
    // still finds somewhere to go.
    let mut frame = params.clone();
    if let Some(object) = frame.as_object_mut() {
        object.insert("id".to_string(), serde_json::json!(id));
    }
    let script = match serde_json::to_string(&frame) {
        Ok(json) => format!(
            "window.dispatchEvent(new CustomEvent('ge:session.command', {{ detail: {json} }}));"
        ),
        Err(err) => {
            forget_session(context, id);
            return Err(("INTERNAL", err.to_string()));
        }
    };

    let windows = context.app()?.webview_windows();
    if windows.is_empty() {
        forget_session(context, id);
        // The session tier's defining limit, reported as itself: there is no
        // live view, so there is no answer — not an empty one.
        return Err((
            "NO_SESSION",
            format!("'{command}' needs a window, and the desktop has none open"),
        ));
    }
    for webview in windows.values() {
        if let Err(err) = webview.eval(&script) {
            forget_session(context, id);
            return Err(("SESSION_FAILED", format!("could not reach the page: {err}")));
        }
    }

    let outcome = receiver.recv_timeout(SESSION_TIMEOUT);
    forget_session(context, id);

    match outcome {
        Ok(outcome) if outcome.ok => Ok(outcome.result),
        Ok(outcome) => Err((
            // An ALLOWLIST, so the page cannot mint codes a caller would have
            // to discover by reading prose. Each one here means a different
            // next move: open something, fix the argument, or look at what is
            // on screen. Anything else is a failure with no advice to give.
            match outcome.code.as_str() {
                "NO_SESSION" => "NO_SESSION",
                "INVALID_REQUEST" => "INVALID_REQUEST",
                "VIEW_REJECTED" => "VIEW_REJECTED",
                _ => "SESSION_FAILED",
            },
            outcome.message,
        )),
        Err(_) => Err((
            "SESSION_TIMEOUT",
            format!("the page did not answer '{command}' within {SESSION_TIMEOUT:?}"),
        )),
    }
}

/// How long an open waits for the page to say it displayed the target.
///
/// Longer than a session command's five seconds because this one may include a
/// cold start: the socket answers before the webview exists (§4.1), and a
/// Windows runner has been seen taking half a minute to bring one up. Still
/// bounded — a CLI that never returns is a CLI that has to be killed.
const OPEN_TIMEOUT: Duration = Duration::from_secs(45);

static OPEN_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Ask the page to display a target, and WAIT for it to say it did.
///
/// Dispatching an event proves only that the shell spoke. It does not prove
/// that a viewer mounted, that it mounted the right one, or that anything
/// reached the screen — which is why `gx open` used to report success for
/// opens the user never saw (§4).
fn await_open(
    context: &ConnectionContext,
    app: &tauri::AppHandle,
    payload_target: serde_json::Value,
    audit_subject: &str,
) -> std::result::Result<serde_json::Value, (&'static str, String)> {
    // Wait for the page to exist before speaking to it. An open issued during
    // startup is normal — a shell alias that launches the desktop and opens a
    // diagram does exactly this — and dispatching into a page with no listener
    // is how that request used to vanish.
    if let Err(err) = wait_for_page(&context.page_ready) {
        return Err(err);
    }

    let id = OPEN_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let (sender, receiver) = std::sync::mpsc::channel::<OpenOutcome>();

    match context.pending_opens.lock() {
        Ok(mut pending) => {
            pending.insert(id, sender);
        }
        Err(err) => return Err(("INTERNAL", format!("open registry lock poisoned: {err}"))),
    }

    // Registered BEFORE the page is told, so an acknowledgment that arrives
    // immediately still finds somewhere to go.
    let frame = serde_json::json!({ "requestId": id, "target": payload_target });
    let script = match serde_json::to_string(&frame) {
        Ok(json) => format!(
            "window.dispatchEvent(new CustomEvent('ge:open.requested', {{ detail: {json} }}));"
        ),
        Err(err) => {
            forget_open(context, id);
            return Err(("INTERNAL", err.to_string()));
        }
    };

    let windows = app.webview_windows();
    if windows.is_empty() {
        forget_open(context, id);
        return Err((
            "NO_WINDOW",
            "the desktop has no window to show it in".to_string(),
        ));
    }
    for webview in windows.values() {
        if let Err(err) = webview.eval(&script) {
            forget_open(context, id);
            return Err(("NO_WINDOW", format!("could not reach the page: {err}")));
        }
    }

    let outcome = receiver.recv_timeout(OPEN_TIMEOUT);
    forget_open(context, id);

    match outcome {
        Ok(outcome) if outcome.status == "displayed" => {
            Ok(outcome.view.unwrap_or(serde_json::Value::Null))
        }
        Ok(outcome) => {
            let code = match outcome.code.as_deref() {
                Some("DIAGRAM_NOT_FOUND") => "DIAGRAM_NOT_FOUND",
                Some("DOCUMENT_NOT_FOUND") => "DOCUMENT_NOT_FOUND",
                Some("OPEN_DENIED") => "OPEN_DENIED",
                Some("PARSE_FAILED") => "PARSE_FAILED",
                Some("ACTIVATION_REFUSED") => "ACTIVATION_REFUSED",
                _ => "VIEW_REJECTED",
            };
            Err((
                code,
                outcome
                    .message
                    .unwrap_or_else(|| format!("the page did not display {audit_subject}")),
            ))
        }
        Err(_) => Err((
            "OPEN_TIMEOUT",
            format!("the page did not acknowledge {audit_subject} within {OPEN_TIMEOUT:?}"),
        )),
    }
}

/// Block until the webview has registered itself, or the open budget runs out.
fn wait_for_page(page_ready: &PageReady) -> std::result::Result<(), (&'static str, String)> {
    let (lock, condvar) = &**page_ready;
    let ready = match lock.lock() {
        Ok(guard) => guard,
        Err(err) => return Err(("INTERNAL", format!("page-ready lock poisoned: {err}"))),
    };
    match condvar.wait_timeout_while(ready, OPEN_TIMEOUT, |ready| !*ready) {
        Ok((_, timeout)) if timeout.timed_out() => Err((
            "OPEN_TIMEOUT",
            format!("the desktop's window did not come up within {OPEN_TIMEOUT:?}"),
        )),
        Ok(_) => Ok(()),
        Err(err) => Err(("INTERNAL", format!("page-ready wait failed: {err}"))),
    }
}

fn forget_open(context: &ConnectionContext, id: u64) {
    if let Ok(mut pending) = context.pending_opens.lock() {
        pending.remove(&id);
    }
}

/// Drop a pending request whatever happened, so a timed-out or failed call does
/// not leave an entry that a late reply could match.
fn forget_session(context: &ConnectionContext, id: u64) {
    if let Ok(mut pending) = context.pending_sessions.lock() {
        pending.remove(&id);
    }
}

/// The page announcing it is listening (§4.1).
///
/// Idempotent, and deliberately not tied to a route: it means "the event
/// listeners exist", not "a diagram is open". An open request that arrived
/// during startup is waiting on this, and every one of them wakes at once.
#[tauri::command(rename_all = "camelCase")]
fn viewer_ready(state: tauri::State<'_, IpcState>) {
    let (lock, condvar) = &*state.page_ready;
    if let Ok(mut ready) = lock.lock() {
        *ready = true;
        condvar.notify_all();
    }
}

/// The page reporting what became of an open request.
///
/// This is what makes `gx open` honest: until the page says `displayed`, the
/// shell knows only that it dispatched an event — not that any viewer mounted,
/// nor that it mounted the right one.
#[tauri::command(rename_all = "camelCase")]
fn complete_open(
    state: tauri::State<'_, IpcState>,
    request_id: u64,
    status: String,
    code: Option<String>,
    message: Option<String>,
    view: Option<serde_json::Value>,
) {
    let outcome = OpenOutcome {
        status,
        code,
        message,
        view,
    };
    if let Ok(mut pending) = state.pending_opens.lock() {
        if let Some(sender) = pending.remove(&request_id) {
            let _ = sender.send(outcome);
        }
    }
}

/// Whether a close may proceed without asking (§7.4).
///
/// A free function so the rule can be tested without a window: the decision is
/// the part worth pinning, and the event plumbing around it is not.
///
/// `confirmed` means the person has already answered. `unsaved` means the page
/// holds an edit that is not on disk. Anything else may close.
fn close_is_allowed(confirmed: bool, unsaved: bool) -> bool {
    confirmed || !unsaved
}

/// The page reporting whether it holds an edit that is not on disk (§7.4).
///
/// Pushed as it changes rather than pulled at close time. Asking during
/// teardown means answering during teardown, and §7.4 rules that out: IPC
/// completion is not guaranteed then, so a save started at that moment may
/// never land. A flag that is already correct costs nothing to read.
#[tauri::command(rename_all = "camelCase")]
fn set_unsaved(state: tauri::State<'_, IpcState>, unsaved: bool) {
    state.unsaved.store(unsaved, std::sync::atomic::Ordering::Relaxed);
}

/// The page saying the person has answered, and the window may go.
///
/// Reached after Save completed or after Discard. Cancel simply never calls
/// this, and the window stays as it is.
#[tauri::command(rename_all = "camelCase")]
fn confirm_close(app: tauri::AppHandle, state: tauri::State<'_, IpcState>) {
    state.closing.store(true, std::sync::atomic::Ordering::Relaxed);
    // `exit`, not `close`. The question is raised by two different intents —
    // closing the window and quitting the app — and the answer has to serve
    // both. Closing windows would leave a quit half-done: the person said
    // "discard and go", and the app would still be running.
    app.exit(0);
}

/// Hash text for the page, so one SHA-256 serves the whole system.
///
/// The page reconciles a bound record against its origin (§8), and that needs
/// `local` — the record's text hashed as it would be STORED. The page cannot
/// compute it: `Hashing` is `MessageDigest`, which does not exist in Scala.js.
///
/// A second digest in the webview would be the alternative, and it would have
/// to agree with this one byte for byte forever. `content_hash` is already
/// pinned against `Hashing` by the shared `content-hashes.json` fixture, so
/// routing the page through here means there is nothing new to keep in
/// agreement.
///
/// The LINE ENDING is applied before the call, not here. `LineEnding.applyTo`
/// is shared Scala the page already has, and reimplementing that convention in
/// Rust would recreate exactly the divergence this command exists to avoid.
/// This hashes the bytes it is given.
#[tauri::command(rename_all = "camelCase")]
fn hash_text(text: String) -> String {
    content_hash(text.as_bytes())
}

/// The page's answer to a session command.
///
/// A Tauri command rather than anything the page can reach on its own: it can
/// only ANSWER a question the shell asked, and an id nobody is waiting for is
/// dropped. That keeps the inversion honest — the webview serves this tier
/// without gaining the ability to initiate anything.
#[tauri::command(rename_all = "camelCase")]
fn session_reply(
    state: tauri::State<'_, IpcState>,
    id: u64,
    ok: bool,
    result: Option<serde_json::Value>,
    code: Option<String>,
    message: Option<String>,
) {
    let outcome = SessionOutcome {
        ok,
        result: result.unwrap_or(serde_json::Value::Null),
        code: code.unwrap_or_else(|| "SESSION_FAILED".to_string()),
        message: message.unwrap_or_else(|| "the page reported a failure".to_string()),
    };
    if let Ok(mut pending) = state.pending_sessions.lock() {
        if let Some(sender) = pending.remove(&id) {
            let _ = sender.send(outcome);
        }
    }
}

/// `show` has to produce a *visible* diagram, so it raises the window. Reported
/// rather than assumed: a headless or minimized desktop still watched the file,
/// and `gx open` should say which of the two happened.
fn focus_main_window(app_handle: &tauri::AppHandle) -> bool {
    match app_handle.get_webview_window("main") {
        Some(window) => {
            let _ = window.unminimize();
            let _ = window.show();
            window.set_focus().is_ok()
        }
        None => false,
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
        current_revision: String,
        attempted_base_revision: String,
    },
    Other(anyhow::Error),
}

/// `notify` is a parameter rather than an `&AppHandle` for two reasons: the
/// caller may be an IPC command or the HTTP server, and V-12 is about what
/// happens when notification FAILS — which is only testable if a test can hand
/// in a notifier that does.
fn put_document_snapshot(
    watch_registry: &WatchRegistry,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
    notify: &dyn Fn(&DocumentChangedEventPayload) -> Result<()>,
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

    // D1's If-Match: compare what is ON DISK, not what the registry last
    // recorded. A counter could only answer "has anything happened since I told
    // you a number"; a hash answers the question that actually matters — "is
    // the content I based this edit on still there" — and answers it even when
    // another process wrote the file without going through us.
    let current_on_disk = fs::read(&normalized)
        .map(|bytes| content_hash(&bytes))
        .unwrap_or_default();
    if payload.base_revision != current_on_disk {
        audit_logger.log_event(
            "document.conflict",
            Some(&normalized),
            payload.source.as_deref(),
            "rejected",
            Some(current_on_disk.clone()),
            Some(&format!(
                "attemptedBaseRevision={}, currentRevision={}",
                payload.base_revision, current_on_disk
            )),
        );
        return Err(PutDocumentError::Conflict {
            current_revision: current_on_disk,
            attempted_base_revision: payload.base_revision,
        });
    }

    write_file_atomic(&normalized, &payload.text).map_err(PutDocumentError::Other)?;
    let content_hash = content_hash(payload.text.as_bytes());
    if let Ok(mut writes) = recent_write_hashes.lock() {
        writes.insert(normalized.clone(), content_hash.clone());
    }

    watch.revision = content_hash.clone();
    let revision = watch.revision.clone();
    let format = watch.format.clone();
    let source = payload
        .source
        .clone()
        .unwrap_or_else(|| "unknown".to_string());
    drop(registry);

    let event_payload = DocumentChangedEventPayload {
        text: payload.text.clone(),
        path: Some(normalized.clone()),
        revision: Some(revision.clone()),
    };

    // V-12. The bytes are on disk and the revision has moved; a window that is
    // gone (or one that refused the script) cannot un-write them. Reporting a
    // failure here told the caller its save was lost when it was not — and `gx
    // set` retried against a revision that had already advanced. The save is
    // what succeeded, so the save is what we report; the notification failure
    // is real and goes to the audit log, where the outcome is legible instead
    // of inverted.
    let notify_failure = notify(&event_payload).err();

    audit_logger.log_event(
        "document.written",
        Some(&normalized),
        Some(&source),
        "ok",
        Some(revision.clone()),
        notify_failure
            .as_ref()
            .map(|err| format!("write succeeded; UI notification failed: {err}"))
            .as_deref(),
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

    // A stale temp from a crashed write would be REUSED by `fs::write`, which
    // truncates rather than recreates — and keeps whatever mode it already had.
    // That matters below, where the freshly created temp's mode is read as the
    // umask probe.
    let _ = fs::remove_file(&temp_path);

    fs::write(&temp_path, text)
        .with_context(|| format!("failed to write temporary file {}", temp_path.display()))?;

    // What a new file should end up as: whatever the OS just gave the temp,
    // which is `0666 & ~umask` — the user's own default, read rather than
    // guessed. Captured BEFORE the temp is locked down below.
    let created_mode = file_mode(&temp_path);

    // Owner-only while it sits in the directory as a temp. This is a small
    // window (the next two calls), and it is not a security boundary — the
    // directory is the user's own — but a half-written diagram has no business
    // being readable by anyone the finished one would not be.
    set_owner_only_permissions(&temp_path)?;

    // V-03. The bits the target already has, applied to the temp so the RENAME
    // carries them — rather than chmod'ing after the fact, which would leave a
    // moment where the real file is 0600.
    //
    // v1 did not do this at all: it chmod'ed the target to 0600 on every save,
    // so one save silently turned a group-readable diagram in a shared checkout
    // into an owner-only one. Nobody asked for that and nobody would look for
    // it. `gx-core` fixed the same bug in P1 (`AtomicFiles.write`); this is the
    // desktop's own writer finally agreeing with it.
    apply_intended_permissions(&target, &temp_path, created_mode)?;

    fs::rename(&temp_path, &target)
        .with_context(|| format!("failed to move temporary file into {}", target.display()))?;
    Ok(())
}

/// Permission bits only.
///
/// `mode()` returns the raw `st_mode`, file-type bits (`S_IFREG`) included.
/// `chmod` happens to ignore those, so passing them through would work — and
/// would be a fact about the platform rather than about the code. Masked to
/// `0o7777` rather than `0o777` so setgid survives: on a shared group checkout
/// it is the bit most likely to be deliberate, and V-03 says *the target's
/// existing bits*, not most of them.
#[cfg(unix)]
fn file_mode(path: &Path) -> Option<u32> {
    use std::os::unix::fs::PermissionsExt;
    fs::metadata(path)
        .ok()
        .map(|meta| meta.permissions().mode() & 0o7777)
}

#[cfg(not(unix))]
fn file_mode(_path: &Path) -> Option<u32> {
    None
}

/// Give `temp` the mode the finished file should have: the target's, if it
/// exists, and otherwise the umask default `created_mode` was probed from.
///
/// Best effort by design. A filesystem that cannot express POSIX bits must not
/// fail a save over it — the write is the operation, the mode is a property of
/// it.
#[cfg(unix)]
fn apply_intended_permissions(target: &Path, temp: &Path, created_mode: Option<u32>) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;

    let intended = file_mode(target).or(created_mode);
    if let Some(mode) = intended {
        let _ = fs::set_permissions(temp, fs::Permissions::from_mode(mode));
    }
    Ok(())
}

#[cfg(not(unix))]
fn apply_intended_permissions(_target: &Path, _temp: &Path, _created_mode: Option<u32>) -> Result<()> {
    // Windows has no POSIX bits to preserve, which is also why the Windows
    // smoke gate does not assert this one.
    Ok(())
}

fn current_time_ms() -> u64 {
    let now = std::time::SystemTime::now();
    now.duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

/// V-13: canonicalization, matching `gx-core`'s `FileOrigins.canonicalize`
/// rule for rule.
///
/// This and the content hash are the join key for the whole library (§4), and
/// they are the one contract this codebase writes twice in two languages. The
/// rules are specified in `local-protocol/fixtures/canonicalization.json`, which
/// both test suites read.
///
/// What changed from v1's `canonicalize(...).unwrap_or(absolute)`: that fell
/// back to the RAW absolute path whenever the target did not exist yet — and
/// creating a file, or watching one a generator has not written, are ordinary.
/// So `..` and unresolved symlinks survived into policy checks, and the same
/// file had two identities depending on whether it happened to exist when it
/// was first named. The brief called this "safe direction, but incidental
/// rather than specified".
fn normalize_path(path: &str) -> Result<String> {
    let input = PathBuf::from(path);
    let absolute = if input.is_absolute() {
        input
    } else {
        std::env::current_dir()
            .context("failed to read current working directory")?
            .join(input)
    };

    if let Ok(real) = fs::canonicalize(&absolute) {
        return Ok(strip_verbatim_prefix(&real));
    }

    // It does not exist yet. Resolve the dot segments ourselves, then real-path
    // the deepest ancestor that DOES exist and re-attach the rest, so a
    // not-yet-created file still gets a stable identity.
    let normalized = normalize_dot_segments(&absolute);
    let mut ancestor = normalized.as_path();
    loop {
        match ancestor.parent() {
            None => return Ok(strip_verbatim_prefix(&normalized)),
            Some(parent) => {
                if let Ok(real_parent) = fs::canonicalize(parent) {
                    let rest = normalized
                        .strip_prefix(parent)
                        .unwrap_or_else(|_| Path::new(""));
                    return Ok(strip_verbatim_prefix(&real_parent.join(rest)));
                }
                ancestor = parent;
            }
        }
    }
}

/// Resolve `.` and `..` lexically.
///
/// Lexical is correct here precisely because the path does not exist: there is
/// no symlink at the end to resolve, and the existing ancestor gets a real
/// `canonicalize` afterwards.
fn normalize_dot_segments(path: &Path) -> PathBuf {
    use std::path::Component;
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                // Never pop past the root: `/..` is `/`, not an escape.
                if !matches!(
                    out.components().next_back(),
                    None | Some(Component::RootDir) | Some(Component::Prefix(_))
                ) {
                    out.pop();
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Windows' `fs::canonicalize` returns a `\\?\C:\…` verbatim path; Java's
/// `toRealPath` returns `C:\…`. Left alone, the same file would have two
/// identities depending on which language named it — which is exactly the
/// drift V-13 exists to prevent. Strip it here, where the divergence is.
#[cfg(windows)]
fn strip_verbatim_prefix(path: &Path) -> String {
    let text = path.to_string_lossy().to_string();
    // `\\?\UNC\server\share` is a UNC path; its non-verbatim spelling is
    // `\\server\share`, so the prefix is replaced rather than removed.
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        return format!(r"\\{rest}");
    }
    text.strip_prefix(r"\\?\").unwrap_or(&text).to_string()
}

#[cfg(not(windows))]
fn strip_verbatim_prefix(path: &Path) -> String {
    path.to_string_lossy().to_string()
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

/// The descriptor already registered for this path, if any.
///
/// Split out so the "is it already watched?" decision is testable without a
/// running window: taking this branch is exactly what made a second `gx open`
/// on a live path emit nothing and merely raise the window.
fn existing_watch(
    watch_registry: &WatchRegistry,
    normalized: &str,
) -> Result<Option<WatchDescriptor>> {
    let registry = watch_registry
        .lock()
        .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?;
    Ok(registry.get(normalized).cloned())
}

/// A watch, and whether THIS call is what created it.
///
/// Registration and display activation are different operations (§4.2 of
/// docs/desktop-open-targets-and-persistence.md). Registration is idempotent;
/// activation must reach the page every time. Callers that show a document
/// need to know which happened, because the initial document event is emitted
/// only on creation.
struct WatchOutcome {
    watch: WatchDescriptor,
    created: bool,
}

fn add_watch(
    access_policy: &AccessPolicy,
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
    app_handle: &tauri::AppHandle,
    path: &str,
    source: &str,
) -> Result<WatchOutcome> {
    let normalized = normalize_path(path)?;
    let normalized_path = PathBuf::from(&normalized);
    ensure_watch_target_is_file(&normalized_path)?;
    ensure_path_not_denied(access_policy, &normalized_path)?;
    ensure_path_allowed(access_policy, &normalized_path)?;

    if let Some(existing) = existing_watch(watch_registry, &normalized)? {
        return Ok(WatchOutcome {
            watch: existing,
            created: false,
        });
    }

    let watch = WatchDescriptor {
        format: infer_format_from_path(&normalized),
        path: normalized.clone(),
        // Seeded from the bytes, not from 1. A restart therefore hands out the
        // same revision it did before, which is what "revisions restart at 1"
        // used to break.
        revision: fs::read(&normalized).map(|b| content_hash(&b)).unwrap_or_default(),
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
            revision: Some(watch.revision.clone()),
        };
        let _ = emit_document_changed_event(app_handle, &initial_event);
    }

    let controller = spawn_watch_loop(
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
        Some(source),
        "ok",
        Some(watch.revision.clone()),
        None,
    );

    Ok(WatchOutcome {
        watch,
        created: true,
    })
}

/// Push the current bytes of an already-registered document at the page.
///
/// The counterpart to registration being idempotent: `add_watch` emits the
/// initial document event only when it creates the watch, so a second `gx open`
/// on a path already being watched used to return the existing descriptor and
/// emit nothing — the window came forward still showing whatever it had been
/// showing. Activation is what makes the open actually open something.
fn activate_document(app_handle: &tauri::AppHandle, watch: &WatchDescriptor) -> Result<()> {
    let text = fs::read_to_string(&watch.path)?;
    let payload = DocumentChangedEventPayload {
        text,
        path: Some(watch.path.clone()),
        revision: Some(watch.revision.clone()),
    };
    emit_document_changed_event(app_handle, &payload)
}

/// The `show` path for a LOOSE FILE: watch it, put it on screen, raise the
/// window — and refuse to call any of that a success if there is no page.
fn show_file(
    id: Option<serde_json::Value>,
    path: &str,
    context: &ConnectionContext,
    app: &tauri::AppHandle,
) -> RpcResponse {
    // BEFORE `add_watch`, which emits the document event that carries the
    // file's text. That event is dispatched into the page and is NOT queued the
    // way an open request is (§4.1), so emitting it before the page is
    // listening drops it — and the open request that follows then finds no
    // session and answers DOCUMENT_NOT_FOUND.
    //
    // The control socket answers from process start, well before the webview
    // runs, so "the desktop is up" is not "the page is listening". A `gx open`
    // issued by a shell alias that launches the desktop lands in exactly that
    // window.
    if let Err((code, message)) = wait_for_page(&context.page_ready) {
        return RpcResponse::failure(id, code, message);
    }

    match add_watch(
        &context.access_policy,
        &context.watch_registry,
        &context.watch_controllers,
        &context.recent_write_hashes,
        &context.audit_logger,
        app,
        path,
        "open",
    ) {
        Ok(outcome) => {
            // Registration is idempotent; DISPLAY is not (§4.2). A watch that
            // already existed emitted no document event, so without this the
            // page would have nothing current to route to — and a second
            // `gx open` only raised the window over the previous document.
            if !outcome.created {
                if let Err(err) = activate_document(app, &outcome.watch) {
                    context.audit_logger.log_event(
                        "show.rejected",
                        Some(&outcome.watch.path),
                        Some("open"),
                        "rejected",
                        None,
                        Some(&err.to_string()),
                    );
                    return RpcResponse::failure(id, "NO_WINDOW", err);
                }
            }

            // The document event above and this request are both evaluated in
            // the same webview, in order, and the page handles the document
            // synchronously. So the session exists by the time the page is
            // asked to open it.
            let target = serde_json::json!({ "kind": "file", "path": outcome.watch.path });
            match await_open(context, app, target, &format!("'{}'", outcome.watch.path)) {
                Ok(view) => {
                    // Raised only AFTER the page confirmed it displayed the
                    // file. Focusing first would bring forward a window still
                    // showing the previous document.
                    let focused = focus_main_window(app);
                    context.audit_logger.log_event(
                        "show.file",
                        Some(&outcome.watch.path),
                        Some("open"),
                        "ok",
                        None,
                        None,
                    );
                    watch_response(id, &outcome.watch, focused, view)
                }
                Err((code, message)) => {
                    // The watch is needed before activation because its event gives the
                    // page the bytes to parse. Roll back only a watch that this failed
                    // request created. A shared watch has another owner and must remain.
                    if let Err(rollback_err) = rollback_failed_open(
                        &outcome,
                        &context.watch_registry,
                        &context.watch_controllers,
                        &context.audit_logger,
                    ) {
                        context.audit_logger.log_event(
                            "watch.rollback_failed",
                            Some(&outcome.watch.path),
                            Some("open"),
                            "error",
                            None,
                            Some(&rollback_err.to_string()),
                        );
                    }
                    context.audit_logger.log_event(
                        "show.rejected",
                        Some(&outcome.watch.path),
                        Some("open"),
                        "rejected",
                        None,
                        Some(&message),
                    );
                    RpcResponse::failure(id, code, message)
                }
            }
        }
        Err(err) => {
            context.audit_logger.log_event(
                "watch.rejected",
                Some(path),
                Some("open"),
                "rejected",
                None,
                Some(&err.to_string()),
            );
            RpcResponse::failure(id, "WATCH_FAILED", err)
        }
    }
}

/// Remove only the watch that a failed open created.
fn rollback_failed_open(
    outcome: &WatchOutcome,
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    audit_logger: &AuditLogger,
) -> Result<()> {
    if outcome.created {
        remove_watch(
            watch_registry,
            watch_controllers,
            audit_logger,
            &outcome.watch.path,
        )?;
    }
    Ok(())
}

/// The descriptor plus whether a window came forward, which is what both
/// `watch` and a file `show` answer with.
fn watch_response(
    id: Option<serde_json::Value>,
    watch: &WatchDescriptor,
    focused: bool,
    view: serde_json::Value,
) -> RpcResponse {
    match serde_json::to_value(watch) {
        Ok(mut value) => {
            if let Some(object) = value.as_object_mut() {
                object.insert("focused".to_string(), serde_json::Value::Bool(focused));
                // Absent for a plain `watch`: registration observes a file and
                // displays nothing, so it has no view to report (§12).
                if !view.is_null() {
                    object.insert("view".to_string(), view);
                }
            }
            RpcResponse::success(id, value)
        }
        Err(err) => RpcResponse::failure(id, "INTERNAL", err),
    }
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
    watch_registry: WatchRegistry,
    recent_write_hashes: RecentWriteHashes,
    app_handle: tauri::AppHandle,
    path: String,
) -> WatchController {
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
    std::thread::spawn(move || {
        let mut previous_hash: Option<String> = hash_file_contents(&path).ok();
        let mut pending: Option<(Instant, String, String)> = None;

        loop {
            if stop_rx.try_recv().is_ok() {
                break;
            }

            // Bytes, then decode — not `read_to_string`. The hash must be over
            // what is on disk (V-13), and a file being written by an editor can
            // momentarily hold a partial multi-byte character that a lossy
            // decode would smooth over into a hash that never existed.
            if let Ok(raw) = fs::read(&path) {
                let current_hash = content_hash(&raw);
                let contents = String::from_utf8_lossy(&raw).into_owned();
                let pending_hash = pending.as_ref().map(|(_, _, hash)| hash.clone());

                let is_self_write = recent_write_hashes
                    .lock()
                    .ok()
                    .and_then(|mut writes| {
                        let matched = writes.get(&path) == Some(&current_hash);
                        if matched {
                            writes.remove(&path);
                            Some(true)
                        } else {
                            Some(false)
                        }
                    })
                    .unwrap_or(false);

                if is_self_write {
                    previous_hash = Some(current_hash.clone());
                    pending = None;
                    std::thread::sleep(WATCH_POLL_INTERVAL);
                    continue;
                }

                if Some(&current_hash) != previous_hash.as_ref()
                    && Some(&current_hash) != pending_hash.as_ref()
                {
                    pending = Some((Instant::now(), contents, current_hash));
                }
            }

            if let Some((seen_at, text, hash)) = pending.as_ref() {
                if seen_at.elapsed() >= WATCH_DEBOUNCE {
                    if let Ok(mut registry) = watch_registry.lock() {
                        if let Some(watch) = registry.get_mut(&path) {
                            // The loop already hashed these bytes to notice the
                            // change; that hash IS the revision. Nothing to count.
                            watch.revision = hash.clone();
                            let event_payload = DocumentChangedEventPayload {
                                text: text.clone(),
                                path: Some(path.clone()),
                                revision: Some(watch.revision.clone()),
                            };
                            if let Err(err) =
                                emit_document_changed_event(&app_handle, &event_payload)
                            {
                                eprintln!("watch loop failed to emit event for {path}: {err}");
                            }
                        }
                    }
                    previous_hash = Some(hash.clone());
                    pending = None;
                }
            }

            std::thread::sleep(WATCH_POLL_INTERVAL);
        }
    });

    WatchController { stop_tx }
}

fn hash_file_contents(path: &str) -> Result<String> {
    let bytes =
        fs::read(path).with_context(|| format!("failed to read watched file {}", path))?;
    Ok(content_hash(&bytes))
}

/// V-13: the content hash, identical to `gx-core`'s `Hashing.ofBytes`.
///
/// Over the BYTES, and that is the whole point. The previous implementation
/// hashed a `String` from `read_to_string`, which is a lossy UTF-8 decode: a
/// file with bytes that do not decode had them silently replaced before
/// hashing, so two different files could share a hash and a file could change
/// without its hash moving. It was also `DefaultHasher` — SipHash, 64 bits,
/// and documented as not stable across Rust releases, which makes it unfit for
/// a value two programs compare.
fn content_hash(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(bytes);
    let mut hex = String::with_capacity(digest.len() * 2);
    for byte in digest {
        hex.push_str(&format!("{byte:02x}"));
    }
    hex
}

/// The exact string injected into the page. Separated from the emit so V-11 can
/// assert on the artifact the untrusted principal actually receives, rather than
/// on the struct we believe produces it.
fn document_changed_script(payload: &DocumentChangedEventPayload) -> Result<String> {
    let payload_json =
        serde_json::to_string(payload).context("failed to serialize event payload")?;
    Ok(format!(
        "window.dispatchEvent(new CustomEvent('ge:document.changed', {{ detail: {payload} }}));\
         window.dispatchEvent(new CustomEvent('document.changed', {{ detail: {payload} }}));",
        payload = payload_json
    ))
}

fn emit_document_changed_event(
    app_handle: &tauri::AppHandle,
    payload: &DocumentChangedEventPayload,
) -> Result<()> {
    let script = document_changed_script(payload)?;

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

// ------------------------------------------------------------------ library
//
// D7.3: the store IS the live state. `gx` writes records into
// `~/.graph-explorer/library/diagrams/<name>.json`, and the webview reads and
// writes that same directory through the commands below — so a headless
// `gx import` reaches a running window with no message sent, which is the
// whole point of the decision.
//
// The shell moves RAW JSON STRINGS and never parses a `Diagram` (D2.5:
// privilege, not intelligence). The schema is gx-core's, defined once in Scala
// and linked into both `gx` and the page. A Rust struct mirroring it would be
// a third implementation of one contract, free to drift — which is precisely
// how V-13's content hash diverged.

fn library_dir_path() -> Result<PathBuf> {
    Ok(graph_explorer_dir()?.join("library").join("diagrams"))
}

/// Resolve a library file name against the library directory, checking only
/// that it cannot address anything outside it.
///
/// The id -> file-name mapping is NOT known here. It is `DiagramFileName` in
/// gx-core's shared Scala, called by `gx` and by the page, so there is exactly
/// one rule. The shell asks a different and independently safe question: can
/// this name escape the directory?
///
/// Since no accepted name may contain a separator, joining cannot escape — so
/// this is sufficient without canonicalizing a file that may not exist yet.
/// `:` is rejected for Windows, where `join("C:x")` would replace the whole
/// path rather than extend it, and a leading `.` is rejected so a name can
/// never collide with the `.<name>.tmp` file an atomic write uses.
fn library_entry_path(dir: &Path, name: &str) -> std::result::Result<PathBuf, IpcError> {
    let refuse = |why: &str| {
        Err(IpcError::new(
            "INVALID_LIBRARY_NAME",
            format!("{why}: {name:?}"),
        ))
    };
    if name.is_empty() {
        return refuse("a library entry needs a name");
    }
    if name.len() > 200 {
        return refuse("library entry name is too long");
    }
    if name.starts_with('.') {
        return refuse("a library entry name may not start with a dot");
    }
    if name.contains('/') || name.contains('\\') || name.contains(':') || name.contains('\0') {
        return refuse("a library entry name may not contain a path separator");
    }
    Ok(dir.join(name))
}

/// One record as the page receives it.
///
/// A record that cannot be read is REPORTED rather than dropped: silently
/// omitting it would show the user a library that is missing a diagram with no
/// indication why, and `LibraryStore.unreadable()` makes the same choice on the
/// JVM side.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LibraryEntry {
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    json: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

fn read_library_entries(dir: &Path) -> Vec<LibraryEntry> {
    let Ok(listing) = fs::read_dir(dir) else {
        // No directory yet is an empty library, not a failure: nothing has
        // written a record. `gx` creates it on first import.
        return Vec::new();
    };

    let mut entries: Vec<LibraryEntry> = listing
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            entry.path().extension().and_then(|e| e.to_str()) == Some("json")
                && !entry.file_name().to_string_lossy().starts_with('.')
        })
        .map(|entry| {
            let name = entry.file_name().to_string_lossy().to_string();
            match fs::read_to_string(entry.path()) {
                Ok(json) => LibraryEntry {
                    name,
                    json: Some(json),
                    error: None,
                },
                Err(err) => LibraryEntry {
                    name,
                    json: None,
                    error: Some(err.to_string()),
                },
            }
        })
        .collect();

    // Sorted so the page gets a stable order across calls; `read_dir` yields
    // whatever the filesystem does, which differs between platforms and would
    // reshuffle the library on every refresh.
    entries.sort_by(|a, b| a.name.cmp(&b.name));
    entries
}

#[tauri::command(rename_all = "camelCase")]
fn library_list(state: tauri::State<'_, IpcState>) -> std::result::Result<Vec<LibraryEntry>, IpcError> {
    let dir = library_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    let entries = read_library_entries(&dir);
    state.audit_logger.log_event(
        "library.list",
        Some(&dir.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(entries)
}

#[tauri::command(rename_all = "camelCase")]
fn library_read(
    state: tauri::State<'_, IpcState>,
    name: String,
) -> std::result::Result<String, IpcError> {
    let dir = library_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    let path = library_entry_path(&dir, &name)?;
    let json = fs::read_to_string(&path)
        .map_err(|err| IpcError::new("LIBRARY_READ_FAILED", format!("{name}: {err}")))?;
    state.audit_logger.log_event(
        "library.read",
        Some(&path.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(json)
}

#[tauri::command(rename_all = "camelCase")]
fn library_write(
    state: tauri::State<'_, IpcState>,
    name: String,
    json: String,
) -> std::result::Result<(), IpcError> {
    let dir = library_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    let path = library_entry_path(&dir, &name)?;

    fs::create_dir_all(&dir)
        .map_err(|err| IpcError::new("LIBRARY_WRITE_FAILED", format!("{name}: {err}")))?;

    let existed = path.exists();

    // The same atomic write documents use, so V-03 (a write keeps the file's
    // permission bits) holds for library records too.
    write_file_atomic(&path.to_string_lossy(), &json)
        .map_err(|err| IpcError::new("LIBRARY_WRITE_FAILED", format!("{name}: {err}")))?;

    // A NEW record is owner-only, matching what `gx` produces.
    //
    // Not a contradiction of V-03, which is about not changing bits somebody
    // chose: nobody chose these, the file did not exist. What it fixes is one
    // library with two creators disagreeing — `gx` lands new records at 0600
    // (a JDK temp file's default), while the umask default here made them
    // world-readable. A user's diagrams should not become readable to other
    // local accounts depending on which process happened to create them.
    #[cfg(unix)]
    if !existed {
        if let Err(err) = set_owner_only_permissions(&path) {
            eprintln!("could not restrict permissions on {}: {err}", path.display());
        }
    }
    #[cfg(not(unix))]
    let _ = existed;

    // Remember our own bytes so the watcher below does not tell the page about
    // a change the page just made. Without this every UI edit round-trips back
    // as an external change and fights whatever the user typed next.
    if let Ok(mut hashes) = state.recent_write_hashes.lock() {
        hashes.insert(path.to_string_lossy().to_string(), content_hash(json.as_bytes()));
    }

    state.audit_logger.log_event(
        "library.write",
        Some(&path.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(())
}

#[tauri::command(rename_all = "camelCase")]
fn library_delete(
    state: tauri::State<'_, IpcState>,
    name: String,
) -> std::result::Result<bool, IpcError> {
    let dir = library_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    let path = library_entry_path(&dir, &name)?;
    let removed = match fs::remove_file(&path) {
        Ok(()) => true,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => false,
        Err(err) => {
            return Err(IpcError::new(
                "LIBRARY_DELETE_FAILED",
                format!("{name}: {err}"),
            ))
        }
    };
    // So the watcher does not report our own delete back to us as news.
    if removed {
        if let Ok(mut hashes) = state.recent_write_hashes.lock() {
            hashes.insert(path.to_string_lossy().to_string(), LIBRARY_DELETED.to_string());
        }
    }
    state.audit_logger.log_event(
        "library.delete",
        Some(&path.to_string_lossy()),
        Some("ui"),
        if removed { "allowed" } else { "not-found" },
        None,
        None,
    );
    Ok(removed)
}

/// One record's identity for change detection: name, size, mtime.
type LibraryStamp = (String, u64, Option<std::time::SystemTime>);

/// A fingerprint of the whole library directory: name, size and mtime per
/// record.
///
/// Deliberately not the file contents — the library is polled forever, and
/// hashing every record on every tick would scale with library size for no
/// gain. A record whose bytes change without size or mtime changing is a
/// filesystem with second-granularity timestamps rewriting a file within one
/// second to the same length; the reconcile on the next real change covers it.
fn library_signature(dir: &Path) -> Vec<LibraryStamp> {
    let Ok(listing) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut signature: Vec<_> = listing
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.path().extension().and_then(|e| e.to_str()) == Some("json"))
        .map(|entry| {
            let metadata = entry.metadata().ok();
            (
                entry.file_name().to_string_lossy().to_string(),
                metadata.as_ref().map(|m| m.len()).unwrap_or(0),
                metadata.and_then(|m| m.modified().ok()),
            )
        })
        .collect();
    signature.sort();
    signature
}

const LIBRARY_POLL_INTERVAL: Duration = Duration::from_millis(400);

/// Tell the page the library changed underneath it.
///
/// Carries no records. The page re-lists, which keeps this event honest about
/// what it knows: the directory moved, and the page owns the schema anyway.
fn library_changed_script() -> String {
    "window.dispatchEvent(new CustomEvent('ge:library.changed'));".to_string()
}

fn emit_library_changed_event(app_handle: &tauri::AppHandle) {
    let script = library_changed_script();
    for webview in app_handle.webview_windows().values() {
        if let Err(err) = webview.eval(&script) {
            eprintln!("failed to announce a library change: {err}");
        }
    }
}

/// Poll the library directory and announce changes the page did not cause.
///
/// Polling rather than `notify` for the same reason the document watcher does:
/// one mechanism, already proven on all three platforms, and a library is a
/// handful of small files.
/// Recorded instead of a hash when WE removed a record, so the watcher can
/// tell our own delete from `gx` deleting something behind our back. A hash is
/// hex, so this cannot collide with one.
const LIBRARY_DELETED: &str = "<deleted>";

/// Which records differ between two signatures — added, removed or edited.
///
/// Only these are worth asking about. The first version of this compared the
/// WHOLE signature against the recent-write map, so a library with two
/// diagrams could never suppress a self-write: the record we did not touch had
/// no recorded hash and dragged the answer to "not ours".
fn changed_records(previous: &[LibraryStamp], current: &[LibraryStamp]) -> Vec<String> {
    let mut names: Vec<String> = Vec::new();
    for (name, size, modified) in current {
        match previous.iter().find(|(n, _, _)| n == name) {
            Some((_, prev_size, prev_modified))
                if prev_size == size && prev_modified == modified => {}
            _ => names.push(name.clone()),
        }
    }
    for (name, _, _) in previous {
        if !current.iter().any(|(n, _, _)| n == name) {
            names.push(name.clone());
        }
    }
    names.sort();
    names.dedup();
    names
}

/// Is this change news to the page, or did the page cause it?
///
/// Separated from the loop deliberately. V-12 sat unasserted for a release
/// because its logic took an `AppHandle`, which no unit test can build; a
/// decision function takes a directory and a map, and can be run anywhere.
///
/// Consumes the matching entries: a recorded write explains exactly one
/// observation, and leaving it in the map would suppress a later genuine
/// change to the same record.
fn library_change_is_news(
    dir: &Path,
    changed: &[String],
    recent_write_hashes: &RecentWriteHashes,
) -> bool {
    if changed.is_empty() {
        return false;
    }
    let Ok(mut hashes) = recent_write_hashes.lock() else {
        // A poisoned lock means we cannot prove the change was ours. Telling
        // the page about a change it already knows costs a redundant re-read;
        // staying silent about one it does not know loses the update.
        return true;
    };

    let mut news = false;
    for name in changed {
        let path = dir.join(name);
        let key = path.to_string_lossy().to_string();
        let recorded = hashes.get(&key).cloned();
        let ours = match (recorded.as_deref(), fs::read(&path).ok()) {
            (Some(LIBRARY_DELETED), None) => true,
            (Some(known), Some(bytes)) => known == content_hash(&bytes),
            _ => false,
        };
        if ours {
            hashes.remove(&key);
        } else {
            news = true;
        }
    }
    news
}

/// Poll the library directory and announce changes the page did not cause.
///
/// Polling rather than `notify` for the same reason the document watcher does:
/// one mechanism, already proven on all three platforms, and a library is a
/// handful of small files.
fn spawn_library_watcher(app_handle: tauri::AppHandle, recent_write_hashes: RecentWriteHashes) {
    std::thread::spawn(move || {
        let Ok(dir) = library_dir_path() else {
            eprintln!("library watcher: could not locate the library directory");
            return;
        };
        let mut previous = library_signature(&dir);

        loop {
            std::thread::sleep(LIBRARY_POLL_INTERVAL);
            let current = library_signature(&dir);
            let changed = changed_records(&previous, &current);
            previous = current;

            if library_change_is_news(&dir, &changed, &recent_write_hashes) {
                emit_library_changed_event(&app_handle);
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An open must not be answered by the shell alone.
    ///
    /// Dispatching an event proves the shell spoke; it does not prove a viewer
    /// mounted, that it mounted the RIGHT one, or that anything reached the
    /// screen. These pin the wait mechanism — the live round trip through a
    /// real webview is covered by
    /// scripts/local-capabilities-open-handshake-smoke.sh, which is where a
    /// page actually exists.
    #[test]
    fn an_open_waits_for_the_page_and_gives_up_rather_than_hanging() {
        // A desktop whose window never comes up. `gx open` must return: a CLI
        // with no timeout is a CLI that has to be killed.
        let never_ready: PageReady = Arc::new((Mutex::new(false), std::sync::Condvar::new()));
        let (lock, condvar) = &*never_ready;
        let ready = lock.lock().unwrap();
        // Bound rather than `_`: `_` would drop the re-acquired guard on the
        // spot, which the lint rejects precisely because it is almost never
        // what the writer meant.
        let (_guard, timeout) = condvar
            .wait_timeout_while(ready, Duration::from_millis(50), |ready| !*ready)
            .unwrap();
        assert!(timeout.timed_out(), "a page that never reports ready must time out");
    }

    #[test]
    fn a_page_that_reports_ready_releases_every_waiting_open() {
        // notify_all, not notify_one: several opens can be parked during a slow
        // start, and waking one would leave the rest to time out for no reason.
        let page_ready: PageReady = Arc::new((Mutex::new(false), std::sync::Condvar::new()));

        let waiters: Vec<_> = (0..3)
            .map(|_| {
                let page_ready = page_ready.clone();
                std::thread::spawn(move || wait_for_page(&page_ready).is_ok())
            })
            .collect();

        // Let them park before the announcement, so this exercises the wake
        // path rather than the already-ready shortcut.
        std::thread::sleep(Duration::from_millis(50));
        {
            let (lock, condvar) = &*page_ready;
            let mut ready = lock.lock().unwrap();
            *ready = true;
            condvar.notify_all();
        }

        for waiter in waiters {
            assert!(waiter.join().unwrap(), "a parked open was not released");
        }
    }

    #[test]
    fn an_open_that_arrives_after_ready_does_not_wait_at_all() {
        let page_ready: PageReady = Arc::new((Mutex::new(true), std::sync::Condvar::new()));
        let started = std::time::Instant::now();
        assert!(wait_for_page(&page_ready).is_ok());
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "a ready page must not make an open wait"
        );
    }

    /// GX_HOME has to mean the same directory to `gx` and to this shell, and a
    /// RELATIVE value cannot: it resolves against the reading process's working
    /// directory, and the two do not share one — `gx` runs from the user's
    /// shell, a GUI-launched desktop from wherever the launcher put it.
    ///
    /// The Scala half refuses the same values in GxHome.resolve; these cases
    /// mirror GxHomeSpec deliberately, because a rule enforced on one side only
    /// is the divergence it was meant to prevent.
    #[test]
    fn a_relative_gx_home_is_refused_on_this_side_too() {
        for relative in ["scratch", "./scratch", "../scratch", "a/b"] {
            let result = graph_explorer_dir_from(
                Some(std::ffi::OsString::from(relative)),
                Some(PathBuf::from("/Users/someone")),
            );
            let err = result
                .expect_err(&format!("[{relative}] should have been refused"))
                .to_string();
            assert!(err.contains("absolute"), "[{relative}] reason should name the rule: {err}");
            assert!(err.contains(relative), "[{relative}] reason should quote the value: {err}");
        }
    }

    #[test]
    fn an_absolute_gx_home_is_normalised_the_way_java_normalises() {
        // `/tmp/../tmp/scratch` and `/tmp/scratch` are one directory, and the
        // two halves compare these paths.
        let resolved = graph_explorer_dir_from(
            Some(std::ffi::OsString::from("/tmp/../tmp/scratch")),
            Some(PathBuf::from("/Users/someone")),
        )
        .unwrap();
        assert_eq!(resolved, PathBuf::from("/tmp/scratch"));
    }

    #[test]
    fn a_blank_gx_home_reads_as_unset_including_whitespace() {
        // `export GX_HOME="$X"` with X unset. Scala trims before testing for
        // empty; this used to test only for exactly-empty, so a single space
        // put the desktop's library at a directory named " " while gx used the
        // default home.
        for blank in ["", "   ", "\t"] {
            let resolved = graph_explorer_dir_from(
                Some(std::ffi::OsString::from(blank)),
                Some(PathBuf::from("/Users/someone")),
            )
            .unwrap();
            assert_eq!(
                resolved,
                PathBuf::from("/Users/someone/.graph-explorer"),
                "blank value [{blank}] should read as unset"
            );
        }
    }

    /// Registration is idempotent; DISPLAY is not.
    ///
    /// `add_watch` emits the initial document event only when it creates the
    /// watch, and returns the existing descriptor otherwise. That early return
    /// is why a second `gx open` on a path already being watched used to raise
    /// the window while leaving the previous document on screen: nothing was
    /// re-delivered, because nothing was registered.
    ///
    /// This pins the decision itself. The `show` branch now treats a
    /// non-created outcome as "activate anyway" (§4.2), and the end-to-end
    /// reopen is covered by the disk-to-UI smoke.
    #[test]
    fn a_path_already_watched_is_not_a_new_watch() {
        let registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
        let path = "/tmp/already-open.dot";

        assert!(
            existing_watch(&registry, path).unwrap().is_none(),
            "an unregistered path must look unregistered"
        );

        registry.lock().unwrap().insert(
            path.to_string(),
            WatchDescriptor {
                path: path.to_string(),
                format: "dot".to_string(),
                revision: "c0ffee".to_string(),
            },
        );

        let found = existing_watch(&registry, path)
            .unwrap()
            .expect("a registered path must be found, or add_watch would install a second watcher");
        assert_eq!(found.revision, "c0ffee", "the descriptor in force is the one returned");

        // The distinction the show branch depends on: this path is NOT created,
        // so activation has to be performed explicitly rather than relied upon
        // as a side effect of registering.
        assert!(
            existing_watch(&registry, "/tmp/some-other.dot")
                .unwrap()
                .is_none(),
            "a different path must not collide with an existing watch"
        );
    }

    #[test]
    fn a_failed_open_removes_only_the_watch_it_created() {
        let dir = temp_dir_for("failed-open-watch");
        let file = dir.join("diagram.dot");
        fs::write(&file, "digraph G { a }").expect("seed file");
        let path = normalize_path(&file.to_string_lossy()).expect("normal path");
        let descriptor = WatchDescriptor {
            path: path.clone(),
            format: "dot".to_string(),
            revision: content_hash(b"digraph G { a }"),
        };
        let registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
        let controllers: WatchControllers = Arc::new(Mutex::new(HashMap::new()));
        let audit = AuditLogger {
            file_path: dir.join("audit.jsonl"),
            write_lock: Arc::new(Mutex::new(())),
        };

        registry.lock().unwrap().insert(path.clone(), descriptor.clone());
        rollback_failed_open(
            &WatchOutcome {
                watch: descriptor.clone(),
                created: true,
            },
            &registry,
            &controllers,
            &audit,
        )
        .expect("rollback");
        assert!(registry.lock().unwrap().is_empty(), "new failed-open watch leaked");

        registry.lock().unwrap().insert(path.clone(), descriptor.clone());
        rollback_failed_open(
            &WatchOutcome {
                watch: descriptor,
                created: false,
            },
            &registry,
            &controllers,
            &audit,
        )
        .expect("shared watch remains");
        assert!(registry.lock().unwrap().contains_key(&path), "shared watch was removed");

        let _ = fs::remove_dir_all(&dir);
    }

    /// The paths that broke v1, now crossing the boundary they actually cross.
    ///
    /// These replace eleven percent-decoding tests. The decoder they guarded is
    /// deleted — there are no URLs on a socket — but the property is the same
    /// one and still worth pinning: a path arrives byte-identical to the way it
    /// was sent, whatever is in it. What changed is that JSON does the escaping,
    /// and JSON is not a thing this codebase implements.
    const AWKWARD_PATHS: [&str; 6] = [
        "/tmp/diagram.dot",
        "/tmp/with space.dot",
        "/Users/jp/My Projects/a&b.dot",
        r"\\?\C:\Users\runneradmin\AppData\Local\Temp\gx.dot",
        "/tmp/ünïcode-Ø.dot",
        "/tmp/quote\"and\\backslash.dot",
    ];

    #[test]
    fn a_path_survives_the_frame_intact() {
        for path in AWKWARD_PATHS {
            let frame = serde_json::json!({
                "id": 1, "method": "get-document", "params": { "path": path }
            })
            .to_string();

            // A frame is a LINE, so an embedded newline would desynchronize the
            // stream. JSON escapes it; this asserts that rather than assuming it.
            assert!(!frame.contains('\n'), "frame must be a single line: {frame}");

            let request: RpcRequest = serde_json::from_str(&frame).expect("parseable frame");
            let params: DocumentRefRequest =
                serde_json::from_value(request.params).expect("parseable params");
            assert_eq!(params.path, path);
        }
    }

    #[test]
    fn a_response_frame_is_one_line_and_reports_its_id() {
        let response = RpcResponse::success(
            Some(serde_json::json!(7)),
            serde_json::json!({ "text": "digraph {\n  a -> b\n}" }),
        );
        let line = serde_json::to_string(&response).expect("serializable");
        assert!(!line.contains('\n'), "a response must be one line: {line}");
        assert!(line.contains("\"id\":7"), "the id must come back: {line}");
        assert!(line.contains("\"ok\":true"));
    }

    #[test]
    fn a_conflict_reports_both_revisions() {
        let current = content_hash(b"what is there now");
        let attempted = content_hash(b"what I based my edit on");
        let response =
            RpcResponse::conflict(Some(serde_json::json!(1)), current.clone(), attempted.clone());
        let value = serde_json::to_value(&response).expect("serializable");
        assert_eq!(value["ok"], serde_json::json!(false));
        assert_eq!(value["error"]["code"], serde_json::json!("DOCUMENT_CONFLICT"));
        // Both are hex hashes on the wire now (D1), not numbers — a client can
        // hash the file itself and compare, without asking anyone.
        assert_eq!(value["error"]["currentRevision"], serde_json::json!(current));
        assert_eq!(value["error"]["attemptedBaseRevision"], serde_json::json!(attempted));
    }

    /// A malformed frame must not kill the connection or the desktop: the
    /// client gets an error and the stream stays usable.
    #[test]
    fn a_malformed_frame_becomes_an_error_response() {
        let response = RpcResponse::failure(None, "INVALID_REQUEST", "malformed frame");
        let value = serde_json::to_value(&response).expect("serializable");
        assert_eq!(value["ok"], serde_json::json!(false));
        assert_eq!(value["error"]["code"], serde_json::json!("INVALID_REQUEST"));
        // No id came in, so none goes out — rather than inventing one.
        assert!(value.get("id").is_none());
    }

    /// The runtime file is what every client reads first. If a credential ever
    /// reappears in it, this fails.
    /// A context with no window yet — the state the process is in from the
    /// moment it starts until tauri's setup runs. Constructible at all only
    /// because the handle became a OnceLock: it used to be an AppHandle, which
    /// a unit test cannot make, so this state could not be tested before.
    fn starting_context(dir: &Path) -> ConnectionContext {
        ConnectionContext {
            access_policy: AccessPolicy {
                allowed_roots: Vec::new(),
                denied_roots: Vec::new(),
            },
            pending_sessions: Arc::new(Mutex::new(HashMap::new())),
            pending_opens: Arc::new(Mutex::new(HashMap::new())),
            // Not ready: this context models a desktop that is still starting,
            // which is exactly the state an open has to wait out.
            page_ready: Arc::new((Mutex::new(false), std::sync::Condvar::new())),
            request_limits: RequestLimits {
                max_body_bytes: 1024,
                rate_limit_max_requests: 10,
                rate_limit_window: Duration::from_secs(1),
            },
            audit_logger: AuditLogger {
                file_path: dir.join("audit.jsonl"),
                write_lock: Arc::new(Mutex::new(())),
            },
            rate_limiter: RequestRateLimiter::new(10, Duration::from_secs(1)),
            watch_registry: Arc::new(Mutex::new(HashMap::new())),
            watch_controllers: Arc::new(Mutex::new(HashMap::new())),
            recent_write_hashes: Arc::new(Mutex::new(HashMap::new())),
            app_handle: Arc::new(OnceLock::new()),
            unsaved: Arc::new(AtomicBool::new(false)),
        }
    }

    /// `$GX_HOME` has to mean the same directory here and in `gx`, or it is
    /// worse than not existing: a library in one place and the runtime file
    /// that names its socket in another. The Scala half of this rule lives in
    /// gx-core's GxHome, tested there — these are the same cases.
    #[test]
    fn gx_home_replaces_the_data_directory() {
        let dir = graph_explorer_dir_from(
            Some(std::ffi::OsString::from("/tmp/scratch")),
            Some(PathBuf::from("/Users/someone")),
        )
        .expect("an explicit GX_HOME needs no home directory");
        // The variable names the data directory itself. Nesting
        // `.graph-explorer` inside it would put a hidden directory in a path
        // the caller chose explicitly.
        assert_eq!(dir, PathBuf::from("/tmp/scratch"));
    }

    #[test]
    fn without_gx_home_it_is_the_dot_directory_under_home() {
        let dir = graph_explorer_dir_from(None, Some(PathBuf::from("/Users/someone")))
            .expect("a home directory is enough");
        assert_eq!(dir, PathBuf::from("/Users/someone/.graph-explorer"));
    }

    #[test]
    fn a_blank_gx_home_means_unset_not_the_filesystem_root() {
        // `export GX_HOME="$SOMETHING"` with SOMETHING unset is how a shell
        // script passes through an absent value. Taken literally it would put
        // the library at `/library`.
        let dir = graph_explorer_dir_from(
            Some(std::ffi::OsString::from("")),
            Some(PathBuf::from("/Users/someone")),
        )
        .expect("a blank value falls back to the home directory");
        assert_eq!(dir, PathBuf::from("/Users/someone/.graph-explorer"));
    }

    #[test]
    fn an_explicit_gx_home_works_with_no_home_directory_at_all() {
        // The point of the fallback order: a process that cannot locate a home
        // is still usable if it was told where to look.
        let dir = graph_explorer_dir_from(Some(std::ffi::OsString::from("/tmp/scratch")), None)
            .expect("GX_HOME alone is sufficient");
        assert_eq!(dir, PathBuf::from("/tmp/scratch"));
        assert!(graph_explorer_dir_from(None, None).is_err(), "neither one is an error");
    }

    #[test]
    fn a_starting_desktop_answers_status_rather_than_refusing_it() {
        // The point of binding the socket before the webview. `status` is the
        // question a client asks to tell "starting" from "not there", so it is
        // the one method that must work without a window.
        let dir = std::env::temp_dir();
        let body = status_body(&starting_context(&dir));
        assert_eq!(body.state, "starting");
        assert!(!body.running, "no window yet, so nothing can be shown");
        assert_eq!(body.pid, std::process::id(), "it is still a real process");
    }

    #[test]
    fn anything_needing_a_window_refuses_with_starting_not_a_hang() {
        // The alternative design — bind early but start accepting late — would
        // have left the client BLOCKED on a read with no timeout for the whole
        // webview startup. A typed refusal is a fast, true answer instead.
        let dir = std::env::temp_dir();
        let err = starting_context(&dir)
            .app()
            .expect_err("there is no window during startup");
        assert_eq!(err.0, "STARTING");
        assert!(err.1.contains("starting"), "{}", err.1);
    }

    #[test]
    fn the_runtime_file_carries_no_credential() {
        let control = ControlFile {
            pid: 42,
            socket: "/home/u/.graph-explorer/runtime/control.sock".to_string(),
            version: "0.1.0".to_string(),
        };
        let json = serde_json::to_value(control).expect("serializable");
        let keys: Vec<&str> = json
            .as_object()
            .expect("an object")
            .keys()
            .map(|k| k.as_str())
            .collect();
        assert_eq!(keys, vec!["pid", "socket", "version"]);
    }

    #[test]
    fn an_over_long_socket_path_is_refused_with_an_explanation() {
        let long = PathBuf::from(format!("/{}/control.sock", "x".repeat(120)));
        let err = check_socket_path_length(&long).expect_err("should be refused");
        assert!(
            err.to_string().contains("unix socket address"),
            "the error should say WHY: {err}"
        );
        assert!(check_socket_path_length(Path::new("/tmp/a/control.sock")).is_ok());
    }

    fn sample_event_payload() -> DocumentChangedEventPayload {
        DocumentChangedEventPayload {
            text: "digraph { a -> b }".to_string(),
            path: Some("/tmp/a.dot".to_string()),
            revision: Some(content_hash(b"whatever")),
        }
    }

    /// V-11, asserted on the artifact rather than the intent: this string is
    /// literally what gets evaluated inside the untrusted webview.
    #[test]
    fn the_event_script_carries_no_credential() {
        let script = document_changed_script(&sample_event_payload()).expect("serializable");
        assert!(!script.contains("token"), "event script leaked a token field: {script}");
        assert!(!script.contains("port"), "event script leaked a port field: {script}");
        assert!(script.contains("digraph"), "event script lost its payload: {script}");
    }

    /// V-11, on the payload's own shape: the page is handed exactly a document
    /// reference and nothing else.
    #[test]
    fn the_event_payload_has_exactly_the_document_fields() {
        let json = serde_json::to_value(sample_event_payload()).expect("serializable");
        // `serde_json::Value` keys a map by BTreeMap, so this is already sorted
        // — the invariant is the SET of fields, not their order on the wire.
        let keys: Vec<&str> = json
            .as_object()
            .expect("an object")
            .keys()
            .map(|key| key.as_str())
            .collect();
        assert_eq!(keys, vec!["path", "revision", "text"]);
    }

    fn temp_dir_for(test: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("gx-desktop-test-{test}-{}", std::process::id()));
        fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    /// V-12. A save whose window is gone still reached the disk, so it must
    /// report success. Before this, `emit` failing turned an applied write into
    /// an error, and the caller saw its own committed edit as lost.
    #[test]
    fn a_save_survives_a_failed_ui_notification() {
        let dir = temp_dir_for("v12");
        let file = dir.join("diagram.dot");
        fs::write(&file, "digraph { a }").expect("seed file");
        let path = file.to_string_lossy().to_string();
        let normalized = normalize_path(&path).expect("normalizable");

        let registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
        registry.lock().expect("lock").insert(
            normalized.clone(),
            WatchDescriptor {
                path: normalized.clone(),
                format: "dot".to_string(),
                revision: content_hash(b"digraph { a }"),
            },
        );
        let recent_writes: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));
        let audit = AuditLogger {
            file_path: dir.join("audit.jsonl"),
            write_lock: Arc::new(Mutex::new(())),
        };

        let snapshot = put_document_snapshot(
            &registry,
            &recent_writes,
            &audit,
            // Exactly what `emit_document_changed_event` returns when the last
            // window has closed.
            &|_| Err(anyhow::anyhow!("no webview windows are available")),
            PutDocumentRequest {
                path: path.clone(),
                text: "digraph { a -> b }".to_string(),
                // If-Match against what is actually on disk.
                base_revision: content_hash(b"digraph { a }"),
                source: Some("ui".to_string()),
            },
        )
        .unwrap_or_else(|_| panic!("a written file must report success"));

        // The new revision is the new content, not "the old number plus one".
        assert_eq!(snapshot.revision, content_hash(b"digraph { a -> b }"));
        assert_eq!(
            fs::read_to_string(&file).expect("readable"),
            "digraph { a -> b }"
        );

        // The failure is not swallowed — it is recorded where an operator can
        // find it.
        let log = fs::read_to_string(dir.join("audit.jsonl")).expect("audit log");
        assert!(
            log.contains("UI notification failed"),
            "the notification failure should be audited: {log}"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    fn mode_of(path: &Path) -> u32 {
        use std::os::unix::fs::PermissionsExt;
        fs::metadata(path).expect("metadata").permissions().mode() & 0o7777
    }

    /// V-03. v1 chmod'ed every file it wrote to 0600, so a single save turned a
    /// group-readable diagram in a shared checkout into an owner-only one — a
    /// change nobody asked for, and one you would only notice via someone
    /// else's permission error.
    #[cfg(unix)]
    #[test]
    fn a_write_preserves_the_targets_permission_bits() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_dir_for("v03");
        let file = dir.join("shared.dot");
        fs::write(&file, "digraph { a }").expect("seed file");
        fs::set_permissions(&file, fs::Permissions::from_mode(0o644)).expect("chmod 0644");

        write_file_atomic(&file.to_string_lossy(), "digraph { a -> b }").expect("write");

        assert_eq!(mode_of(&file), 0o644, "the target's bits must survive the save");
        assert_eq!(
            fs::read_to_string(&file).expect("readable"),
            "digraph { a -> b }"
        );

        // A less common mode, so the test cannot pass by coincidentally matching
        // the umask default.
        fs::set_permissions(&file, fs::Permissions::from_mode(0o664)).expect("chmod 0664");
        write_file_atomic(&file.to_string_lossy(), "digraph { c }").expect("write");
        assert_eq!(mode_of(&file), 0o664);

        // setgid: the bit most likely to be deliberate on a shared checkout, and
        // the one a `& 0o777` mask would quietly drop.
        fs::set_permissions(&file, fs::Permissions::from_mode(0o2664)).expect("chmod 2664");
        if mode_of(&file) == 0o2664 {
            write_file_atomic(&file.to_string_lossy(), "digraph { d }").expect("write");
            assert_eq!(mode_of(&file), 0o2664, "setgid must survive the save");
        }

        let _ = fs::remove_dir_all(&dir);
    }

    /// The other half of V-03, and the one a naive fix gets wrong: a file that
    /// does not exist yet has no bits to preserve, so it must land on the
    /// user's umask default — NOT on the temp file's owner-only mode, which
    /// would be the same surprise wearing a different hat.
    #[cfg(unix)]
    #[test]
    fn a_new_file_lands_on_the_umask_default() {
        let dir = temp_dir_for("v03-new");

        // The reference is what a plain write produces in this very directory,
        // so the assertion holds under any umask the test happens to run with.
        let reference = dir.join("reference.dot");
        fs::write(&reference, "x").expect("reference file");

        let created = dir.join("created.dot");
        write_file_atomic(&created.to_string_lossy(), "digraph { a }").expect("write");

        assert_eq!(mode_of(&created), mode_of(&reference));
        assert_ne!(
            mode_of(&created),
            0o600,
            "a new file must not inherit the temp file's owner-only mode"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    /// A leftover temp from a crashed write must not decide the new file's
    /// mode: `fs::write` truncates rather than recreates, so a stale 0600 temp
    /// would have been silently reused as the umask probe.
    #[cfg(unix)]
    #[test]
    fn a_stale_temp_file_does_not_infect_the_new_mode() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_dir_for("v03-stale");
        let target = dir.join("a.dot");
        let stale = dir.join(".a.dot.tmp");
        fs::write(&stale, "leftover").expect("stale temp");
        fs::set_permissions(&stale, fs::Permissions::from_mode(0o600)).expect("chmod");

        let reference = dir.join("reference.dot");
        fs::write(&reference, "x").expect("reference file");

        write_file_atomic(&target.to_string_lossy(), "digraph { a }").expect("write");

        assert_eq!(mode_of(&target), mode_of(&reference));
        assert!(!stale.exists(), "the temp must not be left beside the target");

        let _ = fs::remove_dir_all(&dir);
    }

    // ----------------------------------------------------------- V-13
    //
    // The contract this codebase writes twice. Both suites read the SAME files,
    // so "we wrote it twice and both are self-consistent" cannot pass for
    // agreement — which is the failure mode §4 warns about.

    fn fixture(name: &str) -> serde_json::Value {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../local-protocol/fixtures")
            .join(name);
        let text = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("failed to read fixture {}: {e}", path.display()));
        serde_json::from_str(&text).expect("fixture is valid JSON")
    }

    #[test]
    fn a_close_is_refused_only_while_an_unanswered_edit_is_unsaved() {
        // The whole table, because each row is a different failure. Refusing
        // the wrong one traps the window open; allowing the wrong one throws
        // away an edit with no prompt.
        assert!(close_is_allowed(false, false), "nothing unsaved: close");
        assert!(!close_is_allowed(false, true), "an unsaved edit must be asked about");
        assert!(close_is_allowed(true, true), "the person already answered: close");
        assert!(close_is_allowed(true, false), "answered and nothing unsaved: close");
    }

    #[test]
    fn hash_text_is_the_same_digest_the_fixtures_pin() {
        // The page calls this instead of hashing for itself, so it has to
        // return exactly what `content_hash` returns — which the fixture above
        // pins against the JVM's `Hashing`. A separate path here would be a
        // second implementation wearing the first one's name.
        let doc = fixture("content-hashes.json");
        let cases = doc["contentHashes"].as_array().expect("an array");
        let mut checked = 0;

        for case in cases {
            if let Some(text) = case.get("text").and_then(|t| t.as_str()) {
                assert_eq!(
                    hash_text(text.to_string()),
                    case["sha256"].as_str().expect("sha256"),
                    "hash_text disagrees with the fixture for '{}'",
                    case["name"].as_str().unwrap_or("?")
                );
                checked += 1;
            }
        }

        assert!(checked >= 4, "the text cases should not have shrunk");
    }

    #[test]
    fn hash_text_hashes_the_bytes_it_is_given_and_applies_no_convention() {
        // The line ending is applied by the caller (shared Scala), so CRLF and
        // LF text MUST hash differently here. If this command normalised
        // anything, a CRLF origin would read as changed on every comparison —
        // the exact bug the CRLF rule exists to prevent.
        assert_ne!(hash_text("a\nb".to_string()), hash_text("a\r\nb".to_string()));
    }

    #[test]
    fn v13_content_hashes_match_the_shared_fixtures() {
        let doc = fixture("content-hashes.json");
        let cases = doc["contentHashes"].as_array().expect("an array");
        assert!(cases.len() >= 8, "the fixture set should not have shrunk");

        for case in cases {
            let name = case["name"].as_str().expect("a name");
            let bytes: Vec<u8> = match (case.get("text"), case.get("hexBytes")) {
                (Some(text), _) => text.as_str().expect("text").as_bytes().to_vec(),
                (_, Some(hex)) => {
                    let hex = hex.as_str().expect("hexBytes");
                    (0..hex.len())
                        .step_by(2)
                        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).expect("hex byte"))
                        .collect()
                }
                _ => panic!("fixture '{name}' has neither text nor hexBytes"),
            };
            assert_eq!(
                content_hash(&bytes),
                case["sha256"].as_str().expect("sha256"),
                "content hash disagrees with the fixture for '{name}'"
            );
        }
    }

    /// Build a case's tree, canonicalize its input, compare to the expectation.
    ///
    /// Everything is expressed relative to a RESOLVED root: on macOS `/tmp` is a
    /// symlink to `/private/tmp`, so an absolute expectation would be asserting
    /// that rather than the rule under test.
    #[test]
    fn v13_canonicalization_matches_the_shared_fixtures() {
        let doc = fixture("canonicalization.json");
        let cases = doc["canonicalization"].as_array().expect("an array");
        assert!(cases.len() >= 10, "the fixture set should not have shrunk");

        let case_insensitive = filesystem_is_case_insensitive();

        for (index, case) in cases.iter().enumerate() {
            let name = case["name"].as_str().expect("a name");
            let root = std::env::temp_dir().join(format!("gx-v13-{}-{index}", std::process::id()));
            let _ = fs::remove_dir_all(&root);
            fs::create_dir_all(&root).expect("root");
            let root = fs::canonicalize(&root).expect("resolvable root");

            let tree = &case["tree"];
            for dir in tree["dirs"].as_array().unwrap_or(&vec![]) {
                fs::create_dir_all(root.join(dir.as_str().expect("dir"))).expect("dir");
            }
            for file in tree["files"].as_array().unwrap_or(&vec![]) {
                let path = root.join(file.as_str().expect("file"));
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent).expect("parent");
                }
                fs::write(&path, "digraph G { a }").expect("file");
            }
            let mut skipped = false;
            for link in tree["symlinks"].as_array().unwrap_or(&vec![]) {
                let from = root.join(link["link"].as_str().expect("link"));
                if let Some(parent) = from.parent() {
                    fs::create_dir_all(parent).expect("parent");
                }
                if make_symlink(link["target"].as_str().expect("target"), &from).is_err() {
                    // Windows needs Developer Mode or elevation for symlinks.
                    // Skipping is honest; silently passing would not be.
                    eprintln!("v13: skipping '{name}' — cannot create symlinks here");
                    skipped = true;
                    break;
                }
            }
            if skipped {
                let _ = fs::remove_dir_all(&root);
                continue;
            }

            let expected_rel = match (case.get("expectCaseInsensitive"), case_insensitive) {
                (Some(alt), true) => alt.as_str().expect("expectCaseInsensitive"),
                _ => case["expect"].as_str().expect("expect"),
            };

            let input = root.join(case["input"].as_str().expect("input"));
            let actual = normalize_path(&input.to_string_lossy()).expect("canonicalizable");
            let expected = strip_verbatim_prefix(&root.join(expected_rel));

            assert_eq!(actual, expected, "canonicalization disagrees for '{name}'");
            let _ = fs::remove_dir_all(&root);
        }
    }

    /// Asked of the filesystem rather than of the OS name: a case-sensitive
    /// volume on macOS exists, and D2.1b's whole lesson is about measuring the
    /// thing rather than a proxy for it.
    fn filesystem_is_case_insensitive() -> bool {
        let dir = temp_dir_for("case-probe");
        let lower = dir.join("probe.dot");
        fs::write(&lower, "x").expect("probe");
        let answer = dir.join("PROBE.DOT").exists();
        let _ = fs::remove_dir_all(&dir);
        answer
    }

    #[cfg(unix)]
    fn make_symlink(target: &str, link: &Path) -> std::io::Result<()> {
        std::os::unix::fs::symlink(target, link)
    }

    #[cfg(windows)]
    fn make_symlink(target: &str, link: &Path) -> std::io::Result<()> {
        // The target may be a file or a directory and Windows needs to be told
        // which; try the directory form first, since every case here that links
        // to a file names it explicitly.
        let resolved = link.parent().unwrap_or(Path::new(".")).join(target);
        if resolved.is_dir() {
            std::os::windows::fs::symlink_dir(target, link)
        } else {
            std::os::windows::fs::symlink_file(target, link)
        }
    }

    /// The rule that had no test and the most consequence: an identity must not
    /// depend on whether the file happened to exist when it was first named.
    #[test]
    fn v13_a_path_keeps_its_identity_across_creation() {
        let dir = temp_dir_for("v13-identity");
        let real = dir.join("real");
        fs::create_dir_all(&real).expect("dir");
        let file = real.join("later.dot");

        let before = normalize_path(&file.to_string_lossy()).expect("before");
        fs::write(&file, "digraph G { a }").expect("create");
        let after = normalize_path(&file.to_string_lossy()).expect("after");

        assert_eq!(
            before, after,
            "a path must name the same document before and after it exists"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// The other half of V-12: a genuinely stale base is still a conflict, and
    /// still leaves the file untouched (V-01).
    #[test]
    fn a_stale_base_revision_is_still_rejected() {
        let dir = temp_dir_for("v12-conflict");
        let file = dir.join("diagram.dot");
        fs::write(&file, "digraph { a }").expect("seed file");
        let path = file.to_string_lossy().to_string();
        let normalized = normalize_path(&path).expect("normalizable");

        let registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
        registry.lock().expect("lock").insert(
            normalized.clone(),
            WatchDescriptor {
                path: normalized,
                format: "dot".to_string(),
                // DELIBERATELY stale, and nothing like the file's contents.
                // Under the old counter this value WAS the answer; under D1 it
                // is ignored, because the file is the authority.
                revision: content_hash(b"a stale registry entry"),
            },
        );
        let recent_writes: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));
        let audit = AuditLogger {
            file_path: dir.join("audit.jsonl"),
            write_lock: Arc::new(Mutex::new(())),
        };

        let result = put_document_snapshot(
            &registry,
            &recent_writes,
            &audit,
            &|_| Ok(()),
            PutDocumentRequest {
                path,
                text: "clobbered".to_string(),
                base_revision: content_hash(b"something else entirely"),
                source: Some("ui".to_string()),
            },
        );

        match result {
            Err(PutDocumentError::Conflict {
                current_revision,
                attempted_base_revision,
            }) => {
                // Reported from the FILE — the seeded content — NOT from the
                // stale registry entry above. That difference is the whole
                // point of D1: a process that wrote the file behind our back
                // is detected, where a counter could only report what it had
                // last been told.
                assert_eq!(current_revision, content_hash(b"digraph { a }"));
                assert_eq!(attempted_base_revision, content_hash(b"something else entirely"));
            }
            _ => panic!("a stale base revision must conflict"),
        }
        assert_eq!(
            fs::read_to_string(&file).expect("readable"),
            "digraph { a }"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    // ------------------------------------------------------- D7.3 library

    /// The shell does not know how an id becomes a file name — that rule is
    /// `DiagramFileName` in gx-core's shared Scala, called by both `gx` and the
    /// page. What it must know is that a name it is handed cannot address
    /// anything outside the library.
    #[test]
    fn a_library_name_cannot_escape_the_library() {
        let dir = PathBuf::from("/tmp/library");
        for hostile in [
            "../secrets.json",
            "../../etc/passwd",
            "sub/dir.json",
            "back\\slash.json",
            "C:evil.json",
            ".hidden.json",
            "",
        ] {
            assert!(
                library_entry_path(&dir, hostile).is_err(),
                "should have been refused: {hostile:?}"
            );
        }
    }

    /// `DiagramFileName` keeps letters rather than restricting to ASCII, so a
    /// diagram named in Japanese produces a name with Japanese in it. A shell
    /// that only accepted `[A-Za-z0-9_-]` would reject records the store side
    /// considers perfectly ordinary.
    #[test]
    fn a_library_name_keeps_the_letters_the_naming_rule_keeps() {
        let dir = PathBuf::from("/tmp/library");
        for ok in ["arch.json", "\u{8a2d}\u{8a08}.json", "caf\u{e9}.json", "a-b_c.json"] {
            assert!(
                library_entry_path(&dir, ok).is_ok(),
                "should have been accepted: {ok:?}"
            );
        }
    }

    /// A record that cannot be read is REPORTED. Dropping it would show a
    /// library silently missing a diagram, with nothing to explain the gap.
    #[test]
    fn an_unreadable_record_is_reported_rather_than_dropped() {
        let dir = temp_dir_for("library-unreadable");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join("good.json"), "{\"id\":1}").expect("good");
        // A directory named `*.json` cannot be read as a string, which is a
        // portable way to make one entry fail while the other succeeds.
        fs::create_dir_all(dir.join("broken.json")).expect("broken");

        let entries = read_library_entries(&dir);
        assert_eq!(entries.len(), 2, "{entries:?}");

        let broken = entries.iter().find(|e| e.name == "broken.json").expect("broken listed");
        assert!(broken.json.is_none());
        assert!(broken.error.is_some(), "the failure must be reported");

        let good = entries.iter().find(|e| e.name == "good.json").expect("good listed");
        assert_eq!(good.json.as_deref(), Some("{\"id\":1}"));
        assert!(good.error.is_none());

        let _ = fs::remove_dir_all(&dir);
    }

    /// `read_dir` yields filesystem order, which differs by platform. Sorting
    /// keeps the library from reshuffling itself on every refresh.
    #[test]
    fn the_listing_is_sorted_and_ignores_what_is_not_a_record() {
        let dir = temp_dir_for("library-sorted");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        for name in ["zeta.json", "alpha.json", "mid.json"] {
            fs::write(dir.join(name), "{}").expect("record");
        }
        fs::write(dir.join("notes.txt"), "not a record").expect("txt");
        fs::write(dir.join(".alpha.json.tmp"), "half-written").expect("tmp");

        let names: Vec<String> = read_library_entries(&dir).into_iter().map(|e| e.name).collect();
        assert_eq!(names, vec!["alpha.json", "mid.json", "zeta.json"]);
    }

    /// A missing directory is an EMPTY library, not an error: nothing has
    /// written a record yet, which is the state every new install is in.
    #[test]
    fn a_library_that_does_not_exist_yet_is_empty_not_broken() {
        let dir = temp_dir_for("library-absent").join("nope");
        let _ = fs::remove_dir_all(&dir);
        assert!(read_library_entries(&dir).is_empty());
        assert!(library_signature(&dir).is_empty());
    }

    #[test]
    fn the_signature_notices_a_new_record_and_an_edit() {
        let dir = temp_dir_for("library-signature");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let empty = library_signature(&dir);

        fs::write(dir.join("a.json"), "{}").expect("a");
        let one = library_signature(&dir);
        assert_ne!(empty, one, "a new record must change the signature");

        fs::write(dir.join("a.json"), "{\"changed\":true}").expect("edit");
        let edited = library_signature(&dir);
        assert_ne!(one, edited, "a size change must change the signature");

        let _ = fs::remove_dir_all(&dir);
    }

    /// The same shape as V-11's check on the document event: the announcement
    /// carries no data, so there is nothing in it to leak or to go stale. The
    /// page re-reads, and the page owns the schema.
    #[test]
    fn the_library_change_event_carries_no_records() {
        let script = library_changed_script();
        assert!(script.contains("ge:library.changed"), "{script}");
        assert!(!script.contains("detail"), "the event must carry no payload: {script}");
        assert!(!script.contains("json"), "{script}");
    }

    // ------------------------------------- D7.3 watcher: is it news?

    fn hashes_of(pairs: &[(&Path, &str)]) -> RecentWriteHashes {
        let map: HashMap<String, String> = pairs
            .iter()
            .map(|(p, h)| (p.to_string_lossy().to_string(), (*h).to_string()))
            .collect();
        Arc::new(Mutex::new(map))
    }

    #[test]
    fn changed_records_notices_an_add_an_edit_and_a_removal() {
        let t = std::time::SystemTime::UNIX_EPOCH;
        let before = vec![
            ("a.json".to_string(), 10, Some(t)),
            ("b.json".to_string(), 10, Some(t)),
        ];
        let after = vec![
            ("a.json".to_string(), 99, Some(t)), // edited
            ("c.json".to_string(), 10, Some(t)), // added
        ];                                        // b removed
        assert_eq!(
            changed_records(&before, &after),
            vec!["a.json".to_string(), "b.json".to_string(), "c.json".to_string()]
        );
    }

    #[test]
    fn an_unchanged_library_is_not_news() {
        let dir = temp_dir_for("news-none");
        assert!(!library_change_is_news(&dir, &[], &hashes_of(&[])));
    }

    /// The case the user hit: `gx import` writes a record while the app is
    /// running. Nothing recorded it, so the page has to be told.
    #[test]
    fn a_record_written_by_gx_is_news() {
        let dir = temp_dir_for("news-external");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join("imported.json"), "{\"id\":\"imported\"}").expect("write");

        assert!(library_change_is_news(
            &dir,
            &["imported.json".to_string()],
            &hashes_of(&[])
        ));
        let _ = fs::remove_dir_all(&dir);
    }

    /// The page's own save must not come back at it, or every edit would
    /// re-enter the UI and fight whatever was typed next.
    #[test]
    fn our_own_write_is_not_news() {
        let dir = temp_dir_for("news-ours");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let body = "{\"id\":\"mine\"}";
        let file = dir.join("mine.json");
        fs::write(&file, body).expect("write");

        let hashes = hashes_of(&[(&file, &content_hash(body.as_bytes()))]);
        assert!(!library_change_is_news(&dir, &["mine.json".to_string()], &hashes));
        let _ = fs::remove_dir_all(&dir);
    }

    /// A change we did not cause reaches the page even when it arrives
    /// alongside one we did.
    #[test]
    fn our_write_does_not_suppress_a_neighbours_change() {
        let dir = temp_dir_for("news-neighbour");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let mine = dir.join("mine.json");
        let theirs = dir.join("theirs.json");
        fs::write(&mine, "{\"a\":1}").expect("mine");
        fs::write(&theirs, "{\"b\":2}").expect("theirs");

        let hashes = hashes_of(&[(&mine, &content_hash(b"{\"a\":1}"))]);
        assert!(
            library_change_is_news(
                &dir,
                &["mine.json".to_string(), "theirs.json".to_string()],
                &hashes
            ),
            "a change we did not cause must still reach the page"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// A recorded write explains exactly ONE observation. If the entry stayed,
    /// the next genuine edit to that record would be silently swallowed.
    #[test]
    fn a_recorded_write_explains_one_change_only() {
        let dir = temp_dir_for("news-once");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let file = dir.join("mine.json");
        let body = "{\"v\":1}";
        fs::write(&file, body).expect("write");
        let hashes = hashes_of(&[(&file, &content_hash(body.as_bytes()))]);

        assert!(!library_change_is_news(&dir, &["mine.json".to_string()], &hashes));
        // Someone else edits the same record afterwards.
        fs::write(&file, "{\"v\":2}").expect("edit");
        assert!(
            library_change_is_news(&dir, &["mine.json".to_string()], &hashes),
            "the second change is news; the recorded write was already spent"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn our_own_delete_is_quiet_but_someone_elses_is_news() {
        let dir = temp_dir_for("news-delete");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let gone = dir.join("gone.json");

        let ours = hashes_of(&[(&gone, LIBRARY_DELETED)]);
        assert!(!library_change_is_news(&dir, &["gone.json".to_string()], &ours));

        assert!(
            library_change_is_news(&dir, &["gone.json".to_string()], &hashes_of(&[])),
            "a record removed by gx must reach the page"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// THE bug that extracting this function exposed, stated precisely.
    ///
    /// The first version compared the WHOLE signature against the recent-write
    /// map and demanded every record match. A library with a second, untouched
    /// diagram therefore had one record with no recorded hash, which dragged
    /// the answer to "not ours" — so the page's own save echoed straight back
    /// at it. The symptom is over-notification, not a missed change, and it
    /// only appears once you have more than one diagram.
    #[test]
    fn our_own_write_is_quiet_even_with_another_record_present() {
        let dir = temp_dir_for("news-ours-plus-one");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let mine = dir.join("mine.json");
        let body = "{\"a\":1}";
        fs::write(&mine, body).expect("mine");
        fs::write(dir.join("untouched.json"), "{\"b\":2}").expect("untouched");

        let hashes = hashes_of(&[(&mine, &content_hash(body.as_bytes()))]);
        assert!(
            !library_change_is_news(&dir, &["mine.json".to_string()], &hashes),
            "only the CHANGED record is consulted; a bystander must not make our own save look external"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// A record written by the REAL `gx`, carried through the shell's reader
    /// unchanged.
    ///
    /// The fixture was produced by running `gx import` and then
    /// `gx run demo hide` against a scratch library, so it is the actual byte
    /// shape the CLI emits — not a hand-written approximation of it. That
    /// matters most for what is ABSENT: upickle omits defaults, so `metadata`
    /// carries only `hiddenElements` and every other field is missing. A
    /// reader that required them would fail on real data while passing every
    /// synthetic test.
    #[test]
    fn a_real_gx_record_survives_the_shell_untouched() {
        let source = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../local-protocol/fixtures/library-record.json");
        let bytes = fs::read_to_string(&source).expect("fixture");

        let dir = temp_dir_for("library-fixture");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join("demo.json"), &bytes).expect("seed");

        let entries = read_library_entries(&dir);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "demo.json");
        assert!(entries[0].error.is_none());
        // Byte-for-byte: the shell is a courier, and a courier that reformats
        // JSON would be silently re-deriving a schema it does not own.
        assert_eq!(entries[0].json.as_deref(), Some(bytes.as_str()));

        let _ = fs::remove_dir_all(&dir);
    }

    /// One library, two creators, two permission policies — found by looking at
    /// `ls -l` after a real run. `gx` lands a new record at 0600 (a JDK temp
    /// file's default); the shell's umask default made its own 0644, so whether
    /// a diagram was readable by other local accounts depended on which process
    /// happened to create it.
    #[cfg(unix)]
    #[test]
    fn a_new_library_record_is_owner_only_like_the_ones_gx_writes() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_dir_for("library-perms");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        let path = dir.join("new.json");

        // What library_write does, in the order it does it.
        let existed = path.exists();
        write_file_atomic(&path.to_string_lossy(), "{}").expect("write");
        assert!(!existed);
        set_owner_only_permissions(&path).expect("restrict");

        let mode = fs::metadata(&path).expect("metadata").permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "got {mode:o}");

        // And V-03 still holds for one that already exists: bits somebody chose
        // are not ours to change.
        fs::set_permissions(&path, fs::Permissions::from_mode(0o640)).expect("chmod");
        write_file_atomic(&path.to_string_lossy(), "{\"v\":2}").expect("rewrite");
        let after = fs::metadata(&path).expect("metadata").permissions().mode() & 0o777;
        assert_eq!(after, 0o640, "an existing record keeps its bits; got {after:o}");

        let _ = fs::remove_dir_all(&dir);
    }

    /// D1's stated cost, asserted as intent rather than left to be discovered:
    /// an A -> B -> A edit returns to its ORIGINAL revision.
    ///
    /// Under a counter this was impossible; under content addressing it is
    /// unavoidable, and D1 argues it is correct rather than a defect — if the
    /// content I based my edit on is what is there now, my edit is safe. Pinning
    /// it means nobody later "fixes" it back into a counter.
    #[test]
    fn an_a_b_a_edit_returns_to_its_original_revision() {
        let dir = temp_dir_for("d1-aba");
        let file = dir.join("diagram.dot");
        fs::write(&file, "A").expect("seed");
        let path = file.to_string_lossy().to_string();
        let normalized = normalize_path(&path).expect("normalizable");

        let registry: WatchRegistry = Arc::new(Mutex::new(HashMap::new()));
        registry.lock().expect("lock").insert(
            normalized.clone(),
            WatchDescriptor {
                path: normalized,
                format: "dot".to_string(),
                revision: content_hash(b"A"),
            },
        );
        let recent_writes: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));
        let audit = AuditLogger {
            file_path: dir.join("audit.jsonl"),
            write_lock: Arc::new(Mutex::new(())),
        };

        let put = |text: &str, base: String| {
            put_document_snapshot(
                &registry,
                &recent_writes,
                &audit,
                &|_| Ok(()),
                PutDocumentRequest {
                    path: path.clone(),
                    text: text.to_string(),
                    base_revision: base,
                    source: Some("test".to_string()),
                },
            )
        };

        let revision_of = |r: std::result::Result<DocumentSnapshot, PutDocumentError>| match r {
            Ok(snapshot) => snapshot.revision,
            Err(_) => panic!("the write should have been accepted"),
        };

        let a = content_hash(b"A");
        let to_b = revision_of(put("B", a.clone()));
        assert_eq!(to_b, content_hash(b"B"));

        let back = revision_of(put("A", to_b));
        assert_eq!(back, a, "the same content must carry the same revision");

        // And the round trip leaves a base that still validates: an edit built
        // on the original content is accepted, which is the property D1 says
        // actually matters.
        assert!(put("C", a).is_ok(), "a base matching what is on disk must be accepted");

        let _ = fs::remove_dir_all(&dir);
    }
}
