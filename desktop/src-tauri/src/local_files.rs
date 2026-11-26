//! The files next to a diagram.
//!
//! - An image that a person pastes or drops on a diagram with a file goes
//!   into a folder next to the diagram: `<dir>/<name>.assets/`.
//! - The page draws an image of a diagram from a path relative to the
//!   diagram, through the asset protocol.
//! - A command-click on a node link opens a local file with the default app.
//!
//! A diagram can come from a different person, and the page can call each
//! command. Thus each function here checks its input again.

use std::fs;
use std::fs::OpenOptions;
use std::path::{Path, PathBuf};

use crate::{content_hash, ensure_path_permitted, normalize_path, write_and_sync, AccessPolicy, IpcError};

/// The extensions of an image that the page can draw and save.
pub(crate) const IMAGE_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"];

/// The largest image that a paste or a drop saves.
pub(crate) const MAX_ASSET_BYTES: usize = 64 * 1024 * 1024;

/// The number of hex characters of the content hash in the name of an asset.
const SHORT_HASH: usize = 12;

/// The bytes of the start of an image that `local_image` gives the page. The
/// header of most images is in the first 32 bytes. A JPEG can have a large
/// EXIF block before its size.
pub(crate) const IMAGE_HEAD_BYTES: usize = 256 * 1024;

/// The diagram file at `diagram_path`: its canonical path, its folder, and
/// its name with no extension.
fn diagram_file(diagram_path: &str) -> std::result::Result<(PathBuf, PathBuf, String), IpcError> {
    let missing = || IpcError::new("DOCUMENT_MISSING", format!("{diagram_path} is not a diagram file"));
    let normalized = normalize_path(diagram_path).map_err(|err| IpcError::new("DOCUMENT_MISSING", err))?;
    let path = PathBuf::from(normalized);
    match fs::metadata(&path) {
        Ok(meta) if meta.is_file() => {}
        _ => return Err(missing()),
    }
    let dir = path.parent().map(Path::to_path_buf).ok_or_else(missing)?;
    let stem = path
        .file_stem()
        .and_then(|s| s.to_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(missing)?
        .to_string();
    Ok((path, dir, stem))
}

fn permitted(access_policy: &AccessPolicy, path: &Path) -> std::result::Result<(), IpcError> {
    ensure_path_permitted(access_policy, path).map_err(|err| IpcError::new("DOCUMENT_DENIED", err))
}

/// True for an extension of [`IMAGE_EXTENSIONS`], in lower case and with no
/// other character. Thus an extension cannot hold a separator or a `.`.
fn is_image_extension(ext: &str) -> bool {
    IMAGE_EXTENSIONS.contains(&ext)
}

fn extension_of(path: &Path) -> Option<String> {
    path.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase())
}

/// Save `bytes` as an asset of the diagram at `diagram_path`, and give the
/// path of the asset relative to the folder of the diagram, with `/`.
///
/// The asset is `<dir>/<name>.assets/img-<hash>.<ext>`, where `<hash>` is the
/// start of the SHA-256 of the bytes. Thus the same bytes give the same file,
/// and a second paste of an image writes nothing. A file with that name and
/// different bytes stays unchanged: the write then uses the whole hash, and
/// it refuses when that name also has different bytes.
///
/// The write never replaces a file. The bytes go into a temporary file in the
/// folder, and a hard link gives the name, as `create_document_at` does.
pub(crate) fn write_asset_at(
    access_policy: &AccessPolicy,
    diagram_path: &str,
    ext: &str,
    bytes: &[u8],
) -> std::result::Result<String, IpcError> {
    let refused = |why: String| IpcError::new("ASSET_REFUSED", why);
    if !is_image_extension(ext) {
        return Err(refused(format!("{ext:?} is not the extension of an image")));
    }
    if bytes.is_empty() {
        return Err(refused("the image has no bytes".to_string()));
    }
    if bytes.len() > MAX_ASSET_BYTES {
        return Err(refused(format!("the image has more than {MAX_ASSET_BYTES} bytes")));
    }
    let (_, dir, stem) = diagram_file(diagram_path)?;
    let folder_name = format!("{stem}.assets");
    let folder = dir.join(&folder_name);
    permitted(access_policy, &folder)?;
    let write_failed = |why: String| IpcError::new("ASSET_WRITE_FAILED", format!("{}: {why}", folder.display()));

    // `symlink_metadata`, so that a link with the name of the folder counts as
    // a link. A write through it would put the file at a different place.
    match fs::symlink_metadata(&folder) {
        Ok(meta) if meta.is_dir() => {}
        Ok(_) => return Err(refused(format!("{} is not a folder", folder.display()))),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
            match fs::create_dir(&folder) {
                Ok(()) => {}
                Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(err) => return Err(write_failed(err.to_string())),
            }
        }
        Err(err) => return Err(write_failed(err.to_string())),
    }

    let hash = content_hash(bytes);
    for name in [format!("img-{}.{ext}", &hash[..SHORT_HASH]), format!("img-{hash}.{ext}")] {
        let target = folder.join(&name);
        let relative = format!("{folder_name}/{name}");
        if holds(&target, bytes) {
            return Ok(relative);
        }
        if fs::symlink_metadata(&target).is_ok() {
            // A different file has the name. It stays unchanged.
            continue;
        }
        match create_new_file(&folder, &target, bytes) {
            Ok(true) => return Ok(relative),
            // A different writer made the file in the meantime.
            Ok(false) if holds(&target, bytes) => return Ok(relative),
            Ok(false) => continue,
            Err(err) => return Err(write_failed(err.to_string())),
        }
    }
    Err(IpcError::new(
        "ASSET_EXISTS",
        format!("{} has different files with the names of this image", folder.display()),
    ))
}

