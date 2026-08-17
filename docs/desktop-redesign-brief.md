# Desktop + `gx` Redesign Brief

Written 2026-08-16, at the point where Local Capabilities v1 went green on all
three platforms. Starting point for redesigning `graph-explorer-desktop` and
`gx`.

This does not repeat `local-capabilities-v1-architecture.md` — read that first
for the intended design. This records what the code actually does where it
differs, what is load-bearing, and what was never decided.

## 1. You are redesigning from a working baseline

v1 is **Go on macOS, Linux and Windows** as of run 31934667443: production
desktop + `gx` build on all three, and each platform's runtime smoke
(`status` → `watch` → `get` → `set` → stale-conflict → `unwatch`) is a blocking
publish gate. Disk → UI latency runs ~74ms locally against a 300ms budget.

So the question is not "how do we stabilize this" but "what is it for". The
Task Board in the implementation plan is fully closed; the Test Matrix
(LC-INV-01..10) is not — those invariants are exercised only as side effects of
smoke scripts, never asserted by name.

## 2. The central unresolved question: who holds the token

The desktop mints a bearer token per launch and writes it to
`~/.graph-explorer/runtime/control.json` (mode 0600). That part matches the
design. What the design does not mention:

**The desktop hands that token to the webview.** Every `document.changed` event
carries `port` and `token` in its payload (`DocumentChangedEventPayload` in
`main.rs`), and the viewer stores them (`DesktopBridgeContext` in
`Viewer.scala`) so `Cmd/Ctrl+S` can `fetch` the control API directly from page
JavaScript.

Consequences worth deciding deliberately rather than inheriting:

- any script running in the webview has the full local API within policy —
  read and write of every watched file
- the token is in the JS heap and in DOM event payloads, so anything that can
  observe events or scrape globals can exfiltrate it
- `Access-Control-Allow-Origin: *` is set on the control server (needed because
  `tauri://localhost` → `http://127.0.0.1:<port>` is cross-origin). The token
  is the only thing standing between any local process and the API

The alternative the architecture doc implies but the code does not take: keep
the token native-side and route UI writes through a Tauri command, so the page
never holds a credential. That is a redesign-scale decision, which is why it
belongs here rather than in a bug.

## 3. Design vs. implementation delta

Everything below is in `local-capabilities-v1-architecture.md` and not in the
code. None of it is a regression — v1 shipped a deliberate subset — but a
redesign should know the map is not the territory.

| Designed | Reality |
|---|---|
| `GET /v1/events`, SSE, topics `document.changed`/`document.conflict`/`watch.added`/`watch.removed` | Not implemented anywhere. The real mechanism is `webview.eval()` injecting a DOM `CustomEvent`. `local-protocol/v1/schema.json` still advertises the SSE endpoint |
| `gx open <path>` | No such command |
| `gx watch --open` pushes into UI | `openInUi` is parsed into `_open_in_ui` and discarded; the flag is a no-op |
| `gx` can launch the desktop if the runtime file is missing | Not implemented; `gx` reports `DESKTOP_NOT_RUNNING` and exits 2 |
| `WatchState` carries `diskMtimeMs`, `diskHash`, `uiDirty`, `lastSource` | `WatchDescriptor` carries `path`, `format`, `revision`. No dirty tracking, no last-writer attribution |
| `/v1/status` reports dirty/conflict flags | Reports watches, policy roots and limits only |
| Delete/rename detection → `document.conflict` with actionable message | The watch loop reads the file and silently ignores failure. A deleted watched file produces nothing at all |
| Crash recovery restores the watch list | Watches are in-memory only and die with the process. Revisions restart at 1 |
| Atomic write = temp + **fsync** + rename | `write_file_atomic` does `fs::write` + `rename`, no fsync. Crash-during-write durability is unproven |
| Preserve line endings of the existing file | Not implemented; text is written as given |
| `diskHash` is sha256 | `DefaultHasher` (SipHash, not stable across Rust releases). Fine for in-process self-write suppression, wrong for anything persisted or compared across versions |
| Body limit ~5MB, debounce 75–150ms | 1MB default; 15ms poll / 50ms debounce, tuned down to hit the LC2-T5 latency budget on slow CI runners |
| Symlink behavior "must be explicitly defined" | Still undefined. `fs::canonicalize` resolves before policy checks, which is the safe direction, but it is incidental rather than specified or tested |

