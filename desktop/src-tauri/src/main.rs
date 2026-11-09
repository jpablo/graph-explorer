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
    pending_live_view_replies: PendingLiveViewReplies,
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

/// Live view requests that wait for the answer of the page, by id.
///
/// The live view tier is the only tier that the shell cannot answer itself
/// (D2.5: it knows nothing about diagrams). So a request waits here while the
/// webview runs it. The channel is how the socket thread waits without holding a lock.
type PendingLiveViewReplies = Arc<Mutex<HashMap<u64, std::sync::mpsc::Sender<LiveViewOutcome>>>>;

/// Open requests awaiting the page's acknowledgment, by request id.
///
/// Same shape as [[PendingLiveViewReplies]] and for the same reason: dispatching an
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
    /// What the page says it displayed (§4): a document session and its
    /// revision. It tells WHICH view answered, not only that a view answered.
    view: Option<serde_json::Value>,
}

#[derive(Debug, Clone)]
struct LiveViewOutcome {
    ok: bool,
    result: serde_json::Value,
    /// The page's own code, so "nothing is open" reaches the caller as
    /// `NO_LIVE_VIEW` rather than as a generic failure it would have to read the
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
    let pending_live_view_replies: PendingLiveViewReplies = Arc::new(Mutex::new(HashMap::new()));
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
        pending_live_view_replies.clone(),
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
        pending_live_view_replies: pending_live_view_replies.clone(),
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

                // The metadata store on disk is the live state, so an entry
                // that `gx` writes must reach the page. Nothing sends a
                // message: the shell sees that `metadata/` changed, and the
                // page reads it again.
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
            live_view_reply,
            viewer_ready,
            complete_open,
            hash_text,
            set_unsaved,
            confirm_close,
            library_list,
            library_read,
            library_write,
            library_delete,
            library_legacy_list,
            library_commit_migration,
            diagrams_folder,
            read_document,
            create_document,
            rename_document
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

/// What `gx open` asks the desktop to show.
///
/// `show` carries a typed target and not a bare path. Before ADR 0001, a
/// target could also name a library record, and the shell sent it to the page.
/// A diagram is now a file, so only a `file` target stays. A frame with an
/// other kind, for example the old `library` kind, is an invalid request.
///
/// Typed only, by decision: `gx` and this shell ship in one release, so a
/// mixed pair is a version mismatch rather than a shape to negotiate.
#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
enum OpenTarget {
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
    pending_live_view_replies: PendingLiveViewReplies,
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
                pending_live_view_replies: pending_live_view_replies.clone(),
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
    pending_live_view_replies: PendingLiveViewReplies,
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

        // `show` carries a TYPED target (§3.1), and `watch` is a plain file
        // operation with a path. The only target kind is `file` (ADR 0001).
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
        // live view tier, so the page can refuse it.
        //
        // It used to emit a `document.changed` with a null path — text with no
        // addressee — and the page put it into whichever viewer was on screen.
        // That is the same defect class as an open reporting success for a file
        // no viewer showed: the caller was told "pushed: true" whatever it hit.
        //
        // The live view tier already answers with the state of the view, and it
        // reports NO_LIVE_VIEW when there is no view. Thus a push through it gets
        // an honest answer, and no second reply mechanism is necessary.
        "push-text" => {
            let request: PushTextRequest = match serde_json::from_value(params) {
                Ok(request) => request,
                Err(err) => return RpcResponse::failure(id, "INVALID_REQUEST", err),
            };
            let frame = serde_json::json!({
                "command": "push-text",
                "params": { "sessionId": request.session_id, "text": request.text },
            });
            match run_live_view_command(context, frame) {
                Ok(_) => RpcResponse::success(id, serde_json::json!({ "pushed": true })),
                Err((code, message)) => RpcResponse::failure(id, code, message),
            }
        }