/// True when `path` is a regular file (not a link) with exactly `bytes`.
fn holds(path: &Path, bytes: &[u8]) -> bool {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.is_file() && meta.len() == bytes.len() as u64 => {
            fs::read(path).map(|on_disk| on_disk == bytes).unwrap_or(false)
        }
        _ => false,
    }
}

/// Make the file `target` with `bytes`. False when the name exists. The file
/// gets its name only after its bytes are on the disk.
fn create_new_file(folder: &Path, target: &Path, bytes: &[u8]) -> std::io::Result<bool> {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let temp = folder.join(format!(".asset-{}-{nanos}.tmp", std::process::id()));
    let write_new = |at: &Path| -> std::io::Result<()> {
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(at)
            .and_then(|file| write_and_sync(file, bytes))
    };
    if let Err(err) = write_new(&temp) {
        let _ = fs::remove_file(&temp);
        return Err(err);
    }
    let linked = fs::hard_link(&temp, target);
    let _ = fs::remove_file(&temp);
    match linked {
        Ok(()) => Ok(true),
        Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        // The file system has no hard links (for example FAT). `create_new`
        // still refuses a name that exists.
        Err(_) => match write_new(target) {
            Ok(()) => Ok(true),
            Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
            Err(err) => {
                let _ = fs::remove_file(target);
                Err(err)
            }
        },
    }
}

/// True for a link with a URL scheme (`https:`, `file:`, `data:`, `C:`).
fn has_scheme(link: &str) -> bool {
    match link.find(':') {
        Some(colon) if colon > 0 => {
            let scheme = &link[..colon];
            scheme.chars().next().is_some_and(|c| c.is_ascii_alphabetic())
                && scheme.chars().all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.')
        }
        _ => false,
    }
}

/// True for a path relative to the folder of a diagram: no scheme, not
/// absolute, and no character that a person cannot see.
fn is_relative_link(link: &str) -> bool {
    !link.is_empty()
        && !has_scheme(link)
        && !link.starts_with(['/', '\\', '~', '#', '?', '-'])
        && !Path::new(link).is_absolute()
        && !link.chars().any(|c| c.is_control())
}

