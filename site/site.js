// The product page (site/index.html).
//
// The page works without this script: each download link then goes to the
// latest release on GitHub. The script adds four things:
//   1. direct links to the files of the latest release, from the GitHub API;
//   2. a download button for the visitor's platform;
//   3. "Open your library" for a visitor who has diagrams in the web app;
//   4. the explorable diagram in the "desktop" section.

const REPO = "jpablo/graph-explorer";
const RELEASES_PAGE = `https://github.com/${REPO}/releases/latest`;
const RELEASE_API = `https://api.github.com/repos/${REPO}/releases/latest`;

// The GitHub API permits 60 requests an hour from one address without a
// token. A visitor who reloads the page uses the stored answer.
const CACHE_KEY = "graph-explorer.site.latest-release";
const CACHE_MS = 60 * 60 * 1000;

const PLATFORM_NAMES = { macos: "macOS", windows: "Windows", linux: "Linux" };

// The names that release-binaries.yml gives the files.
const ASSET_PATTERNS = {
    desktop: /^graph-explorer-desktop-v[\d.]+-(macos|linux|windows)(?:\.dmg|\.exe)?$/,
    gx: /^gx-v[\d.]+-(macos|linux|windows)(?:\.exe)?$/,
};

function visitorPlatform() {
    const hint = (navigator.userAgentData?.platform || navigator.platform || navigator.userAgent || "").toLowerCase();
    const agent = navigator.userAgent.toLowerCase();
    // A phone or a tablet cannot run the desktop app.
    if (/android|iphone|ipad|ipod/.test(agent) || navigator.maxTouchPoints > 1 && /mac/.test(hint)) return null;
    if (hint.includes("mac")) return "macos";
    if (hint.includes("win")) return "windows";
    if (hint.includes("linux")) return "linux";
    return null;
}

function readCache() {
    try {
        const stored = JSON.parse(localStorage.getItem(CACHE_KEY));
        if (stored && Date.now() - stored.at < CACHE_MS) return stored.release;
    } catch {
        // No storage, or a bad value: ask the API.
    }
    return null;
}

function writeCache(release) {
    try {
        localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), release }));
    } catch {
        // No storage: the next visit asks the API again.
    }
}

async function latestRelease() {
    const cached = readCache();
    if (cached) return cached;
    const response = await fetch(RELEASE_API, { headers: { Accept: "application/vnd.github+json" } });
    if (!response.ok) throw new Error(`GitHub API: ${response.status}`);
    const body = await response.json();
    // Keep only what the page uses.
    const release = {
        tag: body.tag_name,
        date: body.published_at,
        page: body.html_url,
        assets: body.assets.map(({ name, size, browser_download_url }) => ({ name, size, url: browser_download_url })),
    };
    writeCache(release);
    return release;
}

/** { desktop: { macos: asset, ... }, gx: { ... }, sums: asset } */
function sortAssets(assets) {
    const found = { desktop: {}, gx: {}, sums: null };
    for (const asset of assets) {
        if (asset.name === "SHA256SUMS") found.sums = asset;
        for (const [kind, pattern] of Object.entries(ASSET_PATTERNS)) {
            const match = pattern.exec(asset.name);
            if (match) found[kind][match[1]] = asset;
        }
    }
    return found;
}

function megabytes(bytes) {
    return `${Math.round(bytes / 1e6)} MB`;
}

function longDate(iso) {
    return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" });
}

function setLink(link, asset) {
    link.href = asset.url;
    link.dataset.file = asset.name;
    const size = document.createElement("small");
    size.textContent = megabytes(asset.size);
    link.append(" ", size);
}

function showRelease(release, platform) {
    const files = sortAssets(release.assets);
    const version = release.tag.replace(/^v/, "");

    for (const article of document.querySelectorAll("[data-platform]")) {
        const name = article.dataset.platform;
        for (const link of article.querySelectorAll("[data-asset]")) {
            const asset = files[link.dataset.asset][name];
            if (asset) setLink(link, asset);
        }
    }

    const field = (name) => document.querySelector(`[data-field="${name}"]`);
    field("version").innerHTML = "";
    const tag = document.createElement("a");
    tag.href = release.page;
    tag.textContent = release.tag;
    field("version").append(tag);
    field("date").textContent = longDate(release.date);
    if (files.sums) field("sums").querySelector("a").href = files.sums.url;

    const primary = document.querySelector('[data-download="primary"]');
    const own = platform && files.desktop[platform];
    if (own) {
        primary.href = own.url;
        primary.dataset.file = own.name;
        primary.textContent = `Download for ${PLATFORM_NAMES[platform]}`;
    }
    const others = Object.keys(PLATFORM_NAMES).filter((name) => name !== platform).map((name) => PLATFORM_NAMES[name]);
    const line = document.querySelector("[data-release-line]");
    line.textContent = own
        ? `Version ${version}, ${longDate(release.date)}. Also for ${others.join(" and ")}. Free and open source.`
        : `Version ${version}, ${longDate(release.date)}, for macOS, Windows, and Linux. Free and open source.`;
}

