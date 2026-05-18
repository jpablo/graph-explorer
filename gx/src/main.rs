use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::ExitCode;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use serde::{Deserialize, Serialize};

const EXIT_OK: u8 = 0;
const EXIT_DESKTOP_UNREACHABLE: u8 = 2;
const EXIT_AUTH_FAILURE: u8 = 3;
const EXIT_INVALID_PATH_OR_PERMISSION: u8 = 4;
const EXIT_CONFLICT: u8 = 5;
const EXIT_UNKNOWN: u8 = 6;

#[derive(Debug, Parser)]
#[command(name = "gx", version, about = "Graph Explorer automation CLI")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Subcommand)]
enum Commands {
    /// Report desktop runtime status
    Status {
        /// Emit machine-readable JSON
        #[arg(long)]
        json: bool,
    },
    /// Register a file for desktop watch lifecycle
    Watch {
        path: String,
        #[arg(long, default_value_t = true)]
        open: bool,
        #[arg(long)]
        json: bool,
    },
    /// Unregister a watched file
    Unwatch {
        path: String,
        #[arg(long)]
        json: bool,
    },
    /// Read a watched document snapshot
    Get {
        #[arg(long = "file")]
        file: String,
        #[arg(long)]
        json: bool,
    },
    /// Write text to a watched document with revision safety
    Set {
        #[arg(long = "file")]
        file: String,
        #[arg(long)]
        stdin: bool,
        #[arg(long)]
        text: Option<String>,
        #[arg(long = "base-revision")]
        base_revision: Option<u64>,
        #[arg(long)]
        json: bool,
    },
}

#[derive(Debug, Deserialize)]
struct ControlFile {
    pid: u32,
    port: u16,
    token: String,
    version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct WatchDescriptor {
    path: String,
    format: String,
    revision: u64,
}

#[derive(Debug, Serialize)]
struct StatusResponse {
    ok: bool,
    running: bool,
    code: &'static str,
    message: String,
    runtime_file: String,
    pid: Option<u32>,
    port: Option<u16>,
    version: Option<String>,
    watches: Vec<WatchDescriptor>,
}

#[derive(Debug, Deserialize)]
struct DesktopStatusBody {
    ok: bool,
    running: bool,
    version: String,
    pid: u32,
    port: u16,
    watches: Vec<WatchDescriptor>,
}

#[derive(Debug, Serialize, Deserialize)]
struct WatchResponseBody {
    ok: bool,
    watch: WatchDescriptor,
}

#[derive(Debug, Serialize, Deserialize)]
struct UnwatchResponseBody {
    ok: bool,
    removed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DocumentSnapshot {
    path: String,
    text: String,
    format: String,
    revision: u64,
    #[serde(rename = "timestampMs")]
    timestamp_ms: u64,
}

#[derive(Debug, Deserialize)]
struct GetDocumentResponseBody {
    #[serde(rename = "ok")]
    _ok: bool,
    document: DocumentSnapshot,
}

#[derive(Debug, Deserialize)]
struct SetDocumentResponseBody {
    #[serde(rename = "ok")]
    _ok: bool,
    document: DocumentSnapshot,
}

#[derive(Debug, Serialize)]
struct ErrorStatus {
    ok: bool,
    code: &'static str,
    message: String,
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let result = match cli.command {
        Commands::Status { json } => run_status(json),
        Commands::Watch { path, open, json } => run_watch(&path, open, json),
        Commands::Unwatch { path, json } => run_unwatch(&path, json),
        Commands::Get { file, json } => run_get(&file, json),
        Commands::Set {
            file,
            stdin,
            text,
            base_revision,
            json,
        } => run_set(&file, stdin, text, base_revision, json),
    };