/// The canonical path of the image `src` of the diagram at `diagram_path`.
///
/// The page draws an image of a diagram through the asset protocol, and the
/// scope of the protocol has only the files that this function gives. Thus
/// the rule is narrow: `src` is a relative path with no `..`, the file is an
/// image, and its real path is inside the folder of the diagram.
pub(crate) fn local_image_path(
    access_policy: &AccessPolicy,
    diagram_path: &str,
    src: &str,
) -> std::result::Result<PathBuf, IpcError> {
    let refused = |why: &str| IpcError::new("IMAGE_REFUSED", format!("{src:?}: {why}"));
    if !is_relative_link(src) {
        return Err(refused("the image is not a path relative to the diagram"));
    }
    let relative = Path::new(src);
    if relative.components().any(|c| !matches!(c, std::path::Component::Normal(_) | std::path::Component::CurDir)) {
        return Err(refused("the path goes out of the folder of the diagram"));
    }
    if !extension_of(relative).is_some_and(|ext| is_image_extension(&ext)) {
        return Err(refused("the file is not an image"));
    }
    let (_, dir, _) = diagram_file(diagram_path)?;
    let joined = dir.join(relative);
    permitted(access_policy, &joined)?;
    let real = fs::canonicalize(&joined)
        .map_err(|err| IpcError::new("IMAGE_MISSING", format!("{}: {err}", joined.display())))?;
    if !real.starts_with(&dir) {
        return Err(refused("the file is outside the folder of the diagram"));
    }
    if !extension_of(&real).is_some_and(|ext| is_image_extension(&ext)) {
        return Err(refused("the file is not an image"));
    }
    permitted(access_policy, &real)?;
    match fs::metadata(&real) {
        Ok(meta) if meta.is_file() => Ok(real),
        _ => Err(IpcError::new("IMAGE_MISSING", format!("{} is not a file", real.display()))),
    }
}

/// The first `limit` bytes of the file at `path`.
pub(crate) fn read_head(path: &Path, limit: usize) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let mut head = Vec::new();
    fs::File::open(path)?.take(limit as u64).read_to_end(&mut head)?;
    Ok(head)
}

/// What a link of a node names.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum LinkTarget {
    /// An http or https link, for the browser of the system.
    Web(String),
    /// A local file or folder, with its canonical path.
    File(PathBuf),
}

/// The `%XX` escapes of `text` as bytes, decoded as UTF-8. `None` for a bad
/// escape or for bytes that are not UTF-8.
fn percent_decoded(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = text.get(i + 1..i + 3)?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// The target of the link `link` of a node of the diagram at `diagram_path`.
///
/// - An http or https link opens in the browser (`is_openable_url`).
/// - A `file://` URL names a local path. Its host is empty or `localhost`.
/// - A relative path names a file in relation to the folder of the diagram.
///   It can have the escapes of a URL (`my%20report.pdf`).
///
/// Each other link is refused. The file must exist, and the result is its
/// real path, so that the open rules see the real file and not a link.
pub(crate) fn link_target(
    access_policy: &AccessPolicy,
    diagram_path: Option<&str>,
    link: &str,
) -> std::result::Result<LinkTarget, IpcError> {
    let refused = |why: &str| IpcError::new("LINK_REFUSED", format!("{link:?}: {why}"));
    if crate::is_openable_url(link) {
        return Ok(LinkTarget::Web(link.to_string()));
    }
    if link.chars().any(|c| c.is_control()) {
        return Err(refused("the link has a control character"));
    }
    let candidates: Vec<PathBuf> = if link.len() >= 7 && link[..7].eq_ignore_ascii_case("file://") {
        let rest = &link[7..];
        let path = if rest.starts_with('/') {
            rest
        } else if rest.len() >= 9 && rest[..9].eq_ignore_ascii_case("localhost") && rest[9..].starts_with('/') {
            &rest[9..]
        } else {
            return Err(refused("a file URL must name a path on this computer"));
        };
        let decoded = percent_decoded(path).ok_or_else(|| refused("the file URL has a bad escape"))?;
        vec![PathBuf::from(decoded)]
    } else if is_relative_link(link) {
        let diagram = diagram_path.ok_or_else(|| refused("a relative link needs a diagram with a file"))?;
        let (_, dir, _) = diagram_file(diagram)?;
        let mut paths = vec![dir.join(link)];
        if let Some(decoded) = percent_decoded(link).filter(|d| d != link) {
            paths.push(dir.join(decoded));
        }
        paths
    } else {
        return Err(refused("only an http or https link, a file URL or a relative path opens"));
    };
    for candidate in &candidates {
        permitted(access_policy, candidate)?;
        if let Ok(real) = fs::canonicalize(candidate) {
            permitted(access_policy, &real)?;
            return Ok(LinkTarget::File(real));
        }
    }
    Err(IpcError::new("LINK_MISSING", format!("{link:?}: no such file")))
}

/// What a command-click does with a local file.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum OpenAction {
    /// Open the file with its default app.
    Open,
    /// Show the file in the file manager, and do not open it.
    Reveal,
}

