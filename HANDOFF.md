# Handoff: the file is the diagram

Written 2026-11-08, for the branch `gx-simplification` (from `viewer`). The branch is not merged and not released. The last release is `v0.9.5`, which has the old record model.

Read these first: `CONTEXT.md` (the terms), `docs/adr/0001-the-file-is-the-only-copy.md` (the decision) and `docs/plan-the-file-is-the-diagram.md` (the plan, now done).

## 1. State

Each phase of the plan is done. The commits of the branch, oldest first:

| Commit | What it did |
|---|---|
| `26d35b01` | Gave the tiers the names Source, Metadata and Live view, and changed the protocol names with them. |
| `fd5b0897` | The viewer follows the metadata of an open diagram, and holds it as one `Var[DiagramMetadata]`. |
| `4bf5fdba` | Phase 1: the metadata store by file path, the migration plan and its JVM executor, the element names, and ADR 0001. |
| `563de6b2` | Phase 2: `gx` has a verb for each command, and the file comes first. `import`, `bind`, `sync`, `run`, `view` and the other old commands are gone. |
| `8c29e043` | Phase 3a: the library of the desktop is the metadata store, and each diagram opens as its file. The old record model is deleted. |
| `f165e20e` | Phase 3b: a live-view command names its diagram, a desktop save keeps the line endings, and the UI says "arrow". |
| `99689ac1` | Phase 4: the documents, and a sandbox for each smoke script. |
| `29211512` | Fix: a control character after a backslash in a record label goes, with the backslash. |
| `5befc286` | Fix: a backslash typed before a special in a record cell no longer breaks the record. |
| `762147ff` | Fix: a write keeps each `id` attribute, and a copy does not take it. |
| `61b2d7e9` | Fix: each subgraph is a different group. |
| `d838a4dc` | Fix: two sibling subgraphs with one name are one subgraph, as in Graphviz. |
| this change | The documents for the three fixes above. |

Each commit message tells the details of its phase.

Tests:

| Suite | Count | Command |
|---|---|---|
| Scala, all modules | 2610 | `sbt --client testFull` |
| Rust, desktop | 77 | `cd desktop/src-tauri && cargo test` |
| Helper self-test | 33 checks | `scripts/lib/control-api-selftest.sh` |

`gx` and the desktop share the library, so they must ship together, in one release.

## 2. The model

A **diagram** is a DOT or Mermaid file, and the file is the only copy of its source. The **library** is the set of diagrams that have **metadata**: one JSON entry for each file in `$GX_HOME/library/metadata/`, named by the SHA-256 of the canonical `file:` URI of the file. The metadata is the tags, the notes, the hidden elements and the collapsed groups, and it never goes into the file. The **live view** is what a desktop window shows now. `gx` and the desktop read and write the same files and the same entries, and the desktop watches both. The first library command of `gx`, or the first start of the desktop, moves the old records of `library/diagrams/` to the store one time, and keeps the old directory unchanged. The web app still keeps each source in the browser.

- The protocol: `local-protocol/README.md`.
- The commands of `gx`: `gx help`, and `skills/gx/SKILL.md` with `skills/gx/commands.md`.

## 3. Before a release

Do these steps by hand. CI does only a part of §3.2.

### 3.1 Build in this order

WARNING: A wrong build order gives a blank window and an `OPEN_TIMEOUT`. This looks like a broken handshake, but it is not.

1. `sbt "viewer/fullLinkJS" && npm run build`
2. `cd desktop/src-tauri && cargo build --release --features tauri/custom-protocol`
3. `scripts/build-gx.sh` (it writes `gx-cli/target/gx`)

- Build the frontend first. A release build of the desktop embeds the frontend.
- Give cargo the `tauri/custom-protocol` feature. `cargo tauri build` sets it, but `cargo build --release` alone does not. Without it, the webview loads nothing.
- Build `gx` and the desktop from the same commit. A `gx` of `v0.9.5` sends the old method `session` and the old `library` target of `show`, and this desktop refuses both.
- After a tag, run `sbt --client reload`, then `sbt --client "show version"`. Make sure that it prints the new tag before you trust a build (see §4, item 7).

### 3.2 Run the smoke scripts

| Script | Desktop binary |
|---|---|
| `scripts/local-capabilities-open-handshake-smoke.sh` | release |
| `scripts/local-capabilities-release-smoke.sh` | release |
| `scripts/local-capabilities-disk-to-ui-smoke.sh` | release |
| `scripts/local-capabilities-policy-smoke.sh` | debug (the script builds it) |
| `scripts/local-capabilities-limits-smoke.sh` | debug (the script builds it) |
| `scripts/local-capabilities-phase3-smoke.sh` | debug (the script builds it) |
| `scripts/lib/control-api-selftest.sh` | none |

Each script makes its own temporary `GX_HOME` and `GX_DIAGRAMS_DIR`, and removes them at the end. The bash scripts use `gx_sandbox_create` in `scripts/lib/control-api.sh`. No script stops a desktop that you run. CI runs the release, disk-to-UI and self-test scripts, and `scripts/local-capabilities-release-smoke.ps1` on Windows. The open-handshake script runs only on a machine with a window.

CAUTION: A release script on your machine can set the "done" flag of the browser-library copy. Read §4, item 4.

### 3.3 Check by hand

