# Handoff: desktop open targets and persistence

Written 2026-08-22. Read `docs/desktop-open-targets-and-persistence.md` first.
That document is the plan. This file records the state of the work.

## 1. State

Phases 0, 1, 2 and 3 are complete.

The work is on two branches, and neither is merged. `viewer` and
`origin/viewer` still point at `d85e4c99`.

| Branch | Tip | Holds |
|---|---|---|
| `phase-2-loose-document-sessions` | `8090081a` | Phase 2, 4 commits |
| `phase-3-origin-reconciliation` | `ec8bff95` | Phase 2, Phase 3 AND the defect fixes below, 13 commits |

The second branch contains the first. They are a stack, not a fork: merge
Phase 2 first and Phase 3 rebases to nothing. Both are pushed.

All tests pass on `phase-3-origin-reconciliation`.

| Suite | Count | Command |
|---|---|---|
| Scala, all modules | 2283 | `sbt --client testFull` |
| Rust, desktop | 50 | `cd desktop/src-tauri && cargo test` |
| Open handshake | 6 checks | `bash scripts/local-capabilities-open-handshake-smoke.sh` |

The smoke script needs a release desktop binary. Read §7 for the build order.

## 2. What Phase 2 added (branch `phase-2-loose-document-sessions`)

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

## 3. What Phase 3 added (branch `phase-3-origin-reconciliation`)

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

### What Phase 3 did NOT build

§8 lists four resolution actions: take the file version, keep the library
version, write the library version to the file, and detach. The strip states
the situation and offers none of them. Each needs a decision this phase did not
make. Decide these before you build them:

- May the page push to a file on a person's behalf, or must a person ask?
- What does detaching do to a record that `gx` may be syncing at that moment?

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

## 5. Before you release

Phase 4 is cleanup and does not gate a release. These do:

1. **Rebuild `gx`.** `scripts/build-gx.sh` writes `gx-cli/target/gx`. It is a
   GraalVM native-image build, separate from the desktop's, so building the
   desktop leaves it stale. Compare `gx --version` against the branch before
   you trust any manual test: a `gx` older than the typed open targets does not
   speak the current `show` protocol, and §9 says why that matters.
2. **Check Cmd+Q by hand** after any change to the menu, the close handler or
   the unsaved flag. Nothing automated reaches it.
3. Run the smoke script. It needs a release desktop binary; §7 gives the order.

The manual Cmd+Q check has been done for `ec8bff95`: the dialog appears, the
edit stays on screen, and Save writes the file and then exits.

## 6. Next task: Phase 4

Phase 4 removes the legacy global bridge. It has 5 items, and Phase 2 already
did one of them. Check each against the code before you start:

| Item | State |
|---|---|
| 1. Remove global `DesktopBridge.target` | Not done. The `target` var is still there. |
| 2. Remove global `documentRef` | **Done** in Phase 2 item 5. |
| 3. Remove direct source replacement from generic events | Partly. A text push with no path still replaces the viewer's text. |
| 4. Remove acceptance of unaddressed legacy events | Not done. `DesktopBridge` still listens to the bare `document.changed` name. |
| 5. Update protocol fixtures and architecture documentation | Not done. |

CAUTION: item 3 removes the last path that `/v1/push-text` uses. Confirm that
nothing depends on that command before you remove it.

## 7. Build the desktop in this order

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

## 8. Open questions for the user

1. The repository has 49 local branches. 40 of them hold work that `viewer`
   does not contain, and there are 12 remote branches. Examples of the
   unmerged ones: `desktop-gx-v2`, `wip`, `tmp`, `travel`.
2. The plan document uses two voices. Phase 2 item 7 and the new §15.6 text use
   Simplified Technical English. Most of the document does not.
3. macOS sends a QUIT APPLE EVENT on logout and restart, and a script sends the
   same. It bypasses the app menu, so it bypasses the Quit item that asks, and
   it terminates without a prompt. Tauri exposes no hook for it: the run loop
   reports `Exit` with nothing preventable before it. Cmd+Q and the window's
   red button are safe; logging out with unsaved work is not. Decide whether
   this is worth an upstream issue or a different approach.
4. §8's four resolution actions need two decisions before anyone builds them.
   §3 above lists both. They are product decisions, not code decisions.
5. Nothing watches a bound origin yet. Phase 3 reconciles a change when the
   shell reports one, and the shell reports one only for a file it watches.
   Decide when the desktop starts a watch for a record's origin: at startup for
   every bound record, or when a record is opened.

Three earlier questions are now answered. The plan's `Status:` line reports the
implemented phases. §15.6 requires the acknowledgment for a file open. The
desktop asks before Cmd+Q discards an edit — see §4 for what that took.

## 9. Cautions

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

## 10. Other work in progress

The `/simplify` slice plan continues. Two slices are complete: `viewer/state`
and `gx-cli`. The next slice is `gx-core`. See the memory file
`simplify-slice-plan.md`.

NOTE: `viewer/state` changed a lot in Phase 2, and `gx-cli` lost its
reconciliation engine in Phase 3. Both slices may need a second pass.
