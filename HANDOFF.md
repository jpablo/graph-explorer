# Handoff: desktop open targets and persistence

Written 2026-08-22. Read `docs/desktop-open-targets-and-persistence.md` first.
That document is the plan. This file records the state of the work.

## 1. State

**Every phase in the plan is implemented.** Phases 0 to 3 are released as
`v0.9.5`; Phase 4 is on `viewer` and not yet released.

`v0.9.5` tags `fecafd8c`, the merge that brought both phases onto `viewer`.
`viewer` has moved on since — this file's own updates land there — so check
`git log v0.9.5..HEAD` rather than assuming the tag is at the tip. The branches
that carried this work,
`phase-2-loose-document-sessions` and `phase-3-origin-reconciliation`, are
merged and deleted. Every commit is reachable from `viewer`.

The release published six binaries — desktop and `gx` for macOS, Linux and
Windows — plus `SHA256SUMS`:
<https://github.com/jpablo/graph-explorer/releases/tag/v0.9.5>

All tests pass.

| Suite | Count | Command |
|---|---|---|
| Scala, all modules | 2295 | `sbt --client testFull` |
| Rust, desktop | 50 | `cd desktop/src-tauri && cargo test` |
| Open handshake | 6 checks | `bash scripts/local-capabilities-open-handshake-smoke.sh` |

The smoke script needs a release desktop binary. Read §8 for the build order.

NOTE: the graphviz corpus tally is **810**. It is the byte-exact transcription
of the dot engine, and it must not move. A corpus diff is a regression, never a
rebaseline.

## 2. What Phase 2 added

The seven items are done. The parts to know:

- **A loose file has a route.** `Route.LooseDocument(sessionId)` gives the URL
  `/documents/<opaque-session-id>`. The route holds the session id and never a
  path (§13).
- **`DesktopDocumentRegistry` maps that id to the file.** It holds the canonical
  path, the base revision, the source text, and a conflict. It is a `Var`, so a
  view follows its own session. `record` is idempotent per path: a second
  `gx open` of one file keeps the first id.
- **`ViewerState` takes a `ViewTarget`, not a `ProjectId`.** The enum has
  `LibraryDiagram`, `LooseFile` and `Example`. `projectId` survives as a derived
  value for the share URL and for `Project`. It is not a persistence key.
- **`DiagramPersistence` is chosen from the target.** `forTarget` is total over
  `ViewTarget`, and that totality is the guarantee §6 asks for: a loose file
  cannot reach library persistence, because no branch gives it one.
- **⌘S is `ViewerState.save()`.** The old path reached through
  `window.__graphExplorerDesktopBridge` for a destination that was
  process-global. That object no longer offers a save, and `documentRef` is
  gone.
- **A file event never replaces dirty text (§7.3).** A clean editor adopts the
  file. A dirty editor keeps its text, and the file's version waits in the
  session behind a banner with two answers.
- **A file open completes the handshake.** `show_file` waits for the page, and
  the acknowledgment names the session and revision that displayed it.

Two deviations from the plan's own sketches, both deliberate:

1. §6 sketches `ViewerState(target = ..., persistence = ...)`. A Scala default
   argument cannot read an earlier parameter of the same list, so
   `persistence = forTarget(target)` does not compile. The `Persistence` trait
   derives it instead. Each implementation is still built and tested on its own.
2. §2 sketches `LooseFile(DocumentRef(sessionId, path, revision))`. The target
   holds the session id only. A revision advances on every save, and a copy in
   the target would go stale and make the next save report a false conflict.

## 3. What Phase 3 added

Phase 3 gives the desktop a capability it did not have. Before it, an edit in
the app changed the record text and left `baseHash` alone. The app never wrote
the origin file. The edit was not lost, and it waited for `gx sync`.

- **The engine moved to `gx-core`.** `Reconciler.plan` is pure and shared, so
  the page and `gx sync` reach the same answer. `syncOne` keeps every behaviour
  it had and is now the I/O half: read the origin, ask, perform, save.
- **The CRLF rule moved with it.** `Reconciler.storedWith` names the convention
  `local` must be hashed with. `LineEnding` moved to `shared/` to make that
  possible. It touches no file.
- **The page hashes through the shell.** `Hashing` is `MessageDigest`, which
  Scala.js does not have. The `hash_text` command reuses the shell's own
  digest, which `content-hashes.json` already pins against the JVM's. A second
  SHA-256 in the webview would have had to agree with two others forever.