1. **The migration of a real library.** Copy `~/.graph-explorer` to a temporary directory. Set `GX_HOME` to the copy, and `GX_DIAGRAMS_DIR` to a new empty directory. Start the new desktop, and compare its home page with the old library. Read the report, and look in `Recovered/`. Then run `gx ls` with the same two variables. This check also runs the browser-library copy (§4, item 4).
2. **Cmd+Q with an unsaved edit.** The dialog must show, the edit must stay, and Save must write the file and then exit. Only a person can do this check: an AppleScript quit does not go through the menu, and a synthetic key press needs accessibility permissions.
3. **Save and the conflict banner.** A Save of an edit must not show "This file changed on disk" (fixed in `f165e20e`). A change of the file in a different editor while the desktop has an unsaved edit must show it.
4. **The home page.** "New diagram" makes `Untitled.dot` in the folder for new diagrams. "Rename" renames the file and keeps its metadata. "Remove from library" keeps the file. A missing file shows as missing.
5. **The desktop commands of `gx`.** Run `gx open`, `gx select`, `gx selection` and `gx fit` against the release desktop. `gx select` of a file that the window does not show must exit 4, and the message must name the file on the screen.

## 4. Known limits and open defects

Each item below is checked against the code of this branch.

1. **One group name changes after a write.** `subgraph x { cluster=true }` becomes `subgraph "cluster_x"`, and its name in `gx` changes from `x` to `cluster_x`. Graphviz draws the same diagram.
2. **`Documents.create` checks, and then moves.** It moves the temporary file with `REPLACE_EXISTING`. Thus a file that a second process makes between the check and the move is lost. The migration of `gx` uses it. The `create_document` command of the desktop uses a hard link and does not have this window.
3. **`gx remove` while the file is open.** The viewer keeps its metadata. Its next change of any type (an edit of the source too) writes the entry again, with the old hidden elements and collapsed groups, and with no tags or notes. This occurs only when the viewer holds hidden elements, collapsed groups, or a manual language mode.
4. **The "done" flag of the browser-library copy is in the localStorage of the webview** (`graph-explorer.migrated-to-disk`). `GX_HOME` does not move it. A sandboxed desktop on your machine (a smoke script, or check 1 of §3.3) can copy your browser diagrams into its sandbox, set the flag, and then remove the sandbox. Your real desktop then never copies them. `v0.9.5` used the same key, so this can occur only when no earlier desktop copied the browser library of the webview. To prevent it, start a real desktop one time first.
5. **A change from `gx` can still be lost while the desktop writes metadata.** The desktop has no 400 ms wait now: each change of the metadata writes the entry at once. But the write merges the fields of the viewer onto the copy of the entry in memory. That copy sees a `gx` change only after the poll of the shell (400 ms) and a new read of the list. A change in the desktop in that interval writes over the change from `gx`. No message shows, because the shell watcher takes the result as a write of the page. Neither side compares before it writes, so a `gx` write can also write over a desktop write that comes between the read and the write of `gx`.
6. **`gx selection` exits 0 with refs** when the window shows an example or a browser diagram. The JSON has `"path": null`, and stderr says that the window shows no file.
7. **The version string of a local build comes from the start of the sbt server.** `sbt-dynver` calculates it one time, when the project loads. Now the server reports `0.9.5+14-fd5b0897`, four commits before the tip. Run `sbt --client reload` after a tag.
8. **A quit Apple event discards an unsaved edit** (older than this branch). macOS sends one at logout and at restart. It does not go through the Quit item of the menu, and Tauri gives no hook. Cmd+Q and the close button of the window ask first.
9. **An arrow to a subgraph does not reach the nodes of its inner subgraphs** (older than this branch). For `x -> subgraph s { subgraph t { a } b }`, Graphviz makes `x -> a` and `x -> b`, but the Graphviz port of `gx` makes only `x -> b` (`AttrResolver` in `graphviz/shared/.../model/Resolved.scala`). Thus a `gx` write drops `x -> a`, with no message. The browser viewer uses the real Graphviz and is correct.
10. **The id of a group can move to a different subgraph.** When the names of two subgraphs give one id (`clusterB` and `cluster_B` give `B`), the first gets `B` and the second gets `B_2`. If a person deletes the first subgraph, the second gets `B` on the next read, and the metadata of `group:B` (a collapsed group, for example) then applies to it.

Small cleanups, not defects:

- The shell still sends each document event two times, also with the old name `document.changed`. Nothing listens to it.
- The shell passes on `OPEN_DENIED` from the page, but the page never sends it. The page does not call `library_read`.
- The socket methods `watch`, `unwatch`, `get-document`, `put-document` and `push-text` stay for the smoke scripts. `gx` does not send them.
- A desktop that is starting answers `STARTING`, and `gx` gives exit 4 for it, not exit 2.
- A write gives each subgraph `label=""` and `cluster="true"` when the source has neither. Graphviz draws the same diagram, but the diff is larger.
- The Graphviz port does not print the warning "Duplicate cluster name" for two clusters with one name under different parents.

## 5. Cautions

- WARNING: A debug desktop loads its page from the dev server (`npm run dev`) when one runs. At its first start, that page moves the library of its `GX_HOME`. Set `GX_HOME` and `GX_DIAGRAMS_DIR` before you start a debug desktop for a test.
- WARNING: Do not run `sbt scalafmtAll`. `.scalafmt.conf` sets `maxColumn = 140`, and the sources wrap near 78. The task changes hundreds of files, `graphviz/` too. Match the file that you edit by hand.
- Do not simplify `graphviz/`. It is a transcription of the C source of Graphviz 13.0.1, and `CorpusByteExactSpec` pins its output byte for byte. A corpus diff is a regression, never a new baseline.

## 6. History

The handoff of the earlier work (desktop open targets, library records, reconciliation, the divergence strip) is in git: `git show 26d35b01:HANDOFF.md`. Its plan, `docs/desktop-open-targets-and-persistence.md`, and `docs/sources-and-library-architecture.md` are superseded by ADR 0001.