function setUpDownloads() {
    const platform = visitorPlatform();
    const primary = document.querySelector('[data-download="primary"]');
    if (platform) {
        document.querySelector(`[data-platform="${platform}"]`)?.classList.add("is-yours");
    } else {
        // No desktop app for this device: show the list instead of a file.
        primary.href = "#download";
    }

    latestRelease()
        .then((release) => showRelease(release, platform))
        .catch(() => {
            // The links go to the releases page, which lists the same files.
            for (const link of document.querySelectorAll("[data-asset]")) link.href = RELEASES_PAGE;
        });

    document.addEventListener("click", (event) => {
        const link = event.target.closest("[data-asset], [data-download]");
        if (!link || typeof window.gtag !== "function") return;
        window.gtag("event", "download", {
            file: link.dataset.file ?? "releases-page",
            platform: link.closest("[data-platform]")?.dataset.platform ?? platform ?? "unknown",
        });
    });
}

// The web app keeps its library in localStorage on this origin, under the
// key that ProjectsStorage.scala writes (laminext adds the prefix).
function hasWebLibrary() {
    try {
        const raw = localStorage.getItem("[StoredString]graph-explorer.projects");
        return (JSON.parse(raw)?.projects?.length ?? 0) > 0;
    } catch {
        return false;
    }
}

function setUpLibraryLinks() {
    if (!hasWebLibrary()) return;
    for (const link of document.querySelectorAll(".button[data-library-link]")) {
        link.textContent = "Open your library";
    }
}

// ── The explorable diagram ─────────────────────────────────────────────────

const NOTES = {
    editor: ["Your editor.", "Edit the DOT or Mermaid text in any editor. When you save the file, the desktop app shows the change."],
    agent: ["Coding agent.", "An agent uses gx to read and change diagrams. It does not need a screen."],
    file: ["diagram.dot.", "A text file in your project. The desktop app watches it, and ⌘S writes your edits back to it."],
    gx: ["gx.", "The command-line tool. It edits files, adds them to the library, and tells the desktop app what to show."],
    library: ["Library.", "A folder of diagrams on your disk. The desktop app and gx share it."],
    desktop: ["Graph Explorer Desktop.", "The window. It shows a file or a library diagram, and it draws the diagram again when the source changes."],
};

function setUpFlow() {
    const svg = document.getElementById("flow");
    const note = document.querySelector("[data-flow-note]");
    if (!svg || !note) return;
    const defaultNote = note.textContent.trim();
    const nodes = [...svg.querySelectorAll(".node")];
    const edges = [...svg.querySelectorAll(".edge")];
    let selected = null;

    function select(name) {
        selected = name === selected ? null : name;
        svg.classList.toggle("has-selection", selected !== null);
        for (const node of nodes) node.classList.remove("near", "selected");
        for (const edge of edges) edge.classList.remove("near");
        if (selected === null) {
            note.textContent = defaultNote;
            return;
        }
        const near = new Set([selected]);
        for (const edge of edges) {
            if (edge.dataset.from === selected || edge.dataset.to === selected) {
                edge.classList.add("near");
                near.add(edge.dataset.from);
                near.add(edge.dataset.to);
            }
        }
        for (const node of nodes) {
            node.classList.toggle("near", near.has(node.dataset.node));
            node.classList.toggle("selected", node.dataset.node === selected);
            node.setAttribute("aria-pressed", String(node.dataset.node === selected));
        }
        const [title, text] = NOTES[selected];
        note.innerHTML = "";
        const strong = document.createElement("strong");
        strong.textContent = title;
        note.append(strong, " ", text);
    }

    for (const node of nodes) {
        node.setAttribute("aria-pressed", "false");
        node.addEventListener("click", (event) => {
            event.stopPropagation();
            select(node.dataset.node);
        });
        node.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                select(node.dataset.node);
            }
        });
    }
    svg.addEventListener("click", () => selected && select(selected));
    svg.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && selected) select(selected);
    });

    drawOnce(svg, nodes, edges);
}

// The diagram draws itself once, from left to right, when it comes into view.
function drawOnce(svg, nodes, edges) {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches || !("IntersectionObserver" in window)) return;
    const width = svg.viewBox.baseVal.width;
    const left = (element) => element.getBBox().x + 4; // The graph is shifted 4 units to the right.
    const delay = (element) => `${Math.round((left(element) / width) * 900)}ms`;

    for (const edge of edges) {
        const path = edge.querySelector("path");
        edge.style.setProperty("--len", String(Math.ceil(path.getTotalLength())));
        edge.style.setProperty("--delay", delay(edge));
    }
    for (const node of nodes) node.style.setProperty("--delay", delay(node));

    // Hidden until it comes into view. Without the script, it is never hidden.
    svg.classList.add("pre-draw");
    const observer = new IntersectionObserver((entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        svg.classList.replace("pre-draw", "drawing");
        // Remove the class after the last element, so that the transitions
        // of a selection work.
        setTimeout(() => svg.classList.remove("drawing"), 1800);
    }, { threshold: 0.5 });
    observer.observe(svg);
}

setUpDownloads();
setUpLibraryLinks();
setUpFlow();
