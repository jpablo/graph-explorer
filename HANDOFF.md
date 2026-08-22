# Handoff: desktop open targets and persistence

Written 2026-08-22. Read `docs/desktop-open-targets-and-persistence.md` first.
That document is the plan. This file records the state of the work.

## 1. State

Phases 0, 1 and 2 are complete.

Phase 2 is on branch `phase-2-loose-document-sessions`, and
`origin/phase-2-loose-document-sessions` holds it. Three commits, split by
domain because the seven items edit the same files in turn:

- `6709b128` the page: targets, sessions, persistence, save, conflicts
- `c676d432` the shell: a file open waits for the page
- the documents, including this file

Read the two code commits before you change either half. Their messages hold
the reasoning, and a message cannot rot the way this file can.

The branch starts at `d85e4c99`, which is where `viewer` and `origin/viewer`
still point. **The branch is not merged.** Merge it, or continue on it, before
you start Phase 3.

All tests pass on the branch.

| Suite | Count | Command |
|---|---|---|
| Scala, all modules | 2245 | `sbt --client testFull` |
| Rust, desktop | 47 | `cd desktop/src-tauri && cargo test` |
| Open handshake | 5 checks | `bash scripts/local-capabilities-open-handshake-smoke.sh` |

The smoke script needs a release desktop binary. Read §4 for the build order.

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

## 3. Next task: Phase 3

Phase 3 moves origin reconciliation into `gx-core`. Do this to give the desktop
a capability it does not have.

The evidence:

- `syncOne` is a private method of `gx-cli/.../Cli.scala`.
- `viewer/.../LibraryMapping.scala` writes `binding = previous.flatMap(_.binding)`.
  A save from the app carries the binding forward and does not change it.
- No file under `viewer/src/main` names `SyncState`, `SyncMode`, or `baseHash`.

Result: an edit in the app changes the record text and does not change
`baseHash`. The app never writes the origin file. The record becomes `Ahead`.
The next `gx sync` pushes the edit. The edit is not lost. The edit waits for
the command line.

Move the CRLF line-ending rule with the engine. `syncOne` hashes the record
text with the origin file's line ending. That rule belongs beside `Documents`
and `Hashing`, not in a command-line tool. See commit `790605f3`.

CAUTION: Phase 2 item 6 compares document TEXT to decide whether an editor is
dirty. Phase 3 hashes record text. Keep one line-ending rule between them, or a
file will read as dirty because of its line endings alone.

## 4. Build the desktop in this order

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

## 5. Open questions for the user

1. The repository has 49 local branches. 40 of them hold work that `viewer`
   does not contain, and there are 12 remote branches. Examples of the
   unmerged ones: `desktop-gx-v2`, `wip`, `tmp`, `travel`.
2. The plan document uses two voices. Phase 2 item 7 and the new §15.6 text use
   Simplified Technical English. Most of the document does not.
3. Phase 2 guards a navigation away from an unsaved file, and it registers a
   `beforeunload` handler for the window closing. The desktop cannot use that
   guard: `desktop/src-tauri/src/main.rs` names no `CloseRequested`,
   `ExitRequested` or `on_window_event`, so the shell quits without asking the
   page. Decide whether the desktop should ask first. §7.4 wants the question,
   and only the shell can hold the answer.

Two earlier questions are now answered. The plan's `Status:` line reports the
implemented phases. §15.6 requires the acknowledgment for a file open.

## 6. Cautions

- WARNING: If a development server runs, do not edit `Persistence.scala`,
  `ProjectsStorage`, or `ThumbnailDiskCache`. A server with hot reload writes
  the real library. No server runs now.
- WARNING: Do not run `sbt scalafmtAll`. `.scalafmt.conf` sets
  `maxColumn = 140`, and the sources wrap scaladoc near 78. The task reformats
  about 340 files and touches `graphviz/`. Match the file you edit by hand.
- The `show` RPC has no legacy format. `gx` and the desktop must ship together.
- Do not simplify `graphviz/`. That module is a transcription of Graphviz
  13.0.1 C source. Idiomatic Scala breaks the byte-exact output.
- A loose file reaches the page ONLY through the desktop shell. The registry is
  empty in a browser, so `/documents/<id>` there shows "No such document
  session". That is correct, and it is not a bug to chase.

## 7. Other work in progress

The `/simplify` slice plan continues. Two slices are complete: `viewer/state`
and `gx-cli`. The next slice is `gx-core`. See the memory file
`simplify-slice-plan.md`.

NOTE: `viewer/state` changed a lot in Phase 2. Its slice may need a second pass.