- **An origin change reaches its RECORD.** `DesktopLibrary` indexes records by
  the file each binds to. `DesktopBridge` sends a bound path to
  `OriginReconciler` instead of opening a loose session for it. A pull writes
  the record, and an open viewer follows because it already watches the record.
- **A divergence is visible.** A strip reports the two states §5.2 calls
  `needsUser`: both sides changed, or the origin is gone.

One deviation, deliberate. The plan says to move `syncOne` into `gx-core`.
`Reconciler` holds the DECISION only, and returns the one step for the caller
to perform. The two callers cannot share I/O: `gx` reads and writes files, and
the page has no filesystem. They can share the answer.

Two traps the code now guards, both found while building this:

- `filePath` is not the inverse of `fromCanonicalPath` on Windows. The encoder
  writes URI separators, so a record read as unbound and opened a second time
  beside itself. `DesktopLibrary` collapses both spellings, and `filePath`'s
  scaladoc now warns. Every other caller passes the value to `Paths.get`, which
  accepts either separator, so none of them was affected.
- A hash the page cannot obtain is reported as `Diverged`, never as agreement.
  A failed IPC call means no comparison happened, and `InSync` would claim one
  that never did.

### The two resolution actions (added after Phase 3)

§8 lists four: take the file version, keep the library version, write the
library version to the file, and detach. **The first two now have buttons.**
Both are one call to `Library.recordReconciled`, and both settle the record:

| Button | Call | Record then reads |
|---|---|---|
| Take the file's version | `recordReconciled(id, Some(originText), originHash)` | `InSync` |
| Keep this diagram | `recordReconciled(id, None, originHash)` | `Ahead` |

The baseline has to move in BOTH. Keeping the diagram and leaving the baseline
alone would leave base, local and remote all different, which is the definition
of `Diverged`: the strip would come straight back and the decision would have
changed nothing. Moving it says "I have seen the file's version and mine
stands", and a binding that pushes carries the record on the next sync.

`OriginReconciler` now keeps the origin SNAPSHOT beside the state, because the
resolution needs the text and the hash the person was shown. It refuses to
offer a resolution it cannot trust: a missing origin has no text, and a write
that lost its compare-and-swap proves the file moved after the read. Both still
report the situation, with no buttons.

Fixed on the way: a push that lost its compare-and-swap recorded the divergence
and then erased it. `perform` marked it, and the caller overwrote the mark with
the settled state it had PREDICTED. `perform` now returns the state the action
actually reached.

**Still not built**, and each needs a decision first:

- **Write the library version to the file.** `binding.mode` answers most of it
  — a `Pull` binding says the file is the author — so the narrow question is
  whether the button simply does not appear under `Pull` and `Detached`. That
  leaves a diverged `Pull` record with two of the four actions. Agree that this
  is right rather than discover it.
- **Detach.** This one also needs new plumbing. `LibraryMapping` carries a
  binding forward unchanged on every page write, so no page path can clear one.
  That invariant is also what makes Phase 3 safe: today the page never changes
  a binding, so a `gx sync` and an app edit cannot fight over one. Detach is
  the first thing that would break it.

Open too: a `Detached` record still shows the strip when it diverges. Detached
means "do not synchronize", so reporting a conflict there may be noise. Not
changed, because it is a policy call.

### The watch that makes all of it run (added after Phase 3)

Phase 3 decided what to do with an origin change and never asked for one.
`DesktopIpc.OpenDocument` was defined, handled in Rust, described in the
protocol README — and called from NOWHERE. Reconciliation runs on a document
event, and the shell sends one only for a file it watches, so an origin edit
reached the app only when `gx open` had watched the file. Opening the same
record from the library was silent, and everything downstream of it — the
whole of Phase 3 and the two buttons above — was unreachable from the UI.

`Persistence.watchOrigin()` now runs at `initializePersistence`, beside
`followDocumentSession`. A loose file arrives with its text; a record arrives
with a binding and nothing listening behind it. This is the symmetric half.

Three things about it:

- **The reply is discarded.** It carries the bytes, and taking them would put
  the file on screen without asking the record — the behaviour §8 removed.
  `add_watch` emits a document event for a watch it CREATES, so the text
  arrives by the one route that reconciles. A file changed while the app was
  shut therefore reconciles at open, and the strip is there on arrival.