## 4. Constraints that are load-bearing

Things that look arbitrary and are not. Changing them is fine — knowing why
they exist is not optional.

- **The watcher polls (15ms) rather than using FS events.** The 15/50ms tuning
  exists to keep disk→UI median under 300ms on oversubscribed CI runners. A
  redesign moving to FS events must keep that budget measurable — the harness
  is `scripts/local-capabilities-disk-to-ui-smoke.sh`.
- **Self-write suppression is by content hash**, not path or mtime. This is
  what prevents the write → watch → push → write loop. Any redesign of the
  write path must preserve an equivalent.
- **`watch` and `set` carry paths in a JSON body; `get` puts one in a URL.**
  That asymmetry hid a five-month bug (percent-decoding, fixed 2026-08-16). If
  the redesign keeps a URL-carried path anywhere, it inherits the hazard.
- **The desktop must be built with `--features tauri/custom-protocol`.**
  Otherwise tauri's build script leaves `dev` set and the window loads
  `devUrl` — a blank window with no error. `scripts/build-local-capabilities-release.sh`
  asserts the frontend is actually embedded.
- **The build cannot run from inside sbt.** vite resolves `scalajs:` imports by
  shelling back into sbt, so an sbt task that runs `npm run build` deadlocks
  against its own nested client.

## 5. Defaults worth revisiting

- **The allowlist is empty by default, i.e. allow-all.** `GX_ALLOWED_ROOTS`
  opts in; with it unset every path passes the allowlist check. Only the
  denylist (`/System`, `~/.ssh`, `~/.gnupg`, `~/.aws`, `~/.kube`, …) is on out
  of the box. The architecture doc's open question — "permissive (prompt) or
  strict (allowlist required)?" — was answered by omission, not by decision.
- **There is no prompt path at all.** The design says "deny outside allowlist
  unless user confirms"; no confirmation UI exists, so policy is env-var only.
- **Both crates are version `0.1.0`**, so `gx status` reports `0.1.0` while the
  binaries ship inside `v0.6.x` releases.
- **Watches are per-session and per-process.** There is no notion of a
  workspace, a project, or reopening what you had open.

## 6. Still-open questions from the original design

Carried forward unanswered:

- Should unsaved UI drafts be persisted separately from on-disk text?
- One active writer (UI or CLI) per watched file, or last-write-wins with
  revisions as today?
- Permissive-with-prompt or strict-allowlist as the shipping default?

And added by hindsight:

- Is the webview a trusted or untrusted principal? (§2 — everything else in the
  security model follows from this)
- Is `gx` a thin client forever, or should it work with the desktop absent?
  Today every command requires a running desktop, which makes `gx` unusable in
  exactly the headless/agent contexts it was built for.
- Should the protocol be HTTP at all? It exists so `gx` can talk to the
  desktop; the UI path does not use it symmetrically (inbound is
  `webview.eval`, outbound is `fetch`). Two mechanisms, one direction each.

## 7. Where to start reading

| Concern | File |
|---|---|
| Intended design | `docs/local-capabilities-v1-architecture.md` |
| Control server, watcher, policy, audit — all of it | `desktop/src-tauri/src/main.rs` (~1600 lines, single file) |
| CLI surface and exit codes | `gx/src/main.rs` |
| Viewer side of the bridge | `viewer/src/main/scala/.../Viewer.scala`, `attachDesktopBridge` through `saveTextToDesktop` (~line 215 onward) |
| Protocol contract (partly aspirational — see §3) | `local-protocol/v1/schema.json` |
| What is actually verified, and how | `scripts/local-capabilities-*-smoke.sh` |
| Release/publish gates | `.github/workflows/release-binaries.yml` |