    match result {
        Ok(code) => ExitCode::from(code),
        Err(err) => {
            eprintln!("gx error: {err:#}");
            ExitCode::from(EXIT_UNKNOWN)
        }
    }
}

fn run_status(json: bool) -> Result<u8> {
    let runtime_file = runtime_file_path().context("failed to compute runtime path")?;
    let runtime_file_display = runtime_file.display().to_string();
    let control = match read_control_file(&runtime_file) {
        Ok(value) => value,
        Err(_) => {
            let status = StatusResponse {
                ok: false,
                running: false,
                code: "DESKTOP_NOT_RUNNING",
                message: "Desktop runtime file was not found. Start graph-explorer-desktop first."
                    .to_string(),
                runtime_file: runtime_file_display,
                pid: None,
                port: None,
                version: None,
                watches: Vec::new(),
            };
            print_status(&status, json)?;
            return Ok(EXIT_DESKTOP_UNREACHABLE);
        }
    };

    match desktop_status(&control) {
        Ok(desktop) => {
            let status = StatusResponse {
                ok: desktop.ok,
                running: desktop.running,
                code: "OK",
                message: "Desktop runtime is reachable.".to_string(),
                runtime_file: runtime_file_display,
                pid: Some(desktop.pid),
                port: Some(desktop.port),
                version: Some(desktop.version),
                watches: desktop.watches,
            };
            print_status(&status, json)?;
            Ok(EXIT_OK)
        }
        Err(err) => {
            let status = StatusResponse {
                ok: false,
                running: false,
                code: "DESKTOP_UNREACHABLE",
                message: format!(
                    "Runtime file exists but desktop control API is not reachable: {err}"
                ),
                runtime_file: runtime_file_display,
                pid: Some(control.pid),
                port: Some(control.port),
                version: Some(control.version),
                watches: Vec::new(),
            };
            print_status(&status, json)?;
            Ok(EXIT_DESKTOP_UNREACHABLE)
        }
    }
}

/// Resolve a user-supplied path to an absolute, canonical string.
///
/// `gx` runs in the user's shell, but the desktop resolves paths against its
/// own working directory — so a relative path fails ("rejected by desktop").
/// Canonicalize here (against gx's cwd) so any cwd-relative path works and
/// matches how the desktop normalizes paths; a missing file fails fast with a
/// clear message instead of an opaque INVALID_REQUEST.
fn resolve_path(path: &str) -> Result<String> {
    let canon = fs::canonicalize(path)
        .map_err(|err| anyhow::anyhow!("cannot resolve path '{path}': {err}"))?;
    canon
        .to_str()
        .map(str::to_string)
        .ok_or_else(|| anyhow::anyhow!("path is not valid UTF-8: {}", canon.display()))
}

fn run_watch(path: &str, open_in_ui: bool, json: bool) -> Result<u8> {
    let resolved = resolve_path(path)?;
    let path = resolved.as_str();
    let control = read_control_file(&runtime_file_path()?)?;
    let endpoint = format!("{}/v1/watch", control_base_url(&control));
    let payload = serde_json::json!({
        "path": path,
        "openInUi": open_in_ui
    });

    match ureq::post(&endpoint)
        .header("Authorization", &format!("Bearer {}", control.token))
        .send_json(payload)
    {
        Ok(response) => {
            let body: WatchResponseBody = response
                .into_body()
                .read_json()
                .map_err(|err| anyhow::anyhow!("failed to parse watch response: {err}"))?;

            if json {
                println!("{}", serde_json::to_string_pretty(&body.watch)?);
            } else {
                println!("watch: {}", body.watch.path);
                println!("format: {}", body.watch.format);
                println!("revision: {}", body.watch.revision);
            }
            Ok(if body.ok { EXIT_OK } else { EXIT_UNKNOWN })
        }
        Err(ureq::Error::StatusCode(401)) => {
            print_error(
                "AUTH_FAILURE",
                "Desktop auth failed. Token may be stale.",
                json,
            )?;
            Ok(EXIT_AUTH_FAILURE)
        }
        Err(ureq::Error::StatusCode(400)) => {
            print_error(
                "INVALID_REQUEST",
                "Watch request was rejected by desktop.",
                json,
            )?;
            Ok(EXIT_INVALID_PATH_OR_PERMISSION)
        }
        Err(err) => {
            print_error(
                "DESKTOP_UNREACHABLE",
                &format!("Desktop request failed: {err}"),
                json,
            )?;
            Ok(EXIT_DESKTOP_UNREACHABLE)
        }
    }
}

fn run_unwatch(path: &str, json: bool) -> Result<u8> {
    let resolved = resolve_path(path)?;
    let path = resolved.as_str();
    let control = read_control_file(&runtime_file_path()?)?;
    let endpoint = format!("{}/v1/unwatch", control_base_url(&control));
    let payload = serde_json::json!({
        "path": path
    });

    match ureq::post(&endpoint)
        .header("Authorization", &format!("Bearer {}", control.token))
        .send_json(payload)
    {
        Ok(response) => {
            let body: UnwatchResponseBody = response
                .into_body()
                .read_json()
                .map_err(|err| anyhow::anyhow!("failed to parse unwatch response: {err}"))?;
            if json {
                println!("{}", serde_json::to_string_pretty(&body)?);
            } else {
                println!("removed: {}", body.removed);
            }
            Ok(if body.ok { EXIT_OK } else { EXIT_UNKNOWN })
        }
        Err(ureq::Error::StatusCode(401)) => {
            print_error(
                "AUTH_FAILURE",
                "Desktop auth failed. Token may be stale.",
                json,
            )?;
            Ok(EXIT_AUTH_FAILURE)
        }
        Err(ureq::Error::StatusCode(400)) => {
            print_error(
                "INVALID_REQUEST",
                "Unwatch request was rejected by desktop.",
                json,
            )?;
            Ok(EXIT_INVALID_PATH_OR_PERMISSION)
        }
        Err(err) => {
            print_error(
                "DESKTOP_UNREACHABLE",
                &format!("Desktop request failed: {err}"),
                json,
            )?;
            Ok(EXIT_DESKTOP_UNREACHABLE)
        }
    }
}

fn run_get(path: &str, json: bool) -> Result<u8> {
    let resolved = resolve_path(path)?;
    let path = resolved.as_str();
    let control = read_control_file(&runtime_file_path()?)?;
    match get_document(&control, path) {
        Ok(document) => {
            if json {
                println!("{}", serde_json::to_string_pretty(&document)?);
            } else {
                println!("{}", document.text);
            }
            Ok(EXIT_OK)
        }
        Err(ureq::Error::StatusCode(401)) => {
            print_error(
                "AUTH_FAILURE",
                "Desktop auth failed. Token may be stale.",
                json,
            )?;
            Ok(EXIT_AUTH_FAILURE)
        }
        Err(ureq::Error::StatusCode(400)) => {
            print_error(
                "INVALID_REQUEST",
                "Document read was rejected by desktop.",
                json,
            )?;
            Ok(EXIT_INVALID_PATH_OR_PERMISSION)
        }
        Err(err) => {
            print_error(
                "DESKTOP_UNREACHABLE",
                &format!("Desktop request failed: {err}"),
                json,
            )?;
            Ok(EXIT_DESKTOP_UNREACHABLE)
        }
    }
}

fn run_set(
    path: &str,
    stdin: bool,
    text_arg: Option<String>,
    base_revision: Option<u64>,
    json: bool,
) -> Result<u8> {
    let resolved = resolve_path(path)?;
    let path = resolved.as_str();
    let control = read_control_file(&runtime_file_path()?)?;
    let text = resolve_set_text(stdin, text_arg)?;
    let base = if let Some(rev) = base_revision {
        rev
    } else {
        get_document(&control, path)?.revision
    };

    match put_document(&control, path, &text, base) {
        Ok(document) => {
            if json {
                println!("{}", serde_json::to_string_pretty(&document)?);
            } else {
                println!("path: {}", document.path);
                println!("revision: {}", document.revision);
            }
            Ok(EXIT_OK)
        }
        Err(ureq::Error::StatusCode(401)) => {
            print_error(
                "AUTH_FAILURE",
                "Desktop auth failed. Token may be stale.",
                json,
            )?;
            Ok(EXIT_AUTH_FAILURE)
        }
        Err(ureq::Error::StatusCode(409)) => {
            print_error(
                "DOCUMENT_CONFLICT",
                "Document write conflict: base revision is stale.",
                json,
            )?;
            Ok(EXIT_CONFLICT)
        }
        Err(ureq::Error::StatusCode(400)) => {
            print_error(
                "INVALID_REQUEST",
                "Document write was rejected by desktop.",
                json,
            )?;
            Ok(EXIT_INVALID_PATH_OR_PERMISSION)
        }
        Err(err) => {
            print_error(
                "DESKTOP_UNREACHABLE",
                &format!("Desktop request failed: {err}"),
                json,
            )?;
            Ok(EXIT_DESKTOP_UNREACHABLE)
        }
    }
}

fn resolve_set_text(stdin: bool, text_arg: Option<String>) -> Result<String> {
    if stdin {
        let mut buffer = String::new();
        std::io::stdin()
            .read_to_string(&mut buffer)
            .context("failed to read stdin")?;
        return Ok(buffer);
    }

    if let Some(text) = text_arg {
        return Ok(text);
    }

    Err(anyhow::anyhow!(
        "missing input text; provide --stdin or --text"
    ))
}

fn runtime_file_path() -> Result<PathBuf> {
    let home = dirs::home_dir().context("home directory not available")?;
    Ok(home
        .join(".graph-explorer")
        .join("runtime")
        .join("control.json"))
}

fn read_control_file(path: &PathBuf) -> Result<ControlFile> {
    let content =
        fs::read_to_string(path).with_context(|| format!("failed to read {}", path.display()))?;
    serde_json::from_str(&content).with_context(|| format!("failed to parse {}", path.display()))
}

fn control_base_url(control: &ControlFile) -> String {
    format!("http://127.0.0.1:{}", control.port)
}

fn desktop_status(control: &ControlFile) -> Result<DesktopStatusBody> {
    let endpoint = format!("{}/v1/status", control_base_url(control));
    let response = ureq::get(&endpoint)
        .header("Authorization", &format!("Bearer {}", control.token))
        .call()?;
    response
        .into_body()
        .read_json()
        .map_err(|err| anyhow::anyhow!("failed to parse status response: {err}"))
}

fn get_document(
    control: &ControlFile,
    path: &str,
) -> std::result::Result<DocumentSnapshot, ureq::Error> {
    let endpoint = format!(
        "{}/v1/document?path={}",
        control_base_url(control),
        urlencoding::encode(path)
    );
    let response = ureq::get(&endpoint)
        .header("Authorization", &format!("Bearer {}", control.token))
        .call()?;
    let body: GetDocumentResponseBody = response.into_body().read_json()?;
    Ok(body.document)
}

fn put_document(
    control: &ControlFile,
    path: &str,
    text: &str,
    base_revision: u64,
) -> std::result::Result<DocumentSnapshot, ureq::Error> {
    let endpoint = format!("{}/v1/document", control_base_url(control));
    let payload = serde_json::json!({
        "path": path,
        "text": text,
        "baseRevision": base_revision,
        "source": "cli"
    });

    let response = ureq::put(&endpoint)
        .header("Authorization", &format!("Bearer {}", control.token))
        .send_json(payload)?;
    let body: SetDocumentResponseBody = response.into_body().read_json()?;
    Ok(body.document)
}

fn print_status(status: &StatusResponse, json: bool) -> Result<()> {
    if json {
        println!("{}", serde_json::to_string_pretty(status)?);
        return Ok(());
    }

    if status.ok {
        println!("status: running");
        if let Some(pid) = status.pid {
            println!("pid: {pid}");
        }
        if let Some(port) = status.port {
            println!("port: {port}");
        }
        println!("runtime_file: {}", status.runtime_file);
        println!("watches: {}", status.watches.len());
        return Ok(());
    }

    println!("status: not-running");
    println!("code: {}", status.code);
    println!("message: {}", status.message);
    println!("runtime_file: {}", status.runtime_file);
    Ok(())
}

fn print_error(code: &'static str, message: &str, json: bool) -> Result<()> {
    if json {
        println!(
            "{}",
            serde_json::to_string_pretty(&ErrorStatus {
                ok: false,
                code,
                message: message.to_string(),
            })?
        );
    } else {
        println!("error: {code}");
        println!("message: {message}");
    }
    Ok(())
}