/// The extensions of a file that can run a program when it opens: an app, an
/// installer, a script, a file that a shell or Terminal runs, a launcher, a
/// link file that opens a different target, and a system configuration.
const PROGRAM_EXTENSIONS: &[&str] = &[
    // macOS apps, bundles and installers
    "app", "pkg", "mpkg", "dmg", "bundle", "framework", "plugin", "prefpane", "kext", "appex", "xpc",
    "qlgenerator", "mdimporter", "saver", "osax", "widget", "action", "workflow",
    // scripts and the files that Terminal runs
    "command", "tool", "sh", "bash", "zsh", "csh", "tcsh", "ksh", "fish", "terminal", "scpt", "scptd",
    "applescript", "py", "pyw", "pyc", "rb", "pl", "php", "lua", "tcl", "js", "mjs", "jar", "class",
    // links and system configuration
    "webloc", "inetloc", "fileloc", "url", "desktop", "mobileconfig", "provisionprofile", "lnk",
    // Windows and Linux programs and installers
    "exe", "com", "bat", "cmd", "msi", "msix", "ps1", "vbs", "vbe", "wsf", "scr", "reg", "cpl", "hta",
    "appimage", "run", "bin", "deb", "rpm", "apk",
];

/// The first bytes of a Finder alias. An alias can point to an app, and an
/// open of the alias opens its target.
const ALIAS_MAGIC: &[u8] = b"book\0\0\0\0mark\0\0\0\0";

#[cfg(unix)]
fn is_executable(meta: &fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    meta.permissions().mode() & 0o111 != 0
}

#[cfg(not(unix))]
fn is_executable(_meta: &fs::Metadata) -> bool {
    false
}

/// The action for the canonical path `path`.
///
/// A diagram can come from a different person, and its links can name any
/// file. Thus a command-click opens only a document. A file that can run a
/// program shows in the file manager, and the person decides:
///
/// - a file with an executable bit (a program or a script),
/// - a file with an extension of [`PROGRAM_EXTENSIONS`],
/// - a Finder alias,
/// - a folder with an extension (a bundle: an `.app`, a `.pkg`),
/// - each thing that is not a file or a folder.
///
/// A folder with no extension opens in the file manager, which runs nothing.
/// A file system that gives each file the executable bit (FAT, some network
/// shares) shows each of its files in the file manager.
pub(crate) fn open_action(path: &Path) -> OpenAction {
    let Ok(meta) = fs::metadata(path) else {
        return OpenAction::Reveal;
    };
    let extension = extension_of(path);
    if meta.is_dir() {
        return if extension.is_none() { OpenAction::Open } else { OpenAction::Reveal };
    }
    if !meta.is_file() || is_executable(&meta) {
        return OpenAction::Reveal;
    }
    if extension.as_deref().is_some_and(|ext| PROGRAM_EXTENSIONS.contains(&ext)) {
        return OpenAction::Reveal;
    }
    match read_head(path, ALIAS_MAGIC.len()) {
        Ok(head) if head == ALIAS_MAGIC => OpenAction::Reveal,
        Ok(_) => OpenAction::Open,
        Err(_) => OpenAction::Reveal,
    }
}