- **There is no unwatch, and it must not grow one without a refcount.** Watches
  are keyed by path and shared: `gx watch` may hold one on the same file, and
  releasing it because a page navigated would stop a watch the page never
  started. The cost is one watch per bound record visited in a session.
- **A watch the shell refuses is not fatal.** The record still opens. It just
  does not hear about the file.

## 4. Three defects that only RUNNING it found

None of these came from a test. All three were found by building the desktop
and using it, and each had passed every suite.

**`gx open <path>` failed on a cold desktop.** It answered DOCUMENT_NOT_FOUND,
and worked as soon as any diagram had been opened — a bug that disappears when
you try to reproduce it. Two causes. The page installed its document listener
from `DesktopBridge.attach`, which runs only when a view mounts, so a desktop on
Home had none. And the shell emitted the document event before the page was
listening; unlike an open request, that event is not queued (§4.1). The smoke
script now opens a loose file FIRST, before anything else, because opening
anything else installs the listener and hides the failure. Do not move that
check.

**A Windows record was unreachable from the path the shell reports.**
`filePath` is not the inverse of `fromCanonicalPath`: the encoder writes URI
separators, so `C:\Users\x\a.dot` came back as `C:/Users/x/a.dot`. The record
read as unbound and opened a second time beside itself, silently.

**Cmd+Q discarded an unsaved edit.** The close handler was correct and
unreachable. macOS raises `CloseRequested` for a window's red button, but Cmd+Q
goes through the app menu's Quit item — Tauri's predefined one calls the native
terminate and bypasses the event loop. Instrumenting every run event showed
`Exit` and nothing preventable before it. Quit is now an item this app owns.

CAUTION: three test harnesses failed to reach the Cmd+Q path before a person
pressed the key. An AppleScript quit does not go through the menu. A synthetic
keystroke is blocked by accessibility permissions, and its "the app is still
running" result means nothing. Only a human can check this one.

## 5. Before the NEXT release

Phase 4 is cleanup and does not gate a release. These do, and all three were
done for `v0.9.5`:

1. **Rebuild `gx`.** `scripts/build-gx.sh` writes `gx-cli/target/gx`. It is a
   GraalVM native-image build, separate from the desktop's, so building the
   desktop leaves it stale. Compare `gx --version` against the branch before
   you trust any manual test: a `gx` older than the typed open targets does not
   speak the current `show` protocol, and §10 says why that matters.
2. **Check Cmd+Q by hand** after any change to the menu, the close handler or
   the unsaved flag. Nothing automated reaches it.
3. Run the smoke script. It needs a release desktop binary; §8 gives the order.
4. **Look at the divergence strip once.** Its buttons are covered by a mounted
   DOM test, so they render and they work. What no test can show is how the
   strip LOOKS with two buttons in it. Its CSS copies
   `.document-conflict-banner`, which ships, so this is a look and not an
   investigation.

The manual Cmd+Q check was done for `ec8bff95`, which `v0.9.5` contains: the
dialog appears, the edit stays on screen, and Save writes the file and then
exits.

CAUTION: `sbt-dynver` computes the version ONCE, when the sbt project loads. A
server that has been up since before the tag keeps reporting the old one, and
the string can appear to go backwards between two builds. Run
`sbt --client reload` and then `show version` after tagging, and confirm it
prints the new tag before you believe any build.

## 6. What Phase 4 removed

Phase 4 removed the legacy global bridge. Its five items, and what each became:

| Item | Outcome |
|---|---|
| 1. Remove global `DesktopBridge.target` | Done. That module no longer names `ViewerState` at all. |
| 2. Remove global `documentRef` | Was already done, in Phase 2 item 5. |
| 3. Remove direct source replacement from generic events | Done, after `push-text` gained a target. |
| 4. Remove acceptance of unaddressed legacy events | Done. The bare `document.changed` is no longer listened to. |
| 5. Update protocol fixtures and documentation | Done. `local-protocol/README.md` had described `show` as taking a bare path since Phase 1. |

The keystone was a decision, not code: `/v1/push-text` now NAMES the document
session it is aimed at. It carried text alone and landed in whichever viewer was
on screen. Once a push names its destination, text with no addressee has no
legitimate source — which turned items 3 and 4 from judgement calls into
deletions.

A viewer now follows its OWN session through an owner-scoped signal (§10), so
Laminar teardown ends it and nothing has to detach anything.