        "live-view" => match run_live_view_command(context, params) {
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

/// How long to wait for the page to answer a live view command.
///
/// Generous enough that a busy render does not look like a failure, short
/// enough that a wedged page does not hang a socket client indefinitely — the
/// failure mode that matters, since a CLI with no timeout is a CLI that has to
/// be killed.
const LIVE_VIEW_TIMEOUT: Duration = Duration::from_secs(5);

static LIVE_VIEW_REQUEST_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Relay a live view command to the webview and wait for its answer.
///
/// This is the one place the shell is a MIDDLEMAN rather than an implementer.
/// D2.5 keeps it diagram-ignorant and D3 keeps the page capability-free, so a
/// question about the live view has to cross both boundaries: the shell knows
/// who asked and how to answer them, the page knows what the answer is.
fn run_live_view_command(
    context: &ConnectionContext,
    params: serde_json::Value,
) -> std::result::Result<serde_json::Value, (&'static str, String)> {
    let command = params
        .get("command")
        .and_then(|v| v.as_str())
        .ok_or(("INVALID_REQUEST", "a live-view call needs a 'command'".to_string()))?;

    let id = LIVE_VIEW_REQUEST_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let (sender, receiver) = std::sync::mpsc::channel::<LiveViewOutcome>();

    match context.pending_live_view_replies.lock() {
        Ok(mut pending) => {
            pending.insert(id, sender);
        }
        Err(err) => return Err(("INTERNAL", format!("live view registry lock poisoned: {err}"))),
    }

    // Registered BEFORE the page is told, so a reply that arrives immediately
    // still finds somewhere to go.
    let mut frame = params.clone();
    if let Some(object) = frame.as_object_mut() {
        object.insert("id".to_string(), serde_json::json!(id));
    }
    let script = match serde_json::to_string(&frame) {
        Ok(json) => format!(
            "window.dispatchEvent(new CustomEvent('ge:live-view.command', {{ detail: {json} }}));"
        ),
        Err(err) => {
            forget_live_view_request(context, id);
            return Err(("INTERNAL", err.to_string()));
        }
    };

    let windows = context.app()?.webview_windows();
    if windows.is_empty() {
        forget_live_view_request(context, id);
        // The defining limit of the live view tier, reported as itself: there is
        // no live view, so there is no answer — not an empty one.
        return Err((
            "NO_LIVE_VIEW",
            format!("'{command}' needs a window, and the desktop has none open"),
        ));
    }
    for webview in windows.values() {
        if let Err(err) = webview.eval(&script) {
            forget_live_view_request(context, id);
            return Err(("LIVE_VIEW_FAILED", format!("could not reach the page: {err}")));
        }
    }

    let outcome = receiver.recv_timeout(LIVE_VIEW_TIMEOUT);
    forget_live_view_request(context, id);

    match outcome {
        Ok(outcome) if outcome.ok => Ok(outcome.result),
        Ok(outcome) => Err((live_view_failure_code(&outcome.code), outcome.message)),
        Err(_) => Err((
            "LIVE_VIEW_TIMEOUT",
            format!("the page did not answer '{command}' within {LIVE_VIEW_TIMEOUT:?}"),
        )),
    }
}

/// The code that the relay gives for a refusal of the page.
///
/// This is an ALLOWLIST. Thus the page cannot make a code that a caller must
/// find in the prose. Each code here tells the caller a different next step:
/// open a diagram (`NO_LIVE_VIEW`), correct the argument (`INVALID_REQUEST`),
/// or show the diagram that the command names (`VIEW_REJECTED`; its message
/// names the diagram on the screen). Each other code is `LIVE_VIEW_FAILED`, a
/// failure with no advice.
fn live_view_failure_code(code: &str) -> &'static str {
    match code {
        "NO_LIVE_VIEW" => "NO_LIVE_VIEW",
        "INVALID_REQUEST" => "INVALID_REQUEST",
        "VIEW_REJECTED" => "VIEW_REJECTED",
        _ => "LIVE_VIEW_FAILED",
    }
}

/// How long an open waits for the page to say it displayed the target.
///
/// Longer than the five seconds of a live view command, because this one can
/// include a cold start: the socket answers before the webview exists (§4.1),
/// and a Windows runner has been seen taking half a minute to bring one up. Still
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
fn forget_live_view_request(context: &ConnectionContext, id: u64) {
    if let Ok(mut pending) = context.pending_live_view_replies.lock() {
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
/// The page needs a SHA-256 in two places: the key of a library entry (the
/// hash of the canonical `file:` URI of a diagram, `LibraryEntry.keySource`)
/// and the hash of a text. The page cannot compute it: `Hashing` is
/// `MessageDigest`, which does not exist in Scala.js.
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

/// The answer of the page to a live view command.
///
/// A Tauri command rather than anything the page can reach on its own: it can
/// only ANSWER a question the shell asked, and an id nobody is waiting for is
/// dropped. That keeps the inversion honest — the webview serves this tier
/// without gaining the ability to initiate anything.
#[tauri::command(rename_all = "camelCase")]
fn live_view_reply(
    state: tauri::State<'_, IpcState>,
    id: u64,
    ok: bool,
    result: Option<serde_json::Value>,
    code: Option<String>,
    message: Option<String>,
) {
    let outcome = LiveViewOutcome {
        ok,
        result: result.unwrap_or(serde_json::Value::Null),
        code: code.unwrap_or_else(|| "LIVE_VIEW_FAILED".to_string()),
        message: message.unwrap_or_else(|| "the page reported a failure".to_string()),
    };
    if let Ok(mut pending) = state.pending_live_view_replies.lock() {
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

/// The `show` path for a diagram file: watch it, put it on screen, raise the
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

/// A directory that only its owner can read, write and enter (0700).
///
/// The JVM makes the temporary directory of the migration with
/// `Files.createTempDirectory`, which gives 0700 on POSIX. The shell gives the
/// same bits, so that `metadata/` does not depend on which process made it.
#[cfg(unix)]
fn set_owner_only_dir_permissions(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("failed to set directory permissions on {}", path.display()))?;
    Ok(())
}

#[cfg(not(unix))]
fn set_owner_only_dir_permissions(_path: &Path) -> Result<()> {
    Ok(())
}

// ------------------------------------------------------------------ library
//
// ADR 0001: a diagram is a file, and the library is the metadata of each file.
// `gx` writes one entry for each diagram into
// `$GX_HOME/library/metadata/<sha256>.json`. The webview reads and writes the
// same directory through the commands below. Thus a `gx hide` reaches a
// running window with no message.
//
// The name of an entry is the hex SHA-256 of the canonical `file:` URI of the
// diagram (`LibraryEntry.keySource` in gx-core). The page makes the URI with
// the shared Scala function and hashes it with `hash_text`. The shell does not
// make the URI. It checks only that a name has the form of a key, so that a
// name cannot address a file outside the directory.
//
// The shell moves RAW JSON STRINGS and never parses an entry (D2.5:
// privilege, not intelligence). The schema is gx-core's, defined once in Scala
// and linked into both `gx` and the page. A Rust struct for it would be a
// third copy of one contract, and a copy can drift. V-13's content hash
// drifted in that way.
//
// The old records are in `library/diagrams/`. The shell only reads them, for
// the one-time migration (plan §4). It never writes there, so that an older
// desktop that is still installed shows its library as before.

/// `$GX_HOME/library`: the parent of `metadata/` and of `diagrams/`.
fn library_root_path() -> Result<PathBuf> {
    Ok(graph_explorer_dir()?.join("library"))
}

/// `library/metadata`: one entry for each diagram (plan §3).
fn metadata_dir_in(library_root: &Path) -> PathBuf {
    library_root.join("metadata")
}

/// `library/diagrams`: the old records, read only by the migration.
fn legacy_dir_in(library_root: &Path) -> PathBuf {
    library_root.join("diagrams")
}

fn metadata_dir_path() -> Result<PathBuf> {
    Ok(metadata_dir_in(&library_root_path()?))
}

/// True for a name of the form `<64 lowercase hex characters>.json`.
///
/// That is the form of every key that `MetadataStore.fileNameOf` gives. A
/// name of this form has no separator, no dot at the start and no drive
/// letter, so a join with the directory cannot leave the directory.
fn is_entry_name(name: &str) -> bool {
    match name.strip_suffix(".json") {
        Some(key) => {
            key.len() == 64 && key.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        }
        None => false,
    }
}

/// Resolve the name of an entry against the metadata directory.
///
/// The key rule is NOT here. It is in gx-core's shared Scala, which `gx` and
/// the page call, so there is one rule. The shell asks a different question,
/// and the answer is safe without the rule: does the name have the form of a
/// key?
fn library_entry_path(dir: &Path, name: &str) -> std::result::Result<PathBuf, IpcError> {
    if is_entry_name(name) {
        Ok(dir.join(name))
    } else {
        Err(IpcError::new(
            "INVALID_LIBRARY_NAME",
            format!("a library entry name must be <64 lowercase hex characters>.json: {name:?}"),
        ))
    }
}

/// One entry, or one old record, as the page receives it.
///
/// A file that cannot be read is REPORTED and not dropped. If the shell
/// dropped it, the library would have a missing diagram and no message.
/// `MetadataStore.unreadable()` makes the same choice on the JVM side.
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
        // A directory that does not exist is an empty list, not a failure.
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

    // Sorted, so that the page gets the same order each time. `read_dir` gives
    // the order of the file system, which is different on each platform.
    entries.sort_by(|a, b| a.name.cmp(&b.name));
    entries
}

fn list_library_entries(dir: &Path, audit_logger: &AuditLogger) -> Vec<LibraryEntry> {
    let entries = read_library_entries(dir);
    audit_logger.log_event(
        "library.list",
        Some(&dir.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    entries
}

fn read_library_entry(
    dir: &Path,
    name: &str,
    audit_logger: &AuditLogger,
) -> std::result::Result<String, IpcError> {
    let path = library_entry_path(dir, name)?;
    let json = fs::read_to_string(&path)
        .map_err(|err| IpcError::new("LIBRARY_READ_FAILED", format!("{name}: {err}")))?;
    audit_logger.log_event(
        "library.read",
        Some(&path.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(json)
}

/// Write one entry into the metadata directory.
///
/// The write refuses a directory that does not exist, and it does NOT make
/// the directory. The migration makes it, and the migration runs only while
/// the directory does not exist. If a write made it, the migration would
/// never run, and the old records would stay out of the library.
/// `MetadataStore.put` refuses in the same way on the JVM.
fn write_library_entry(
    dir: &Path,
    name: &str,
    json: &str,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
) -> std::result::Result<(), IpcError> {
    let path = library_entry_path(dir, name)?;
    if !dir.is_dir() {
        return Err(IpcError::new(
            "LIBRARY_NOT_MIGRATED",
            format!(
                "the metadata store {} does not exist. The migration makes it.",
                dir.display()
            ),
        ));
    }

    let existed = path.exists();

    // The same atomic write that documents use. Thus V-03 (a write keeps the
    // permission bits of the file) applies to library entries too.
    write_file_atomic(&path.to_string_lossy(), json)
        .map_err(|err| IpcError::new("LIBRARY_WRITE_FAILED", format!("{name}: {err}")))?;

    // A NEW entry is owner-only, as the entries that `gx` writes are.
    //
    // This does not contradict V-03. V-03 keeps the bits that a person chose.
    // Nobody chose these bits, because the file did not exist. Without this,
    // `gx` and the shell give a new entry different bits, and the metadata of
    // a person becomes readable by other local accounts, by chance.
    #[cfg(unix)]
    if !existed {
        if let Err(err) = set_owner_only_permissions(&path) {
            eprintln!("could not restrict permissions on {}: {err}", path.display());
        }
    }
    #[cfg(not(unix))]
    let _ = existed;

    // Keep the hash of our own bytes, so that the watcher does not tell the
    // page about a change that the page made. Without this, each change in the
    // page comes back as an outside change.
    if let Ok(mut hashes) = recent_write_hashes.lock() {
        hashes.insert(path.to_string_lossy().to_string(), content_hash(json.as_bytes()));
    }

    audit_logger.log_event(
        "library.write",
        Some(&path.to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(())
}

/// Remove one entry. The diagram file does not change ("Remove from
/// library"). `false` tells that the entry was not there.
fn delete_library_entry(
    dir: &Path,
    name: &str,
    recent_write_hashes: &RecentWriteHashes,
    audit_logger: &AuditLogger,
) -> std::result::Result<bool, IpcError> {
    let path = library_entry_path(dir, name)?;
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
    // Thus the watcher does not report our own delete back to us.
    if removed {
        if let Ok(mut hashes) = recent_write_hashes.lock() {
            hashes.insert(path.to_string_lossy().to_string(), LIBRARY_DELETED.to_string());
        }
    }
    audit_logger.log_event(
        "library.delete",
        Some(&path.to_string_lossy()),
        Some("ui"),
        if removed { "allowed" } else { "not-found" },
        None,
        None,
    );
    Ok(removed)
}

#[tauri::command(rename_all = "camelCase")]
fn library_list(state: tauri::State<'_, IpcState>) -> std::result::Result<Vec<LibraryEntry>, IpcError> {
    let dir = metadata_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    Ok(list_library_entries(&dir, &state.audit_logger))
}

#[tauri::command(rename_all = "camelCase")]
fn library_read(
    state: tauri::State<'_, IpcState>,
    name: String,
) -> std::result::Result<String, IpcError> {
    let dir = metadata_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    read_library_entry(&dir, &name, &state.audit_logger)
}

#[tauri::command(rename_all = "camelCase")]
fn library_write(
    state: tauri::State<'_, IpcState>,
    name: String,
    json: String,
) -> std::result::Result<(), IpcError> {
    let dir = metadata_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    write_library_entry(&dir, &name, &json, &state.recent_write_hashes, &state.audit_logger)
}

#[tauri::command(rename_all = "camelCase")]
fn library_delete(
    state: tauri::State<'_, IpcState>,
    name: String,
) -> std::result::Result<bool, IpcError> {
    let dir = metadata_dir_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    delete_library_entry(&dir, &name, &state.recent_write_hashes, &state.audit_logger)
}

// --------------------------------------------------------------- migration

/// The old records, for the migration, and whether the migration is done.
///
/// `migrated` is true when `metadata/` exists. Then the migration has nothing
/// to do, and the shell does not read the old records.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LegacyListing {
    migrated: bool,
    records: Vec<LibraryEntry>,
}

/// List the old records in `library/diagrams/`. This function only reads.
fn list_legacy_records(library_root: &Path, audit_logger: &AuditLogger) -> LegacyListing {
    let migrated = metadata_dir_in(library_root).exists();
    let records = if migrated {
        Vec::new()
    } else {
        read_library_entries(&legacy_dir_in(library_root))
    };
    audit_logger.log_event(
        "library.legacy_list",
        Some(&legacy_dir_in(library_root).to_string_lossy()),
        Some("ui"),
        "allowed",
        None,
        Some(&format!("migrated={migrated}, records={}", records.len())),
    );
    LegacyListing { migrated, records }
}

#[tauri::command(rename_all = "camelCase")]
fn library_legacy_list(
    state: tauri::State<'_, IpcState>,
) -> std::result::Result<LegacyListing, IpcError> {
    let root = library_root_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    Ok(list_legacy_records(&root, &state.audit_logger))
}

/// One entry that the migration writes: the name of its file and its JSON.
#[derive(Debug, Deserialize)]
struct NamedEntry {
    name: String,
    json: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MigrationCommit {
    committed: usize,
}

/// Write the entries of the migration into a new `metadata/` (plan §4).
///
/// The order makes a crash safe. The entries go into a temporary directory
/// beside `metadata/`, and a rename makes it `metadata/` LAST. Before the
/// rename, `metadata/` does not exist, and the next start tries again. After
/// the rename, the store is complete. No order of failures leaves a part of a
/// store. `MetadataMigrationExecutor.execute` uses the same order on the JVM.
fn commit_migration(
    library_root: &Path,
    entries: &[NamedEntry],
    audit_logger: &AuditLogger,
) -> std::result::Result<MigrationCommit, IpcError> {
    let metadata_dir = metadata_dir_in(library_root);
    let already = || {
        IpcError::new(
            "LIBRARY_ALREADY_MIGRATED",
            format!("{} exists, so the migration is done", metadata_dir.display()),
        )
    };
    if metadata_dir.exists() {
        return Err(already());
    }

    // Check each name before the first write, so that a bad name changes
    // nothing.
    let mut seen = std::collections::HashSet::new();
    for entry in entries {
        library_entry_path(&metadata_dir, &entry.name)?;
        if !seen.insert(entry.name.as_str()) {
            return Err(IpcError::new(
                "INVALID_LIBRARY_NAME",
                format!("the migration names {:?} two times", entry.name),
            ));
        }
    }

    let failed = |why: String| {
        audit_logger.log_event(
            "library.migration_committed",
            Some(&metadata_dir.to_string_lossy()),
            Some("ui"),
            "error",
            None,
            Some(&why),
        );
        IpcError::new("LIBRARY_MIGRATION_FAILED", why)
    };

    fs::create_dir_all(library_root)
        .map_err(|err| failed(format!("could not make {}: {err}", library_root.display())))?;

    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let temp = library_root.join(format!(".metadata-{}-{nanos}", std::process::id()));
    fs::create_dir(&temp)
        .map_err(|err| failed(format!("could not make {}: {err}", temp.display())))?;

    let result = (|| -> std::result::Result<(), String> {
        // Owner-only, as the temporary directory that the JVM makes is.
        set_owner_only_dir_permissions(&temp).map_err(|err| err.to_string())?;
        for entry in entries {
            let path = temp.join(&entry.name);
            fs::write(&path, entry.json.as_bytes())
                .map_err(|err| format!("could not write {}: {err}", entry.name))?;
            set_owner_only_permissions(&path).map_err(|err| err.to_string())?;
        }
        // A different process can finish its own migration in the meantime.
        // Check again just before the rename, so that this run does not put
        // its store over that store.
        if metadata_dir.exists() {
            return Err("ALREADY".to_string());
        }
        fs::rename(&temp, &metadata_dir)
            .map_err(|err| format!("could not rename {} to {}: {err}", temp.display(), metadata_dir.display()))
    })();

    match result {
        Ok(()) => {
            audit_logger.log_event(
                "library.migration_committed",
                Some(&metadata_dir.to_string_lossy()),
                Some("ui"),
                "ok",
                None,
                Some(&format!("entries={}", entries.len())),
            );
            Ok(MigrationCommit {
                committed: entries.len(),
            })
        }
        Err(why) => {
            // The temporary directory belongs to this run, so its removal
            // loses nothing.
            let _ = fs::remove_dir_all(&temp);
            if why == "ALREADY" {
                Err(already())
            } else {
                Err(failed(why))
            }
        }
    }
}

#[tauri::command(rename_all = "camelCase")]
fn library_commit_migration(
    state: tauri::State<'_, IpcState>,
    entries: Vec<NamedEntry>,
) -> std::result::Result<MigrationCommit, IpcError> {
    let root = library_root_path().map_err(|err| IpcError::new("LIBRARY_UNAVAILABLE", err))?;
    commit_migration(&root, &entries, &state.audit_logger)
}

// --------------------------------------------------- the folder for new diagrams

/// The folder for new diagrams: `$GX_DIAGRAMS_DIR`, or else
/// `~/Documents/Graph Explorer`.
///
/// It must agree with `GxHome.diagramsDir` in gx-core. The two test suites
/// read the same cases in `local-protocol/fixtures/diagrams-dir.json`.
///
/// `$GX_HOME` does not move this folder, because the diagrams are the files
/// of the person and not the data of gx. A blank value means "not set", as a
/// blank `GX_HOME` does. A relative value is refused for the reason that a
/// relative `GX_HOME` is refused: gx and the desktop do not share a working
/// directory.
fn diagrams_dir_from(
    diagrams_dir: Option<std::ffi::OsString>,
    home_dir: Option<PathBuf>,
) -> Result<PathBuf> {
    if let Some(dir) = diagrams_dir {
        let trimmed = dir.to_string_lossy().trim().to_string();
        if !trimmed.is_empty() {
            let path = PathBuf::from(&trimmed);
            if path.is_relative() {
                anyhow::bail!(
                    "GX_DIAGRAMS_DIR must be an absolute path, but is '{trimmed}'. A relative \
                     value resolves against each process's working directory, and gx and the \
                     desktop do not share one — they would put new diagrams in different \
                     folders without saying so."
                );
            }
            return Ok(normalize_dot_segments(&path));
        }
    }
    let home_dir = home_dir.context("could not locate user home directory")?;
    Ok(home_dir.join("Documents").join("Graph Explorer"))
}

/// The folder for new diagrams, and the names that exist in it.
///
/// `existing` holds each name in the folder and each name in `Recovered/`
/// (as `Recovered/<name>`). The migration needs them, because it must not
/// give a new file the name of a file that exists. The JVM gives the same
/// names (`MetadataMigrationExecutor.namesIn`): each entry, a file or a
/// directory.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DiagramsFolder {
    path: String,
    existing: Vec<String>,
}

fn diagrams_folder_listing(
    access_policy: &AccessPolicy,
    folder: &Path,
) -> std::result::Result<DiagramsFolder, IpcError> {
    let canonical = normalize_path(&folder.to_string_lossy())
        .map_err(|err| IpcError::new("DIAGRAMS_FOLDER_INVALID", err))?;
    let canonical_path = PathBuf::from(&canonical);
    ensure_path_not_denied(access_policy, &canonical_path)
        .and_then(|_| ensure_path_allowed(access_policy, &canonical_path))
        .map_err(|err| IpcError::new("DOCUMENT_DENIED", err))?;

    let names_in = |dir: &Path, prefix: &str| -> Vec<String> {
        match fs::read_dir(dir) {
            Ok(listing) => listing
                .filter_map(|entry| entry.ok())
                .map(|entry| format!("{prefix}{}", entry.file_name().to_string_lossy()))
                .collect(),
            Err(_) => Vec::new(),
        }
    };
    let mut existing = names_in(&canonical_path, "");
    existing.extend(names_in(&canonical_path.join("Recovered"), "Recovered/"));
    existing.sort();

    Ok(DiagramsFolder {
        path: canonical,
        existing,
    })
}

#[tauri::command(rename_all = "camelCase")]
fn diagrams_folder(
    state: tauri::State<'_, IpcState>,
) -> std::result::Result<DiagramsFolder, IpcError> {
    let folder = diagrams_dir_from(env::var_os("GX_DIAGRAMS_DIR"), dirs::home_dir())
        .map_err(|err| IpcError::new("DIAGRAMS_FOLDER_INVALID", err))?;
    let listing = diagrams_folder_listing(&state.access_policy, &folder)?;
    state.audit_logger.log_event(
        "diagrams.folder",
        Some(&listing.path),
        Some("ui"),
        "allowed",
        None,
        None,
    );
    Ok(listing)
}

// --------------------------------------------------------- diagram files

/// The canonical path of a document, after the access policy.
///
/// The policy check comes BEFORE each existence check. Thus a refusal tells
/// nothing about a file that the policy hides.
fn allowed_document_path(
    access_policy: &AccessPolicy,
    path: &str,
    failure_code: &'static str,
) -> std::result::Result<String, IpcError> {
    let normalized = normalize_path(path).map_err(|err| IpcError::new(failure_code, err))?;
    let normalized_path = PathBuf::from(&normalized);
    ensure_path_not_denied(access_policy, &normalized_path)
        .and_then(|_| ensure_path_allowed(access_policy, &normalized_path))
        .map_err(|err| IpcError::new("DOCUMENT_DENIED", err))?;
    Ok(normalized)
}

/// Log the result of a document operation, and give the result back.
fn audited<T>(
    audit_logger: &AuditLogger,
    action: &str,
    path: &str,
    result: std::result::Result<(T, Option<String>), IpcError>,
) -> std::result::Result<T, IpcError> {
    match result {
        Ok((value, revision)) => {
            audit_logger.log_event(action, Some(path), Some("ui"), "ok", revision, None);
            Ok(value)
        }
        Err(err) => {
            audit_logger.log_event(
                action,
                Some(path),
                Some("ui"),
                "rejected",
                None,
                Some(&format!("{}: {}", err.code, err.message)),
            );
            Err(err)
        }
    }
}

/// Read a diagram file, with NO watch.
///
/// The home page shows the title, the kind and a thumbnail of each diagram in
/// the library. A watch for each of these files costs a thread and a poll,
/// and the page does not follow the files on the home page. `open_document`
/// starts a watch when a diagram opens.
///
/// The text is a LOSSY UTF-8 decode, as the watch loop makes. The revision is
/// the hash of the bytes on disk, so a compare-and-swap save sees each
/// difference, also one that the decode hides.
fn read_document_at(
    access_policy: &AccessPolicy,
    audit_logger: &AuditLogger,
    path: &str,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    let result = (|| {
        let normalized = allowed_document_path(access_policy, path, "DOCUMENT_READ_FAILED")?;
        match fs::metadata(&normalized) {
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                return Err(IpcError::new("DOCUMENT_MISSING", format!("{normalized} does not exist")))
            }
            Err(err) => return Err(IpcError::new("DOCUMENT_READ_FAILED", format!("{normalized}: {err}"))),
            Ok(meta) if !meta.is_file() => {
                return Err(IpcError::new(
                    "DOCUMENT_READ_FAILED",
                    format!("{normalized} is not a regular file"),
                ))
            }
            Ok(_) => {}
        }
        let bytes = fs::read(&normalized)
            .map_err(|err| IpcError::new("DOCUMENT_READ_FAILED", format!("{normalized}: {err}")))?;
        let revision = content_hash(&bytes);
        let snapshot = DocumentSnapshot {
            format: infer_format_from_path(&normalized),
            text: String::from_utf8_lossy(&bytes).into_owned(),
            revision: revision.clone(),
            path: normalized,
            timestamp_ms: current_time_ms(),
        };
        Ok((snapshot, Some(revision)))
    })();
    audited(audit_logger, "document.read", path, result)
}

#[tauri::command(rename_all = "camelCase")]
fn read_document(
    state: tauri::State<'_, IpcState>,
    path: String,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    read_document_at(&state.access_policy, &state.audit_logger, &path)
}

/// Create a NEW diagram file. The create never writes over a file that
/// exists.
///
/// The create is atomic and it does not overwrite. The text goes into a
/// temporary file beside the target, and a hard link gives it the name of the
/// target. A hard link fails when the name exists, so a file that a different
/// process made in the meantime stays unchanged. A reader never sees a part
/// of the file.
///
/// The bytes are the text, unchanged (V-04: the page gives the line endings
/// and the final newline). The new file gets the default mode of the umask of
/// the person (V-03 for a file that did not exist), because the temporary file
/// gets it and the link shares it.
fn create_document_at(
    access_policy: &AccessPolicy,
    audit_logger: &AuditLogger,
    path: &str,
    text: &str,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    let result = (|| {
        let normalized = allowed_document_path(access_policy, path, "DOCUMENT_WRITE_FAILED")?;
        let target = PathBuf::from(&normalized);
        let exists = || IpcError::new("DOCUMENT_EXISTS", format!("{normalized} exists"));
        let write_failed = |why: String| IpcError::new("DOCUMENT_WRITE_FAILED", format!("{normalized}: {why}"));

        // `symlink_metadata`, so that a link that points nowhere also counts.
        // A create through it would write a file at a different place.
        if fs::symlink_metadata(&target).is_ok() {
            return Err(exists());
        }
        let parent = target
            .parent()
            .ok_or_else(|| write_failed("the path has no parent directory".to_string()))?;
        fs::create_dir_all(parent).map_err(|err| write_failed(err.to_string()))?;

        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        // A different name from the `.name.tmp` of `write_file_atomic`, so
        // that a save and a create cannot use the same temporary file.
        let temp = parent.join(format!(
            ".{}.create-{}-{nanos}.tmp",
            target.file_name().and_then(|v| v.to_str()).unwrap_or("graph-explorer"),
            std::process::id()
        ));
        let write_new = |at: &Path| -> std::io::Result<()> {
            let mut file = OpenOptions::new().write(true).create_new(true).open(at)?;
            file.write_all(text.as_bytes())?;
            file.sync_all()
        };
        write_new(&temp).map_err(|err| {
            let _ = fs::remove_file(&temp);
            write_failed(err.to_string())
        })?;

        let linked = fs::hard_link(&temp, &target);
        let _ = fs::remove_file(&temp);
        match linked {
            Ok(()) => {}
            Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => return Err(exists()),
            Err(_) => {
                // The file system has no hard links (for example FAT). Then
                // `create_new` still refuses a file that exists, but the write
                // is not atomic: a crash can leave a part of the file.
                match write_new(&target) {
                    Ok(()) => {}
                    Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => return Err(exists()),
                    Err(err) => {
                        let _ = fs::remove_file(&target);
                        return Err(write_failed(err.to_string()));
                    }
                }
            }
        }

        let revision = content_hash(text.as_bytes());
        let snapshot = DocumentSnapshot {
            format: infer_format_from_path(&normalized),
            text: text.to_string(),
            revision: revision.clone(),
            path: normalized,
            timestamp_ms: current_time_ms(),
        };
        Ok((snapshot, Some(revision)))
    })();
    audited(audit_logger, "document.created", path, result)
}

#[tauri::command(rename_all = "camelCase")]
fn create_document(
    state: tauri::State<'_, IpcState>,
    path: String,
    text: String,
) -> std::result::Result<DocumentSnapshot, IpcError> {
    create_document_at(&state.access_policy, &state.audit_logger, &path, &text)
}

/// What a rename did.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RenameOutcome {
    /// The canonical path of the file after the rename.
    path: String,
    revision: String,
    /// True when the file had a watch, and the watch now follows the new path.
    watch_moved: bool,
}

/// Rename a file, and refuse when the new name exists.
///
/// The rename does not overwrite. A hard link gives the file its new name,
/// and fails when that name exists. Then the old name goes. The two names are
/// one file in the meantime, so no order of failures loses the file. A hard
/// link keeps the inode, so the file keeps its bytes, its mode (V-03) and its
/// line endings (V-04).
///
/// @return the canonical old path and the canonical new path.
fn rename_file(
    access_policy: &AccessPolicy,
    from: &str,
    to: &str,
) -> std::result::Result<(String, String), IpcError> {
    let from_norm = allowed_document_path(access_policy, from, "DOCUMENT_RENAME_FAILED")?;
    let to_norm = allowed_document_path(access_policy, to, "DOCUMENT_RENAME_FAILED")?;
    let failed = |why: String| IpcError::new("DOCUMENT_RENAME_FAILED", why);
    let exists = || IpcError::new("DOCUMENT_EXISTS", format!("{to_norm} exists"));

    match fs::metadata(&from_norm) {
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
            return Err(IpcError::new("DOCUMENT_MISSING", format!("{from_norm} does not exist")))
        }
        Err(err) => return Err(failed(format!("{from_norm}: {err}"))),
        Ok(meta) if !meta.is_file() => return Err(failed(format!("{from_norm} is not a regular file"))),
        Ok(_) => {}
    }

    if from_norm == to_norm {
        // The two names are one file. On a file system that ignores case,
        // `a.dot` and `A.dot` give one canonical path. A plain rename to the
        // literal new name changes the case, and it cannot lose a file. A
        // rename to the same name does nothing.
        let literal = PathBuf::from(to);
        let literal = if literal.is_absolute() { literal } else { PathBuf::from(&to_norm) };
        if literal.file_name() != Path::new(&from_norm).file_name() {
            fs::rename(&from_norm, &literal).map_err(|err| failed(format!("{from_norm}: {err}")))?;
        }
        let after = normalize_path(&literal.to_string_lossy()).map_err(|err| failed(err.to_string()))?;
        return Ok((from_norm, after));
    }

    if fs::symlink_metadata(&to_norm).is_ok() {
        return Err(exists());
    }

    match fs::hard_link(&from_norm, &to_norm) {
        Ok(()) => {
            if let Err(err) = fs::remove_file(&from_norm) {
                // The old name stays. Remove the new name, so that the file
                // has one name again, and report the failure.
                let _ = fs::remove_file(&to_norm);
                return Err(failed(format!("could not remove the old name {from_norm}: {err}")));
            }
        }
        Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => return Err(exists()),
        Err(_) => {
            // No hard link here: two file systems (EXDEV), or a file system
            // with no hard links. A plain rename replaces a file that exists,
            // so check again just before it. A different process can make the
            // file between the check and the rename. That window is small,
            // and the hard link above closes it on each usual file system.
            if fs::symlink_metadata(&to_norm).is_ok() {
                return Err(exists());
            }
            fs::rename(&from_norm, &to_norm).map_err(|err| failed(format!("{from_norm}: {err}")))?;
        }
    }

    let after = normalize_path(&to_norm).map_err(|err| failed(err.to_string()))?;
    Ok((from_norm, after))
}

/// Move the watch of a renamed file to its new path.
///
/// The watch loop of the old path stops, and a new loop starts for the new
/// path. The registry gets a descriptor for the new path, with the hash of
/// the bytes as its revision. The move sends NO document event: the page
/// moves its own document session to the new path. A document event would
/// make a second session for the same file.
///
/// `spawn` starts the watch loop. It is a parameter, so that a test can move
/// a watch without an `AppHandle`.
///
/// @return false when the old path had no watch. Then nothing changes.
fn move_watch(
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    recent_write_hashes: &RecentWriteHashes,
    from: &str,
    to: &str,
    spawn: &dyn Fn(&str) -> WatchController,
) -> Result<bool> {
    let mut registry = watch_registry
        .lock()
        .map_err(|err| anyhow::anyhow!("watch registry lock poisoned: {err}"))?;
    if registry.remove(from).is_none() {
        return Ok(false);
    }

    let mut controllers = watch_controllers
        .lock()
        .map_err(|err| anyhow::anyhow!("watch controller lock poisoned: {err}"))?;
    if let Some(controller) = controllers.remove(from) {
        let _ = controller.stop_tx.send(());
    }
    // A watch on the new path is not possible before the rename, because a
    // watch needs a file. Stop one all the same, so that two loops never
    // follow one path.
    if let Some(controller) = controllers.remove(to) {
        let _ = controller.stop_tx.send(());
    }
    if let Ok(mut writes) = recent_write_hashes.lock() {
        writes.remove(from);
    }

    let watch = WatchDescriptor {
        format: infer_format_from_path(to),
        path: to.to_string(),
        revision: fs::read(to).map(|b| content_hash(&b)).unwrap_or_default(),
    };
    registry.insert(to.to_string(), watch);
    drop(registry);
    controllers.insert(to.to_string(), spawn(to));
    Ok(true)
}

fn rename_document_at(
    access_policy: &AccessPolicy,
    audit_logger: &AuditLogger,
    watch_registry: &WatchRegistry,
    watch_controllers: &WatchControllers,
    recent_write_hashes: &RecentWriteHashes,
    from: &str,
    to: &str,
    spawn: &dyn Fn(&str) -> WatchController,
) -> std::result::Result<RenameOutcome, IpcError> {
    let result = (|| {
        let (from_norm, to_norm) = rename_file(access_policy, from, to)?;
        let revision = fs::read(&to_norm)
            .map(|bytes| content_hash(&bytes))
            .map_err(|err| IpcError::new("DOCUMENT_RENAME_FAILED", format!("{to_norm}: {err}")))?;
        let watch_moved = move_watch(
            watch_registry,
            watch_controllers,
            recent_write_hashes,
            &from_norm,
            &to_norm,
            spawn,
        )
        .map_err(|err| {
            IpcError::new(
                "DOCUMENT_RENAME_FAILED",
                format!("the file is now {to_norm}, but its watch did not move: {err}"),
            )
        })?;
        if watch_moved {
            audit_logger.log_event(
                "watch.moved",
                Some(&to_norm),
                Some("ui"),
                "ok",
                Some(revision.clone()),
                Some(&format!("from {from_norm}")),
            );
        }
        let outcome = RenameOutcome {
            path: to_norm,
            revision: revision.clone(),
            watch_moved,
        };
        Ok((outcome, Some(revision)))
    })();
    audited(audit_logger, "document.renamed", &format!("{from} -> {to}"), result)
}

#[tauri::command(rename_all = "camelCase")]
fn rename_document(
    app: tauri::AppHandle,
    state: tauri::State<'_, IpcState>,
    from: String,
    to: String,
) -> std::result::Result<RenameOutcome, IpcError> {
    let spawn = |path: &str| {
        spawn_watch_loop(
            state.watch_registry.clone(),
            state.recent_write_hashes.clone(),
            app.clone(),
            path.to_string(),
        )
    };
    rename_document_at(
        &state.access_policy,
        &state.audit_logger,
        &state.watch_registry,
        &state.watch_controllers,
        &state.recent_write_hashes,
        &from,
        &to,
        &spawn,
    )
}

/// The identity of one entry for change detection: name, size, mtime.
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
        let Ok(dir) = metadata_dir_path() else {
            eprintln!("library watcher: could not locate the metadata directory");
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

    /// The relay keeps the codes of the page that tell the caller what to do
    /// next. `VIEW_REJECTED` is the refusal of a live view command for a
    /// diagram that is not on the screen (plan §2). Its message names the
    /// diagram on the screen, and `gx` prints it.
    #[test]
    fn the_relay_keeps_the_codes_of_the_page_that_name_a_next_step() {
        for code in ["NO_LIVE_VIEW", "INVALID_REQUEST", "VIEW_REJECTED"] {
            assert_eq!(live_view_failure_code(code), code, "the relay changed {code}");
        }
    }

    /// A code that is not in the list is a failure with no advice. The page
    /// cannot make a code that a caller must find in the prose.
    #[test]
    fn the_relay_gives_an_unknown_code_of_the_page_as_live_view_failed() {
        for code in ["", "SOMETHING_NEW", "view_rejected", "INTERNAL"] {
            assert_eq!(live_view_failure_code(code), "LIVE_VIEW_FAILED", "the relay kept {code:?}");
        }
    }

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
            pending_live_view_replies: Arc::new(Mutex::new(HashMap::new())),
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

    // ------------------------------------------------------- the library

    /// The hex key of an entry, as `MetadataStore.fileNameOf` gives it.
    fn key_name(seed: &str) -> String {
        format!("{}.json", content_hash(seed.as_bytes()))
    }

    fn test_audit(dir: &Path) -> AuditLogger {
        AuditLogger {
            file_path: dir.join("audit.jsonl"),
            write_lock: Arc::new(Mutex::new(())),
        }
    }

    fn no_policy() -> AccessPolicy {
        AccessPolicy {
            allowed_roots: Vec::new(),
            denied_roots: Vec::new(),
        }
    }

    fn denying(root: &Path) -> AccessPolicy {
        AccessPolicy {
            allowed_roots: Vec::new(),
            denied_roots: vec![fs::canonicalize(root).expect("a denied root that exists")],
        }
    }

    /// A fresh, empty directory for one test, with its real path (on macOS
    /// the temp directory is behind a symlink).
    fn fresh_dir(test: &str) -> PathBuf {
        let dir = temp_dir_for(test);
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("dir");
        fs::canonicalize(&dir).expect("real path")
    }

    /// The name of an entry is the key of a path, and nothing else. A name of
    /// that form cannot leave the directory, so the shell needs no other check.
    #[test]
    fn a_library_entry_name_is_a_key_and_nothing_else() {
        let dir = PathBuf::from("/tmp/library/metadata");
        let key = content_hash(b"file:///tmp/a.dot");
        assert!(library_entry_path(&dir, &format!("{key}.json")).is_ok());

        for refused in [
            format!("{}.json", key.to_uppercase()),
            format!("{}.json", &key[1..]),
            format!("{key}0.json"),
            key.clone(),
            format!("{key}.txt"),
            format!("{key}.json.tmp"),
            format!(".{key}.json"),
            format!("../{key}.json"),
            format!("sub/{key}.json"),
            format!("sub\\{key}.json"),
            format!("C:{key}.json"),
            "../secrets.json".to_string(),
            "arch.json".to_string(),
            "\u{8a2d}\u{8a08}.json".to_string(),
            String::new(),
        ] {
            let err = library_entry_path(&dir, &refused)
                .expect_err(&format!("should have been refused: {refused:?}"));
            assert_eq!(err.code, "INVALID_LIBRARY_NAME", "{refused:?}");
        }
    }

    /// A write must not make `metadata/`. The migration makes it, and runs
    /// only while it does not exist. A write that made it would stop the
    /// migration for ever, and the old records would stay out of the library.
    #[test]
    fn a_write_refuses_a_store_that_the_migration_has_not_made() {
        let root = fresh_dir("library-not-migrated");
        let metadata = metadata_dir_in(&root);
        let recent: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));

        let err = write_library_entry(&metadata, &key_name("a"), "{}", &recent, &test_audit(&root))
            .expect_err("a write before the migration must be refused");
        assert_eq!(err.code, "LIBRARY_NOT_MIGRATED");
        assert!(!metadata.exists(), "the write made metadata/");
        assert!(recent.lock().expect("lock").is_empty());

        let _ = fs::remove_dir_all(&root);
    }

    /// A new entry is owner-only, as the entries that `gx` writes are. An
    /// entry that exists keeps its bits (V-03). The page does not hear about
    /// its own write or its own delete.
    #[test]
    fn a_write_keeps_its_bytes_and_records_its_hash() {
        let root = fresh_dir("library-write");
        let metadata = metadata_dir_in(&root);
        fs::create_dir_all(&metadata).expect("metadata");
        let recent: RecentWriteHashes = Arc::new(Mutex::new(HashMap::new()));
        let name = key_name("a");
        let json = "{\"path\":\"/tmp/a.dot\"}";

        write_library_entry(&metadata, &name, json, &recent, &test_audit(&root)).expect("write");
        let path = metadata.join(&name);
        assert_eq!(fs::read_to_string(&path).expect("read"), json);
        assert_eq!(
            read_library_entry(&metadata, &name, &test_audit(&root)).expect("read entry"),
            json
        );
        assert!(
            !library_change_is_news(&metadata, &[name.clone()], &recent),
            "the page must not hear about its own write"
        );

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(mode_of(&path), 0o600, "a new entry must be owner-only");
            fs::set_permissions(&path, fs::Permissions::from_mode(0o640)).expect("chmod");
            write_library_entry(&metadata, &name, "{}", &recent, &test_audit(&root)).expect("rewrite");
            assert_eq!(mode_of(&path), 0o640, "an entry that exists keeps its bits");
            // Spend the recorded hash of the rewrite.
            assert!(!library_change_is_news(&metadata, &[name.clone()], &recent));
        }

        assert!(delete_library_entry(&metadata, &name, &recent, &test_audit(&root)).expect("delete"));
        assert!(!path.exists());
        assert!(
            !library_change_is_news(&metadata, &[name.clone()], &recent),
            "the page must not hear about its own delete"
        );
        assert!(!delete_library_entry(&metadata, &name, &recent, &test_audit(&root)).expect("second delete"));

        let _ = fs::remove_dir_all(&root);
    }

    /// The library watcher starts before the migration. `metadata/` does not
    /// exist then. When the migration makes it, the page must hear about it.
    #[test]
    fn a_metadata_directory_that_appears_later_is_news() {
        let root = fresh_dir("library-appears");
        let metadata = metadata_dir_in(&root);
        let before = library_signature(&metadata);
        assert!(before.is_empty());

        fs::create_dir_all(&metadata).expect("metadata");
        fs::write(metadata.join(key_name("a")), "{}").expect("entry");
        let after = library_signature(&metadata);
        let changed = changed_records(&before, &after);
        assert_eq!(changed, vec![key_name("a")]);
        assert!(library_change_is_news(&metadata, &changed, &Arc::new(Mutex::new(HashMap::new()))));

        let _ = fs::remove_dir_all(&root);
    }

    // ------------------------------------------------------- the migration

    /// Each file under a directory, with its bytes.
    fn tree_of(root: &Path) -> Vec<(String, Vec<u8>)> {
        fn walk(dir: &Path, root: &Path, out: &mut Vec<(String, Vec<u8>)>) {
            if let Ok(listing) = fs::read_dir(dir) {
                for entry in listing.flatten() {
                    let path = entry.path();
                    if path.is_dir() {
                        walk(&path, root, out);
                    } else {
                        let name = path.strip_prefix(root).unwrap().to_string_lossy().to_string();
                        out.push((name, fs::read(&path).unwrap_or_default()));
                    }
                }
            }
        }
        let mut out = Vec::new();
        walk(root, root, &mut out);
        out.sort();
        out
    }

    fn temp_dirs_in(root: &Path) -> Vec<String> {
        fs::read_dir(root)
            .map(|listing| {
                listing
                    .flatten()
                    .map(|e| e.file_name().to_string_lossy().to_string())
                    .filter(|n| n.starts_with(".metadata-"))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// The migration READS the old records, and nothing more. The directory
    /// stays as it was, so an older desktop shows its library as before.
    #[test]
    fn the_legacy_listing_reads_the_old_records_and_changes_nothing() {
        let root = fresh_dir("legacy-list");
        let legacy = legacy_dir_in(&root);
        fs::create_dir_all(&legacy).expect("diagrams");
        let real = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../local-protocol/fixtures/library-record.json");
        let bytes = fs::read_to_string(&real).expect("fixture");
        fs::write(legacy.join("demo.json"), &bytes).expect("seed");
        fs::write(legacy.join("notes.json"), "{\"id\":{\"value\":\"notes\"}}").expect("seed");
        let before = tree_of(&root);

        let listing = list_legacy_records(&root, &test_audit(&root));
        assert!(!listing.migrated);
        let names: Vec<&str> = listing.records.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, vec!["demo.json", "notes.json"]);
        // Byte for byte: the shell carries the JSON and does not parse it.
        assert_eq!(listing.records[0].json.as_deref(), Some(bytes.as_str()));

        let after: Vec<_> = tree_of(&root).into_iter().filter(|(n, _)| n != "audit.jsonl").collect();
        assert_eq!(after, before, "the legacy listing changed the old directory");
        assert!(!metadata_dir_in(&root).exists(), "the legacy listing made metadata/");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn once_migrated_the_legacy_listing_reads_nothing() {
        let root = fresh_dir("legacy-migrated");
        fs::create_dir_all(legacy_dir_in(&root)).expect("diagrams");
        fs::write(legacy_dir_in(&root).join("old.json"), "{}").expect("seed");
        fs::create_dir_all(metadata_dir_in(&root)).expect("metadata");

        let listing = list_legacy_records(&root, &test_audit(&root));
        assert!(listing.migrated);
        assert!(listing.records.is_empty(), "{:?}", listing.records);

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_library_with_no_old_records_is_not_migrated_and_lists_nothing() {
        let root = fresh_dir("legacy-none");
        let listing = list_legacy_records(&root.join("library"), &test_audit(&root));
        assert!(!listing.migrated);
        assert!(listing.records.is_empty());
        assert!(!root.join("library").exists(), "the legacy listing made library/");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_commit_makes_metadata_with_each_entry_and_no_temporary_directory() {
        let base = fresh_dir("commit");
        let root = base.join("library");
        let entries = vec![
            NamedEntry { name: key_name("a"), json: "{\"path\":\"/a.dot\"}".to_string() },
            NamedEntry { name: key_name("b"), json: "{\"path\":\"/b.dot\"}".to_string() },
        ];

        let commit = commit_migration(&root, &entries, &test_audit(&base)).expect("commit");
        assert_eq!(commit.committed, 2);
        let metadata = metadata_dir_in(&root);
        for entry in &entries {
            assert_eq!(fs::read_to_string(metadata.join(&entry.name)).expect("entry"), entry.json);
        }
        assert!(temp_dirs_in(&root).is_empty(), "a temporary directory stays: {:?}", temp_dirs_in(&root));

        #[cfg(unix)]
        {
            assert_eq!(mode_of(&metadata), 0o700, "metadata/ must be owner-only");
            assert_eq!(mode_of(&metadata.join(&entries[0].name)), 0o600);
        }

        let log = fs::read_to_string(base.join("audit.jsonl")).expect("audit");
        assert!(log.contains("library.migration_committed"), "{log}");

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_commit_with_no_entries_makes_an_empty_store() {
        let root = fresh_dir("commit-empty");
        let commit = commit_migration(&root, &[], &test_audit(&root)).expect("commit");
        assert_eq!(commit.committed, 0);
        assert!(metadata_dir_in(&root).is_dir());
        assert_eq!(fs::read_dir(metadata_dir_in(&root)).expect("list").count(), 0);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_commit_refuses_a_store_that_exists_and_changes_nothing() {
        let root = fresh_dir("commit-twice");
        let metadata = metadata_dir_in(&root);
        fs::create_dir_all(&metadata).expect("metadata");
        fs::write(metadata.join(key_name("mine")), "{\"keep\":true}").expect("entry");
        let before = tree_of(&metadata);

        let err = commit_migration(
            &root,
            &[NamedEntry { name: key_name("other"), json: "{}".to_string() }],
            &test_audit(&root),
        )
        .expect_err("a second commit must be refused");
        assert_eq!(err.code, "LIBRARY_ALREADY_MIGRATED");
        assert_eq!(tree_of(&metadata), before);
        assert!(temp_dirs_in(&root).is_empty());

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_commit_with_a_bad_or_a_repeated_name_writes_nothing() {
        let root = fresh_dir("commit-bad-name");
        for entries in [
            vec![NamedEntry { name: "../escape.json".to_string(), json: "{}".to_string() }],
            vec![
                NamedEntry { name: key_name("a"), json: "{}".to_string() },
                NamedEntry { name: key_name("a"), json: "{\"v\":2}".to_string() },
            ],
        ] {
            let err = commit_migration(&root, &entries, &test_audit(&root))
                .expect_err("a bad name must be refused");
            assert_eq!(err.code, "INVALID_LIBRARY_NAME");
            assert!(!metadata_dir_in(&root).exists());
            assert!(temp_dirs_in(&root).is_empty());
        }
        let _ = fs::remove_dir_all(&root);
    }

    /// A failure before the rename leaves no `metadata/`, so the next start
    /// tries the migration again.
    #[cfg(unix)]
    #[test]
    fn a_failed_commit_leaves_no_metadata() {
        use std::os::unix::fs::PermissionsExt;
        let root = fresh_dir("commit-fails");
        // A library directory that nobody can write: the temporary directory
        // cannot be made.
        fs::set_permissions(&root, fs::Permissions::from_mode(0o500)).expect("chmod");
        let result = commit_migration(
            &root,
            &[NamedEntry { name: key_name("a"), json: "{}".to_string() }],
            &test_audit(&std::env::temp_dir()),
        );
        fs::set_permissions(&root, fs::Permissions::from_mode(0o700)).expect("chmod back");

        let err = result.expect_err("the commit must fail");
        assert_eq!(err.code, "LIBRARY_MIGRATION_FAILED");
        assert!(!metadata_dir_in(&root).exists());
        assert!(temp_dirs_in(&root).is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    // ---------------------------------------- the folder for new diagrams

    /// The folder for new diagrams must be the same folder for gx and for
    /// the desktop. Both suites read the same cases.
    #[test]
    fn the_diagrams_folder_matches_the_shared_fixture() {
        if cfg!(windows) {
            // The fixture has POSIX paths. On Windows, `/tmp/x` is not absolute.
            return;
        }
        let doc = fixture("diagrams-dir.json");
        let home = PathBuf::from(doc["home"].as_str().expect("home"));
        let cases = doc["diagramsDir"].as_array().expect("an array");
        assert!(cases.len() >= 13, "the fixture set should not have shrunk");

        for case in cases {
            let name = case["name"].as_str().unwrap_or("?");
            let env_value = case["env"].as_str().map(std::ffi::OsString::from);
            let result = diagrams_dir_from(env_value, Some(home.clone()));
            match (case.get("expect").and_then(|e| e.as_str()), case.get("error")) {
                (Some(expect), _) => {
                    let got = result.unwrap_or_else(|e| panic!("[{name}] refused: {e}"));
                    assert_eq!(got, PathBuf::from(expect), "[{name}]");
                }
                (None, Some(parts)) => {
                    let message = result
                        .expect_err(&format!("[{name}] should have been refused"))
                        .to_string();
                    for part in parts.as_array().expect("error parts") {
                        let part = part.as_str().expect("a string");
                        assert!(message.contains(part), "[{name}] the message lacks {part:?}: {message}");
                    }
                }
                _ => panic!("[{name}] has neither expect nor error"),
            }
        }
    }

    #[test]
    fn the_diagrams_folder_gives_its_canonical_path_and_the_names_in_it() {
        let root = fresh_dir("diagrams-folder");
        let folder = root.join("Graph Explorer");

        // A folder that does not exist yet: the canonical path, and no names.
        let empty = diagrams_folder_listing(&no_policy(), &folder).expect("listing");
        assert_eq!(empty.path, folder.to_string_lossy());
        assert!(empty.existing.is_empty());
        assert!(!folder.exists(), "the listing made the folder");

        fs::create_dir_all(folder.join("Recovered")).expect("recovered");
        fs::write(folder.join("Notes.dot"), "digraph { n }").expect("notes");
        fs::write(folder.join("Recovered").join("Arch.dot"), "digraph { a }").expect("arch");
        let listing = diagrams_folder_listing(&no_policy(), &folder).expect("listing");
        assert_eq!(listing.existing, vec!["Notes.dot", "Recovered", "Recovered/Arch.dot"]);

        // A spelling with a `..` gives the same canonical path.
        let other = diagrams_folder_listing(&no_policy(), &folder.join("Recovered").join(".."))
            .expect("listing");
        assert_eq!(other.path, folder.to_string_lossy());

        let err = diagrams_folder_listing(&denying(&root), &folder).expect_err("denied");
        assert_eq!(err.code, "DOCUMENT_DENIED");

        let _ = fs::remove_dir_all(&root);
    }

    // ------------------------------------------------------ diagram files

    #[test]
    fn read_document_gives_the_text_and_the_revision_with_no_watch() {
        // `read_document_at` gets no watch registry, so it cannot start a
        // watch. The home page reads each diagram in the library this way.
        let root = fresh_dir("read-document");
        let file = root.join("a.mmd");
        fs::write(&file, "flowchart LR\r\n  a --> b").expect("seed");

        let snapshot = read_document_at(&no_policy(), &test_audit(&root), &file.to_string_lossy())
            .expect("read");
        assert_eq!(snapshot.path, file.to_string_lossy());
        assert_eq!(snapshot.text, "flowchart LR\r\n  a --> b");
        assert_eq!(snapshot.revision, content_hash(b"flowchart LR\r\n  a --> b"));
        assert_eq!(snapshot.format, "mermaid");

        let log = fs::read_to_string(root.join("audit.jsonl")).expect("audit");
        assert!(log.contains("document.read"), "{log}");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn read_document_names_each_refusal() {
        let root = fresh_dir("read-document-refused");
        let audit = test_audit(&root);

        let missing = read_document_at(&no_policy(), &audit, &root.join("gone.dot").to_string_lossy())
            .expect_err("missing");
        assert_eq!(missing.code, "DOCUMENT_MISSING");

        fs::create_dir_all(root.join("dir.dot")).expect("dir");
        let not_a_file = read_document_at(&no_policy(), &audit, &root.join("dir.dot").to_string_lossy())
            .expect_err("a directory");
        assert_eq!(not_a_file.code, "DOCUMENT_READ_FAILED");

        let secret = root.join("secret");
        fs::create_dir_all(&secret).expect("secret");
        fs::write(secret.join("a.dot"), "digraph { s }").expect("seed");
        let denied = read_document_at(&denying(&secret), &audit, &secret.join("a.dot").to_string_lossy())
            .expect_err("denied");
        assert_eq!(denied.code, "DOCUMENT_DENIED");
        // The policy comes first, so a refusal tells nothing about existence.
        let denied_missing =
            read_document_at(&denying(&secret), &audit, &secret.join("none.dot").to_string_lossy())
                .expect_err("denied");
        assert_eq!(denied_missing.code, "DOCUMENT_DENIED");

        let log = fs::read_to_string(root.join("audit.jsonl")).expect("audit");
        assert!(log.contains("rejected"), "{log}");

        let _ = fs::remove_dir_all(&root);
    }

    fn files_named_like_temps(dir: &Path) -> Vec<String> {
        fs::read_dir(dir)
            .map(|listing| {
                listing
                    .flatten()
                    .map(|e| e.file_name().to_string_lossy().to_string())
                    .filter(|n| n.ends_with(".tmp"))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// V-04: the bytes are the text. A create keeps CRLF, and keeps a file
    /// with no final newline as it is.
    #[test]
    fn create_document_writes_the_text_byte_for_byte_into_new_folders() {
        let root = fresh_dir("create-document");
        let file = root.join("Graph Explorer").join("Recovered").join("Untitled.dot");
        let text = "digraph G {\r\n  a -> b\r\n}";

        let snapshot = create_document_at(&no_policy(), &test_audit(&root), &file.to_string_lossy(), text)
            .expect("create");
        assert_eq!(fs::read(&file).expect("read"), text.as_bytes());
        assert_eq!(snapshot.revision, content_hash(text.as_bytes()));
        assert_eq!(snapshot.path, file.to_string_lossy());
        assert_eq!(snapshot.text, text);
        assert!(
            files_named_like_temps(file.parent().unwrap()).is_empty(),
            "a temporary file stays: {:?}",
            files_named_like_temps(file.parent().unwrap())
        );
        let log = fs::read_to_string(root.join("audit.jsonl")).expect("audit");
        assert!(log.contains("document.created"), "{log}");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn create_document_never_writes_over_a_file_that_exists() {
        let root = fresh_dir("create-document-exists");
        let file = root.join("Untitled.dot");
        fs::write(&file, "digraph { mine }").expect("seed");

        let err = create_document_at(&no_policy(), &test_audit(&root), &file.to_string_lossy(), "digraph {}")
            .expect_err("an existing file must be refused");
        assert_eq!(err.code, "DOCUMENT_EXISTS");
        assert_eq!(fs::read_to_string(&file).expect("read"), "digraph { mine }");
        assert!(files_named_like_temps(&root).is_empty());

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn create_document_refuses_a_denied_path_and_writes_nothing() {
        let root = fresh_dir("create-document-denied");
        let secret = root.join("secret");
        fs::create_dir_all(&secret).expect("secret");
        let file = secret.join("a.dot");

        let err = create_document_at(&denying(&secret), &test_audit(&root), &file.to_string_lossy(), "x")
            .expect_err("denied");
        assert_eq!(err.code, "DOCUMENT_DENIED");
        assert!(!file.exists());

        let _ = fs::remove_dir_all(&root);
    }

    /// V-03 for a file that did not exist: the default of the umask, not the
    /// owner-only mode of a temporary file.
    #[cfg(unix)]
    #[test]
    fn create_document_gives_a_new_file_the_umask_default() {
        let root = fresh_dir("create-document-mode");
        let reference = root.join("reference.dot");
        fs::write(&reference, "x").expect("reference");
        let file = root.join("new.dot");

        create_document_at(&no_policy(), &test_audit(&root), &file.to_string_lossy(), "digraph {}")
            .expect("create");
        assert_eq!(mode_of(&file), mode_of(&reference));

        let _ = fs::remove_dir_all(&root);
    }

    fn fake_spawn() -> impl Fn(&str) -> WatchController {
        |_path: &str| {
            let (stop_tx, _stop_rx) = std::sync::mpsc::channel::<()>();
            WatchController { stop_tx }
        }
    }

    fn empty_registries() -> (WatchRegistry, WatchControllers, RecentWriteHashes) {
        (
            Arc::new(Mutex::new(HashMap::new())),
            Arc::new(Mutex::new(HashMap::new())),
            Arc::new(Mutex::new(HashMap::new())),
        )
    }

    #[test]
    fn rename_document_moves_the_file_and_keeps_its_bytes() {
        let root = fresh_dir("rename-document");
        let from = root.join("Untitled.dot");
        let to = root.join("Architecture.dot");
        fs::write(&from, "digraph G {\r\n  a\r\n}").expect("seed");
        let (registry, controllers, recent) = empty_registries();

        let outcome = rename_document_at(
            &no_policy(),
            &test_audit(&root),
            &registry,
            &controllers,
            &recent,
            &from.to_string_lossy(),
            &to.to_string_lossy(),
            &fake_spawn(),
        )
        .expect("rename");
        assert!(!from.exists(), "the old name stays");
        assert_eq!(fs::read(&to).expect("read"), b"digraph G {\r\n  a\r\n}");
        assert_eq!(outcome.path, to.to_string_lossy());
        assert_eq!(outcome.revision, content_hash(b"digraph G {\r\n  a\r\n}"));
        assert!(!outcome.watch_moved, "a file with no watch has no watch to move");
        assert!(registry.lock().unwrap().is_empty(), "a rename must not start a watch");

        let log = fs::read_to_string(root.join("audit.jsonl")).expect("audit");
        assert!(log.contains("document.renamed"), "{log}");

        let _ = fs::remove_dir_all(&root);
    }

    /// V-03: a rename keeps the bits of the file.
    #[cfg(unix)]
    #[test]
    fn rename_document_keeps_the_mode_of_the_file() {
        use std::os::unix::fs::PermissionsExt;
        let root = fresh_dir("rename-document-mode");
        let from = root.join("a.dot");
        let to = root.join("b.dot");
        fs::write(&from, "digraph {}").expect("seed");
        fs::set_permissions(&from, fs::Permissions::from_mode(0o640)).expect("chmod");
        let (registry, controllers, recent) = empty_registries();

        rename_document_at(
            &no_policy(), &test_audit(&root), &registry, &controllers, &recent,
            &from.to_string_lossy(), &to.to_string_lossy(), &fake_spawn(),
        )
        .expect("rename");
        assert_eq!(mode_of(&to), 0o640);

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn rename_document_never_writes_over_a_file_that_exists() {
        let root = fresh_dir("rename-document-exists");
        let from = root.join("a.dot");
        let to = root.join("b.dot");
        fs::write(&from, "digraph { a }").expect("a");
        fs::write(&to, "digraph { b }").expect("b");
        let (registry, controllers, recent) = empty_registries();

        let err = rename_document_at(
            &no_policy(), &test_audit(&root), &registry, &controllers, &recent,
            &from.to_string_lossy(), &to.to_string_lossy(), &fake_spawn(),
        )
        .expect_err("an existing name must be refused");
        assert_eq!(err.code, "DOCUMENT_EXISTS");
        assert_eq!(fs::read_to_string(&from).expect("a"), "digraph { a }");
        assert_eq!(fs::read_to_string(&to).expect("b"), "digraph { b }");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn rename_document_refuses_a_missing_file_and_a_denied_name() {
        let root = fresh_dir("rename-document-refused");
        let (registry, controllers, recent) = empty_registries();
        let audit = test_audit(&root);

        let missing = rename_document_at(
            &no_policy(), &audit, &registry, &controllers, &recent,
            &root.join("gone.dot").to_string_lossy(), &root.join("new.dot").to_string_lossy(), &fake_spawn(),
        )
        .expect_err("missing");
        assert_eq!(missing.code, "DOCUMENT_MISSING");

        let secret = root.join("secret");
        fs::create_dir_all(&secret).expect("secret");
        let from = root.join("a.dot");
        fs::write(&from, "digraph { a }").expect("a");
        let denied = rename_document_at(
            &denying(&secret), &audit, &registry, &controllers, &recent,
            &from.to_string_lossy(), &secret.join("a.dot").to_string_lossy(), &fake_spawn(),
        )
        .expect_err("denied");
        assert_eq!(denied.code, "DOCUMENT_DENIED");
        assert_eq!(fs::read_to_string(&from).expect("a"), "digraph { a }", "the file must stay");
        assert!(!secret.join("a.dot").exists());

        let _ = fs::remove_dir_all(&root);
    }

    /// The watch of a renamed file follows the new path, and the shell sends
    /// no document event: the page moves its session itself.
    #[test]
    fn rename_document_moves_the_watch_to_the_new_path() {
        let root = fresh_dir("rename-document-watch");
        let from = root.join("a.dot");
        let to = root.join("b.mmd");
        fs::write(&from, "flowchart LR\n  a --> b\n").expect("seed");
        let from_key = from.to_string_lossy().to_string();
        let to_key = to.to_string_lossy().to_string();
        let (registry, controllers, recent) = empty_registries();

        registry.lock().unwrap().insert(
            from_key.clone(),
            WatchDescriptor {
                path: from_key.clone(),
                format: "dot".to_string(),
                revision: "old".to_string(),
            },
        );
        let (old_stop_tx, old_stop_rx) = std::sync::mpsc::channel::<()>();
        controllers.lock().unwrap().insert(from_key.clone(), WatchController { stop_tx: old_stop_tx });
        recent.lock().unwrap().insert(from_key.clone(), "a hash".to_string());

        let spawned: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let spawned_in = spawned.clone();
        let spawn = move |path: &str| {
            spawned_in.lock().unwrap().push(path.to_string());
            let (stop_tx, _stop_rx) = std::sync::mpsc::channel::<()>();
            WatchController { stop_tx }
        };

        let outcome = rename_document_at(
            &no_policy(), &test_audit(&root), &registry, &controllers, &recent,
            &from_key, &to_key, &spawn,
        )
        .expect("rename");
        assert!(outcome.watch_moved);

        {
            let registry = registry.lock().unwrap();
            assert!(registry.get(&from_key).is_none(), "the old path is still watched");
            let moved = registry.get(&to_key).expect("the new path is watched");
            assert_eq!(moved.revision, content_hash(b"flowchart LR\n  a --> b\n"));
            assert_eq!(moved.format, "mermaid");
        }
        assert!(old_stop_rx.try_recv().is_ok(), "the old watch loop was not stopped");
        assert_eq!(*spawned.lock().unwrap(), vec![to_key.clone()]);
        {
            let controllers = controllers.lock().unwrap();
            assert!(controllers.contains_key(&to_key) && !controllers.contains_key(&from_key));
        }
        assert!(recent.lock().unwrap().get(&from_key).is_none());

        let _ = fs::remove_dir_all(&root);
    }

    /// On a file system that ignores case, `a.dot` and `A.dot` are one file.
    /// A rename that changes only the case must work, and not be refused as
    /// a name that exists.
    #[test]
    fn rename_document_can_change_only_the_case() {
        if !filesystem_is_case_insensitive() {
            return;
        }
        let root = fresh_dir("rename-document-case");
        let from = root.join("notes.dot");
        fs::write(&from, "digraph { n }").expect("seed");
        let (registry, controllers, recent) = empty_registries();

        let outcome = rename_document_at(
            &no_policy(), &test_audit(&root), &registry, &controllers, &recent,
            &from.to_string_lossy(), &root.join("Notes.dot").to_string_lossy(), &fake_spawn(),
        )
        .expect("a case-only rename");
        let names: Vec<String> = fs::read_dir(&root)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().to_string())
            .filter(|n| n.ends_with(".dot"))
            .collect();
        assert_eq!(names, vec!["Notes.dot"]);
        assert!(outcome.path.ends_with("Notes.dot"), "{}", outcome.path);

        let _ = fs::remove_dir_all(&root);
    }

    /// `show` has one target kind now. `gx` no longer sends `library`, and a
    /// frame with it is a request error, not a hang and not a display.
    #[test]
    fn a_show_with_a_library_target_is_an_invalid_request() {
        let dir = std::env::temp_dir();
        let frame = r#"{"id":1,"method":"show","params":{"target":{"kind":"library","diagramId":"arch"}}}"#;
        let response = dispatch_frame(frame, &starting_context(&dir));
        let value = serde_json::to_value(&response).expect("serializable");
        assert_eq!(value["ok"], serde_json::json!(false));
        assert_eq!(value["error"]["code"], serde_json::json!("INVALID_REQUEST"));
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