/// The command that does `action` with `path` on this platform.
pub(crate) fn opener(action: OpenAction, path: &Path) -> std::process::Command {
    #[cfg(target_os = "macos")]
    {
        let mut c = std::process::Command::new("open");
        if action == OpenAction::Reveal {
            c.arg("-R");
        }
        c.arg(path);
        c
    }
    #[cfg(target_os = "windows")]
    {
        let mut c = std::process::Command::new("explorer");
        match action {
            OpenAction::Open => c.arg(path),
            OpenAction::Reveal => c.arg(format!("/select,{}", path.display())),
        };
        c
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        let mut c = std::process::Command::new("xdg-open");
        match action {
            OpenAction::Open => c.arg(path),
            OpenAction::Reveal => c.arg(path.parent().unwrap_or(path)),
        };
        c
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn no_policy() -> AccessPolicy {
        AccessPolicy {
            allowed_roots: Vec::new(),
            denied_roots: Vec::new(),
        }
    }

    /// A fresh, empty directory for one test, with its real path (on macOS
    /// the temp directory is behind a symlink).
    fn fresh_dir(test: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("gx-local-files-{test}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("temp dir");
        fs::canonicalize(&dir).expect("real path")
    }

    fn diagram_in(dir: &Path, name: &str) -> String {
        let path = dir.join(name);
        fs::write(&path, "digraph { a }").expect("diagram");
        path.to_string_lossy().to_string()
    }

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n-image-one";
    const OTHER_PNG: &[u8] = b"\x89PNG\r\n\x1a\n-image-two";

    // --------------------------------------------------------- asset write

    #[test]
    fn an_asset_goes_into_a_folder_next_to_the_diagram_with_a_content_hash_name() {
        let dir = fresh_dir("asset-name");
        let diagram = diagram_in(&dir, "arch.dot");

        let relative = write_asset_at(&no_policy(), &diagram, "png", PNG).expect("the write");

        let hash = content_hash(PNG);
        assert_eq!(relative, format!("arch.assets/img-{}.png", &hash[..SHORT_HASH]));
        assert!(dir.join("arch.assets").is_dir(), "no assets folder");
        assert_eq!(fs::read(dir.join(&relative)).expect("the asset"), PNG);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_same_bytes_use_the_same_asset() {
        let dir = fresh_dir("asset-reuse");
        let diagram = diagram_in(&dir, "arch.mmd");

        let first = write_asset_at(&no_policy(), &diagram, "png", PNG).expect("the first write");
        let second = write_asset_at(&no_policy(), &diagram, "png", PNG).expect("the second write");
        let other = write_asset_at(&no_policy(), &diagram, "png", OTHER_PNG).expect("the other write");

        assert_eq!(first, second);
        assert_ne!(first, other);
        let names: Vec<_> = fs::read_dir(dir.join("arch.assets"))
            .expect("the folder")
            .map(|e| e.expect("entry").file_name())
            .collect();
        assert_eq!(names.len(), 2, "{names:?}");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_asset_write_does_not_overwrite_a_different_file_with_the_name() {
        let dir = fresh_dir("asset-no-overwrite");
        let diagram = diagram_in(&dir, "arch.dot");
        let hash = content_hash(PNG);
        let short_name = format!("img-{}.png", &hash[..SHORT_HASH]);
        fs::create_dir_all(dir.join("arch.assets")).expect("the folder");
        fs::write(dir.join("arch.assets").join(&short_name), b"a different file").expect("the other file");

        let relative = write_asset_at(&no_policy(), &diagram, "png", PNG).expect("the write");

        assert_eq!(relative, format!("arch.assets/img-{hash}.png"));
        assert_eq!(
            fs::read(dir.join("arch.assets").join(&short_name)).expect("the other file"),
            b"a different file"
        );
        assert_eq!(fs::read(dir.join(&relative)).expect("the asset"), PNG);

        // The long name is taken by a different file too: the write refuses.
        fs::write(dir.join(&relative), b"also different").expect("the long name");
        let refused = write_asset_at(&no_policy(), &diagram, "png", PNG).expect_err("a refusal");
        assert_eq!(refused.code, "ASSET_EXISTS");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_asset_write_refuses_an_extension_that_is_not_an_image_or_a_path() {
        let dir = fresh_dir("asset-ext");
        let diagram = diagram_in(&dir, "arch.dot");
        for ext in ["", "exe", "sh", "../png", "png/../../x", "a/png", ".png", "png\0"] {
            let refused = write_asset_at(&no_policy(), &diagram, ext, PNG).expect_err(ext);
            assert_eq!(refused.code, "ASSET_REFUSED", "{ext:?}");
        }
        assert!(!dir.join("arch.assets").exists(), "a refused write made the folder");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_asset_write_refuses_empty_bytes_and_a_diagram_that_is_not_a_file() {
        let dir = fresh_dir("asset-input");
        let diagram = diagram_in(&dir, "arch.dot");
        assert_eq!(write_asset_at(&no_policy(), &diagram, "png", b"").expect_err("empty").code, "ASSET_REFUSED");
        let missing = dir.join("missing.dot").to_string_lossy().to_string();
        assert_eq!(
            write_asset_at(&no_policy(), &missing, "png", PNG).expect_err("missing").code,
            "DOCUMENT_MISSING"
        );
        let folder = dir.to_string_lossy().to_string();
        assert_eq!(
            write_asset_at(&no_policy(), &folder, "png", PNG).expect_err("a folder").code,
            "DOCUMENT_MISSING"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// A link with the name of the folder could send the write to a different
    /// place, so the write refuses it.
    #[cfg(unix)]
    #[test]
    fn an_asset_write_refuses_an_assets_folder_that_is_a_link() {
        let dir = fresh_dir("asset-link");
        let elsewhere = fresh_dir("asset-link-elsewhere");
        let diagram = diagram_in(&dir, "arch.dot");
        std::os::unix::fs::symlink(&elsewhere, dir.join("arch.assets")).expect("the link");

        let refused = write_asset_at(&no_policy(), &diagram, "png", PNG).expect_err("a refusal");

        assert_eq!(refused.code, "ASSET_REFUSED");
        assert_eq!(fs::read_dir(&elsewhere).expect("elsewhere").count(), 0, "the write went through the link");
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_dir_all(&elsewhere);
    }

    #[test]
    fn an_asset_write_follows_the_access_policy() {
        let dir = fresh_dir("asset-policy");
        let diagram = diagram_in(&dir, "arch.dot");
        let denying = AccessPolicy {
            allowed_roots: Vec::new(),
            denied_roots: vec![dir.clone()],
        };
        let refused = write_asset_at(&denying, &diagram, "png", PNG).expect_err("a refusal");
        assert_eq!(refused.code, "DOCUMENT_DENIED");
        assert!(!dir.join("arch.assets").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    // --------------------------------------------------------- local images

    #[test]
    fn a_local_image_is_a_relative_image_file_inside_the_folder_of_the_diagram() {
        let dir = fresh_dir("local-image");
        let diagram = diagram_in(&dir, "arch.dot");
        let relative = write_asset_at(&no_policy(), &diagram, "png", PNG).expect("the write");

        let path = local_image_path(&no_policy(), &diagram, &relative).expect("the image");

        assert_eq!(path, dir.join(&relative));
        assert_eq!(read_head(&path, 4).expect("the head"), &PNG[..4]);
        assert_eq!(read_head(&path, IMAGE_HEAD_BYTES).expect("the head"), PNG);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_local_image_outside_the_folder_or_not_an_image_is_refused() {
        let dir = fresh_dir("local-image-refused");
        let sub = dir.join("sub");
        fs::create_dir_all(&sub).expect("sub");
        let diagram = diagram_in(&sub, "arch.dot");
        fs::write(dir.join("outside.png"), PNG).expect("outside");
        fs::write(sub.join("notes.txt"), "text").expect("text");
        fs::write(sub.join("inside.png"), PNG).expect("inside");

        for src in [
            "../outside.png",
            "inside.png/../../outside.png",
            &dir.join("outside.png").to_string_lossy().to_string(),
            "file:///etc/hosts.png",
            "https://example.com/a.png",
            "data:image/png;base64,AAAA",
            "notes.txt",
            "",
        ] {
            let refused = local_image_path(&no_policy(), &diagram, src).expect_err(src);
            assert_eq!(refused.code, "IMAGE_REFUSED", "{src:?}");
        }
        assert_eq!(
            local_image_path(&no_policy(), &diagram, "missing.png").expect_err("missing").code,
            "IMAGE_MISSING"
        );
        assert!(local_image_path(&no_policy(), &diagram, "inside.png").is_ok());
        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn a_local_image_through_a_link_to_outside_the_folder_is_refused() {
        let dir = fresh_dir("local-image-link");
        let elsewhere = fresh_dir("local-image-link-elsewhere");
        let diagram = diagram_in(&dir, "arch.dot");
        fs::write(elsewhere.join("secret.png"), PNG).expect("secret");
        std::os::unix::fs::symlink(elsewhere.join("secret.png"), dir.join("shown.png")).expect("the link");

        let refused = local_image_path(&no_policy(), &diagram, "shown.png").expect_err("a refusal");

        assert_eq!(refused.code, "IMAGE_REFUSED");
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_dir_all(&elsewhere);
    }

    // --------------------------------------------------------- links

    #[test]
    fn a_link_is_a_web_link_a_relative_path_or_a_file_url() {
        let dir = fresh_dir("link-target");
        let diagram = diagram_in(&dir, "arch.dot");
        fs::create_dir_all(dir.join("docs")).expect("docs");
        fs::write(dir.join("docs").join("my report.pdf"), "%PDF").expect("pdf");
        let pdf = dir.join("docs").join("my report.pdf");

        assert_eq!(
            link_target(&no_policy(), Some(&diagram), "https://example.com/a").expect("web"),
            LinkTarget::Web("https://example.com/a".to_string())
        );
        assert_eq!(
            link_target(&no_policy(), Some(&diagram), "docs/my report.pdf").expect("relative"),
            LinkTarget::File(pdf.clone())
        );
        // A relative link with the escapes of a URL also finds the file.
        assert_eq!(
            link_target(&no_policy(), Some(&diagram), "docs/my%20report.pdf").expect("escaped"),
            LinkTarget::File(pdf.clone())
        );
        let url = format!("file://{}", pdf.to_string_lossy().replace(' ', "%20"));
        assert_eq!(link_target(&no_policy(), None, &url).expect("file url"), LinkTarget::File(pdf.clone()));
        let localhost = format!("file://localhost{}", pdf.to_string_lossy().replace(' ', "%20"));
        assert_eq!(
            link_target(&no_policy(), None, &localhost).expect("localhost"),
            LinkTarget::File(pdf.clone())
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_link_that_names_nothing_to_open_is_refused() {
        let dir = fresh_dir("link-refused");
        let diagram = diagram_in(&dir, "arch.dot");
        for link in [
            "",
            "javascript:alert(1)",
            "mailto:a@example.com",
            "ftp://example.com/a",
            "-a Calculator",
            "file://example.com/etc/hosts",
            "docs/a\nb.pdf",
            "#anchor",
        ] {
            let refused = link_target(&no_policy(), Some(&diagram), link).expect_err(link);
            assert_eq!(refused.code, "LINK_REFUSED", "{link:?}");
        }
        // A relative link needs a diagram with a file.
        assert_eq!(
            link_target(&no_policy(), None, "arch.dot").expect_err("no diagram").code,
            "LINK_REFUSED"
        );
        assert_eq!(
            link_target(&no_policy(), Some(&diagram), "missing.pdf").expect_err("missing").code,
            "LINK_MISSING"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_document_opens_and_a_program_shows_in_the_file_manager() {
        let dir = fresh_dir("open-action");
        let file = |name: &str| {
            let path = dir.join(name);
            fs::write(&path, "content").expect("file");
            path
        };
        let folder = |name: &str| {
            let path = dir.join(name);
            fs::create_dir_all(&path).expect("folder");
            path
        };

        for name in ["report.pdf", "notes.txt", "photo.PNG", "flow.mmd", "arch.dot", "README"] {
            assert_eq!(open_action(&file(name)), OpenAction::Open, "{name}");
        }
        assert_eq!(open_action(&folder("plain folder")), OpenAction::Open);

        for name in [
            "run.command",
            "run.sh",
            "Run.TOOL",
            "setup.pkg",
            "setup.mpkg",
            "script.py",
            "settings.terminal",
            "profile.mobileconfig",
            "link.webloc",
            "tool.jar",
            "setup.exe",
            "run.bat",
        ] {
            assert_eq!(open_action(&file(name)), OpenAction::Reveal, "{name}");
        }
        for name in ["Calculator.app", "Plugin.bundle", "Thing.prefPane"] {
            assert_eq!(open_action(&folder(name)), OpenAction::Reveal, "{name}");
        }

        // A Finder alias can point to an app. Its first bytes tell it.
        let alias = dir.join("alias");
        fs::write(&alias, b"book\0\0\0\0mark\0\0\0\0rest").expect("alias");
        assert_eq!(open_action(&alias), OpenAction::Reveal);
        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn a_file_with_the_executable_bit_shows_in_the_file_manager() {
        use std::os::unix::fs::PermissionsExt;
        let dir = fresh_dir("open-exec");
        let path = dir.join("report.pdf");
        fs::write(&path, "content").expect("file");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).expect("chmod");
        assert_eq!(open_action(&path), OpenAction::Reveal);
        let _ = fs::remove_dir_all(&dir);
    }

    /// The link target is canonical, so a link to a program shows as the
    /// program.
    #[cfg(unix)]
    #[test]
    fn a_link_to_a_program_through_a_symlink_shows_in_the_file_manager() {
        let dir = fresh_dir("open-symlink");
        let diagram = diagram_in(&dir, "arch.dot");
        fs::write(dir.join("run.command"), "echo hi").expect("script");
        std::os::unix::fs::symlink(dir.join("run.command"), dir.join("notes.pdf")).expect("the link");

        let target = link_target(&no_policy(), Some(&diagram), "notes.pdf").expect("the target");

        assert_eq!(target, LinkTarget::File(dir.join("run.command")));
        if let LinkTarget::File(path) = target {
            assert_eq!(open_action(&path), OpenAction::Reveal);
        }
        let _ = fs::remove_dir_all(&dir);
    }
}