**Two globals stay, and the code says why.** `SessionCommands` and
`DesktopClose` ask questions that are ABOUT the view on screen — what is
selected, is there an edit to lose before this window closes. Naming the current
view is their meaning, not an accident. Item 1 named `DesktopBridge.target`, and
that is the one that could go.

§16's invariant now holds: the route, the persistence owner, the event target
and the save destination all derive from one `ViewTarget`.

## 7. What remains

The plan is implemented. What is left was deferred on purpose:

1. **Two of §8's four resolution actions.** Take the file and keep the library
   version are built and have buttons; see §3. Write-to-file and detach are
   not. Write-to-file needs a rule for which binding modes let the page write
   on a person's behalf. Detach needs that AND new plumbing: no page path can
   clear a binding, and clearing one races a `gx sync`.
2. **Confirm the origin path on a real Windows machine.** `originPathOf`
   returns the path as the binding stores it, which on Windows carries URI
   separators — `C:/Users/x/a.dot`. The shell normalizes through `Paths.get`,
   which accepts either, so it should reach the right file. Nothing has run it
   there. The last bug of this shape opened a second copy of a record beside
   itself and said nothing (§4).
3. **A quit APPLE EVENT still discards an unsaved edit.** macOS sends one on
   logout and restart. It bypasses the app menu, so it bypasses the Quit item
   that asks. Tauri exposes no hook: the run loop reports `Exit` with nothing
   preventable before it. Cmd+Q and the red button are safe.

## 8. Build the desktop in this order

WARNING: A wrong build order gives a blank window and an `OPEN_TIMEOUT`. This
looks like a broken handshake. It is not.

1. `sbt "viewer/fullLinkJS" && npm run build`
2. `cd desktop/src-tauri && cargo build --release --features tauri/custom-protocol`

Two rules apply:

- Build the frontend first. A release build embeds the frontend.
- Give cargo the `tauri/custom-protocol` feature. `cargo tauri build` sets this
  feature. `cargo build --release` alone does not. Without the feature the
  webview loads nothing.

The smoke script tests both rules before it starts. The script also uses a
sandbox `GX_HOME`. The script does not touch your library and does not stop
your desktop.

## 9. Open questions for the user

The deferred WORK is in §7. These are questions about the repository itself:

1. The repository has 49 local branches. 40 of them hold work that `viewer`
   does not contain, and there are 12 remote branches. Examples of the
   unmerged ones: `desktop-gx-v2`, `wip`, `tmp`, `travel`.
2. The plan document uses two voices. The Phase 2 item 7 text and the §15.6
   text use Simplified Technical English. Most of the document does not.

Four earlier questions are now answered. The plan's `Status:` line reports every
phase implemented. §15.6 requires the acknowledgment for a file open. The
desktop asks before Cmd+Q discards an edit — §4 records what that took. And
`/v1/push-text` names its document session, which is what let Phase 4 finish.

## 10. Cautions

- WARNING: If a development server runs, do not edit `Persistence.scala`,
  `ProjectsStorage`, or `ThumbnailDiskCache`. A server with hot reload writes
  the real library. No server runs now.
- WARNING: Do not run `sbt scalafmtAll`. `.scalafmt.conf` sets
  `maxColumn = 140`, and the sources wrap scaladoc near 78. The task reformats
  about 340 files and touches `graphviz/`. Match the file you edit by hand.
- The `show` RPC has no legacy format. `gx` and the desktop must ship together.
- Do not simplify `graphviz/`. That module is a transcription of Graphviz
  13.0.1 C source. Idiomatic Scala breaks the byte-exact output.
- WARNING: `gx` and the desktop must be built together for any manual test.
  The `show` RPC has no legacy format, and a stale `gx` fails against a new
  desktop in a way that looks like a broken handshake.
- Reconciliation compares TEXT, and `gx sync` compares hashes of text. Both use
  `Reconciler.storedWith` to choose the line ending. Keep it that way. A second
  convention makes a file read as changed because of its line endings alone.
- A loose file reaches the page ONLY through the desktop shell. The registry is
  empty in a browser, so `/documents/<id>` there shows "No such document
  session". That is correct, and it is not a bug to chase.

## 11. Other work in progress

The `/simplify` slice plan continues. Two slices are complete: `viewer/state`
and `gx-cli`. The next slice is `gx-core`. See the memory file
`simplify-slice-plan.md`.

NOTE: `viewer/state` changed a lot in Phase 2, and `gx-cli` lost its
reconciliation engine in Phase 3. Both slices may need a second pass.
