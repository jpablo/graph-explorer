# Desktop Open Targets and Persistence

Status: Phases 0-4 implemented. The deferred items are listed in HANDOFF.md. Reviewed against `31b72b14` (2026-08-22).

Reads with:

- `desktop-gx-v2-architecture.md`
- `sources-and-library-architecture.md`

This document addresses a gap in the desktop open flow: the application does not currently preserve whether an opened target is a library diagram or a loose file. Consequently, an incoming file can replace the source of whichever viewer happens to be active, reactive persistence can write that source into an unrelated library record, and explicit save can write to a different file again.

---

## 1. Problem statement

The current flow is:

1. `gx open <ref>` resolves both loose paths and library references to a filesystem path.
2. The CLI sends `show(path)` and discards any library diagram ID.
3. Rust watches the path and emits `{text, path, revision}` globally.
4. `DesktopBridge` applies the text to whichever `ViewerState` is currently attached.
5. That viewer persists through the project ID in the current browser route.
6. Cmd/Ctrl+S separately writes the source to the last watched path.

There is no path from an open request to a specific library record, and there is no durable identity connecting the active route, persistence owner, event target, and save destination.

This creates several concrete failures:

- Opening a loose file while project A is displayed can persist the loose file's source into record A.
- Opening a bound library diagram loses its diagram ID and does not reliably navigate to that record.
- An initial open event can be dropped while the app is on Home or still starting.
- Reopening a path that Rust already watches does not emit its initial document again: `add_watch` returns the existing descriptor before the event path, so `gx open` can focus the window without changing what it displays.
- `DesktopBridge` can retain a viewer after it unmounts.
- Multiple watched files can all broadcast into one active viewer.
- Library autosave and Cmd/Ctrl+S can write to different destinations.
- Rust can report that a file is “showing” without proof that the page displayed it.

### 1.1 Post-merge baseline

The merge at `31b72b14` improves adjacent behavior but does not close the identity gap:

- CLI reference resolution is now `Either[RefError, Diagram]`. Ambiguous names or origins are reported with the matching diagram IDs and no longer fall through to a same-named filesystem path. The typed open work should reuse this resolver rather than reimplement ambiguity handling.
- `Target.OnDisk` no longer carries an unused `OriginUri`; this does not change the open wire format, which remains `show({path})`.
- `GX_HOME` now gives `gx` and the desktop a shared configurable library/runtime root. Open-target lookup must continue to use that same resolved root. Relative `GX_HOME` values need a cross-process contract: Scala currently absolutizes them against its process working directory while Rust retains a relative `PathBuf`, and the two processes need not have the same working directory.
- The merged viewer-state cleanup does not change `DesktopBridge`, routing, or persistence ownership. The global target and global document reference remain.

The current source anchors are:

- `gx-cli/.../Cli.scala`: `open`, `pathToShow`, and `resolveRef`
- `desktop/src-tauri/src/main.rs`: the `"watch" | "show"` branch and `add_watch`
- `viewer/.../desktop/DesktopBridge.scala`: the global target and document reference
- `viewer/.../Viewer.scala`: route-derived `ViewerState(projectId = ...)`
- `viewer/.../state/Persistence.scala`: unconditional library persistence for non-example viewers

---

## 2. Core decision: the viewer has a typed target

A library diagram and a loose file have different owners and persistence rules. They must be represented explicitly rather than both being forced through `ViewerState(projectId)`.

```scala
enum ViewTarget:
  case LibraryDiagram(id: DiagramId)
  case LooseFile(document: DocumentRef)

case class DocumentRef(
    sessionId: DocumentSessionId,
    path:      String,
    revision:  ContentHash
)
```

The ownership rules are:

- **Library diagram:** the library record is authoritative. Its binding and sync mode govern synchronization with an origin.
- **Loose file:** the file is authoritative. It has no library record and is saved directly with compare-and-swap.
- **Example:** it remains ephemeral and does not participate in project or file persistence.

The active route, persistence owner, event target, and save destination must always identify the same `ViewTarget`.

---

## 3. Preserve target identity through `gx open`

### 3.1 Typed open request

The CLI should retain the result of reference resolution:

```scala
enum OpenTarget:
  case Library(id: DiagramId)
  case File(path: Path)
```

It then sends a typed request rather than only a path.

Library record:

```json
{
  "target": {
    "kind": "library",
    "diagramId": "architecture"
  }
}
```

Loose file:

```json
{
  "target": {
    "kind": "file",
    "path": "/Users/me/work/architecture.dot"
  }
}
```

### 3.2 Library references

An ID or exact library name opens that library record directly. It must not require a binding: an origin-less library diagram is still displayable.

A path matching exactly one binding may resolve to that record. This preserves the convenience of opening an imported file while retaining record identity and metadata.

### 3.3 Ambiguous origins and explicit loose-file intent

The merged CLI already refuses ambiguous references and prints the matching IDs. Preserve that behavior in the typed open resolver; do not replace it with a second, open-specific lookup.

The data model permits several records to bind to one origin. Resolution remains:

- An ID or exact name opens that record.
- A path matching exactly one binding opens that record.
- A path matching multiple bindings reports the matching IDs and refuses to guess.
- `gx open --loose <path>` bypasses library resolution and opens the raw file intentionally.

Only the final `--loose` escape hatch and typed result are new work here. Opening a loose file must never import it implicitly.

---

## 4. The page completes the open operation

Rust currently raises the window after dispatching an event. Event delivery does not prove that the correct viewer mounted or displayed the target.

The open operation should use an acknowledgment handshake:

1. The CLI sends an open request.
2. Rust assigns an `openRequestId`.
3. Rust sends an addressed `ge:open.requested` event.
4. The webview navigates to or mounts the correct viewer.
5. The webview calls a Tauri command such as `complete_open`.
6. Rust raises the window and returns success to the CLI.

Request event:

```json
{
  "requestId": "req-123",
  "target": {
    "kind": "file",
    "path": "/abs/a.dot"
  }
}
```

Completion:

```json
{
  "requestId": "req-123",
  "result": {
    "status": "displayed",
    "view": {
      "kind": "file",
      "sessionId": "doc-42",
      "revision": "sha256..."
    }
  }
}
```

Failures should be typed, including:

- `NO_WINDOW`
- `OPEN_DENIED`
- `DOCUMENT_NOT_FOUND`
- `DIAGRAM_NOT_FOUND`
- `OPEN_TIMEOUT`
- `VIEW_REJECTED`

`gx open` may print “showing …” only after this acknowledgment.

### 4.1 Startup queue

Rust should queue open requests while the webview starts. Once the page registers readiness through a command such as `viewer_ready`, Rust delivers queued requests in order. This prevents the initial event from being lost on Home or before the event listener exists.

### 4.2 Reopening an existing watch

An open operation cannot use “watch was already registered” as evidence that the document is displayed. Today `add_watch` returns an existing descriptor before emitting document text, so a second `gx open` for that path only raises the window.

The typed open request must always reach the page, regardless of whether resource observation was newly installed. Watch registration can remain idempotent, but display activation is a separate operation and must complete the same page acknowledgment handshake every time.

---

## 5. Loose files have a real viewer mode

Add a desktop-only route or equivalent route state:

```scala
enum Route:
  case Home
  case ProjectDetail(id: String, source: Option[String])
  case LooseDocument(sessionId: String)
  case Example(slug: String)
```

The route should contain an opaque session ID, not an absolute path. Raw paths contain private information, are awkward in URLs, and would be retained in browser history.

A `DesktopDocumentRegistry` maps the session ID to the canonical path, current revision, dirty state, conflict state, and source text.

Possible URL:

```text
/documents/<opaque-session-id>
```

---

## 6. Persistence is injected by target type

Refactor viewer construction away from an unconditional `projectId` persistence handle:

```scala
ViewerState(
  target = target,
  persistence = persistence,
  ...
)
```

Use a small persistence interface:

```scala
trait DiagramPersistence:
  def initial: PersistedDiagramState
  def update(state: PersistedDiagramState): Unit
  def saveNow(state: PersistedDiagramState): Future[SaveResult]
  def close(): Unit
```

Implementations:

- `LibraryDiagramPersistence`
- `LooseFilePersistence`
- `EphemeralPersistence`

This makes it impossible for a loose file to persist accidentally through a `ProjectId`.

---

## 7. Loose-file persistence semantics

### 7.1 Source text

Loose-file edits remain in memory while typing. Cmd/Ctrl+S performs a compare-and-swap write:

```scala
saveDocument(path, sourceText, baseRevision)
```

On success, the session advances to the returned revision and clears its dirty marker.

Direct file autosave should not be inherited from library-record persistence. It has different conflict and external-tool implications. An optional loose-file autosave preference can be designed later.

### 7.2 Record-only metadata

A loose file has no durable home for:

- hidden elements
- collapsed groups
- project name
- tags
- notes
- virtual folder

Initially, these values should remain session-local and the UI should label them as not saved. A record-only operation can offer **Add to library**, but must not silently create a record.

Optional sidecar metadata can be considered later if there is a demonstrated need.

### 7.3 External edits and conflicts

A loose session tracks:

- `base`: the revision loaded or last saved
- `local`: the current editor text hash
- `remote`: the current file hash

If the editor is clean, a remote change may reload automatically. If both local and remote changed, the session enters a conflict state and preserves both versions. It must never replace dirty text silently.

### 7.4 Leaving a dirty session

Navigating away from or closing a dirty loose document offers:

- Save
- Discard
- Cancel

Do not rely solely on asynchronous `pagehide` work; IPC completion during teardown is not guaranteed.

---

## 8. Library diagrams reconcile through their records

A library target loads and persists through:

```scala
Library.createProjectPersistence(diagramId, None)
```

A generic origin event must not call `replaceSourceDetectingFormat` directly on a library viewer. Instead, origin changes reconcile against the record's binding:

```text
base   = binding.baseHash
local  = hash(record.text)
remote = hash(origin bytes)
```

Apply the existing `SyncMode` and `SyncState` rules:

- `Pull + Behind` updates the record text and base hash.
- `Push + Ahead` writes the record text to the origin.
- `Sync` moves either direction when safe.
- `Detached` does not synchronize.
- `Diverged` preserves both versions and requires an explicit decision.

For divergence, the UI should offer actions such as:

- Take file version
- Keep library version
- Save library version to file, when the binding permits it
- Detach

Metadata-only changes remain in the record and do not conflict with origin text.

---

## 9. Address document events to sessions

Replace the global `DesktopBridge.target` and global `documentRef` with a session registry:

```scala
final class DesktopDocumentRegistry:
  private val sessions: Map[DocumentSessionId, DocumentSession]

case class DocumentSession(
    id:       DocumentSessionId,
    path:     String,
    revision: ContentHash,
    state:    Var[DocumentState]
)
```

Every loose-file event carries a session ID:

```json
{
  "sessionId": "doc-42",
  "path": "/abs/a.dot",
  "text": "...",
  "revision": "sha256..."
}
```

The registry updates only that session. A mounted viewer subscribes only to its own session.

Library-origin changes should use a distinct event shape:

```json
{
  "kind": "originChanged",
  "origin": "file:///abs/a.dot",
  "revision": "sha256..."
}
```

The library reconciler fans this event out to all records bound to that canonical origin. It does not target whichever page happens to be visible.

### 9.1 Fail-closed compatibility

During rollout, an unaddressed legacy `{text,path,revision}` event may be accepted only when exactly one mounted loose session owns that canonical path. Otherwise, log and ignore it.

An unaddressed event must never mutate the current viewer by default.

### 9.2 Ordering

Include either a monotonic per-session sequence number or sufficient base-revision information to reject stale events that arrive after a newer revision.

---

## 10. Lifecycle ownership

Subscriptions and save destinations must be scoped to the mounted view.

An attachment may return a disposable handle:

```scala
val attachment = DesktopDocuments.attach(sessionId, state)

onUnmountCallback: _ =>
  attachment.dispose()
  viewOwner.killSubscriptions()
```

Preferably, `ViewerState` subscribes to an owner-scoped `DocumentSession` signal so Laminar teardown removes the subscription automatically.

When navigating to Home:

- no viewer remains attached;
- session commands return `NO_SESSION`;
- Cmd/Ctrl+S has no stale destination;
- an explicit open request navigates to a new viewer rather than mutating old state.

`SessionCommands` needs the same detach or owner-scoped behavior.

---

## 11. Save is an operation on the active target

The keyboard handler should not reach through a global JavaScript object. Expose a typed operation on the viewer:

```scala
def save(): Future[SaveResult] =
  persistence.saveNow(snapshot())
```

Behavior by target:

- **Library diagram:** flush record persistence. Any origin push is governed explicitly by its binding and sync mode.
- **Loose file:** compare-and-swap write that session's path.
- **Example:** report that it must be copied to the library before it can be saved.

As a transitional safeguard, compare the active view identity with the proposed save destination and refuse a mismatch.

This eliminates the current split where library autosave updates one record while Cmd/Ctrl+S writes another file.

---

## 12. Multiple watches are not multiple active views

Separate two concepts:

1. **Resource observation:** many files may be watched.
2. **Displayed document:** one target is active in the current window.

Rules:

- A loose-session change updates only that session.
- A bound-origin change reconciles all records bound to that origin.
- A background update does not switch the visible route.
- `gx open` explicitly changes the visible route.
- A plain watch operation never changes the visible route.

Use the canonical origin URI—not a display-path string—as the join key between bindings, the watch registry, and target resolution.

The library/runtime root must come from the shared `GX_HOME` contract. Both processes must resolve a relative value identically—preferably by requiring or normalizing it to an absolute path before either process advertises a socket or scans a library. The open protocol should carry a diagram ID or canonical document identity, not infer identity from whichever library directory a process happened to resolve.

---

## 13. Path privacy

Do not place raw absolute paths in browser URLs, telemetry, or routine console logs.

- Routes contain opaque session IDs.
- Window titles may show a basename.
- The full path may appear in an explicit file-information surface.
- IPC events may carry a path only where the scoped document session requires it.

This change must not reintroduce credentials into the webview or weaken the existing Tauri IPC boundary.

---

## 14. Implementation phases

### Phase 0 — Immediate containment

Before the larger redesign:

1. Add `DesktopBridge.detach(state)`.
2. Detach bridge and session-command targets on unmount.
3. Clear a document reference when its owning viewer detaches.
4. Ignore file events when no live viewer owns the file.
5. Before Cmd/Ctrl+S, require that the active viewer owns the destination.
6. Make `show` re-deliver or activate a document even when its filesystem watch already exists.
7. Stop reporting `gx open` success merely because an event was emitted.

These changes reduce the immediate risk of wrong-record and stale-destination writes.

### Phase 1 — Typed open targets and acknowledgment

1. Add `OpenTarget.Library` and `OpenTarget.File` in the CLI, built on the existing `resolveRef` / `RefError` behavior.
2. Change `show` RPC to accept the typed target.
3. Add `openRequestId`.
4. Add page-ready and open-completion handshakes.
5. Make unbound library records openable.
6. Add `--loose`; ambiguity reporting itself is already implemented.
7. Define and cross-test absolute `GX_HOME` resolution for both hosts.

### Phase 2 — Loose-document sessions

1. Add `DocumentSessionId`.
2. Add the opaque loose-document route.
3. Implement `DesktopDocumentRegistry`.
4. Implement `LooseFilePersistence`.
5. Move Cmd/Ctrl+S to `ViewerState.save()`.
6. Add dirty and external-conflict states.
7. Complete the open handshake for **file** targets. Items 1 to 3 make this
   possible.

   Phase 1 delivered the handshake for library targets only. A library record
   has a route. The page can therefore answer `displayed` correctly. A loose
   file had no route. On the Home route, no viewer received the file. With a
   project open, the page put the file text into a viewer of a different
   record. §1 lists this failure.

   A file `show` therefore keeps the `NO_WINDOW` check only. Until this item is
   complete, `gx open <path>` can report success for a file that no viewer
   shows. §1 lists this failure. §4 gives the rule: `gx open` prints
   "showing ..." only after the page acknowledges the request. The product does
   not meet this rule for file targets.

### Phase 3 — Library-origin reconciliation

1. Index desktop-library records by canonical binding origin.
2. Route origin changes to a reconciler.
3. Apply `SyncState` and `SyncMode`.
4. Persist successful pulls into the record.
5. Surface divergence and missing origins in the UI.
6. Stop raw origin events from changing `ViewerState` directly.

### Phase 4 — Remove the legacy global bridge

1. Remove global `DesktopBridge.target`.
2. Remove global `documentRef`.
3. Remove direct source replacement from generic desktop events.
4. Remove acceptance of unaddressed legacy events.
5. Update protocol fixtures and architecture documentation.

---

## 15. Verification

### 15.1 CLI resolution

- A loose path produces `OpenTarget.File`.
- An ID or exact name produces `OpenTarget.Library`, even when unbound.
- A bound path with one match opens that record.
- A bound path with multiple matches reports ambiguity using the existing `RefError.Ambiguous` path; this behavior already has general CLI coverage and needs an open-target assertion too.
- `--loose` bypasses binding resolution.
- `open` succeeds only after page acknowledgment.
- Relative and absolute `GX_HOME` inputs resolve to the same library/runtime roots in Scala and Rust.

### 15.2 Viewer routing

- Opening a loose file from Home mounts a loose viewer.
- Opening a loose file while project A is displayed does not change record A.
- Opening library B while A is displayed navigates to B.
- An open request received during startup is queued and eventually displayed.
- A failed mount returns an error rather than false success.

### 15.3 Persistence and save

- Loose edits never create or update a library record.
- Loose Cmd/Ctrl+S writes only the loose file.
- Library edits update only the selected record.
- Library Cmd/Ctrl+S cannot write a stale loose-file destination.
- Example edits do not persist.
- A dirty loose file plus an external change produces a conflict without losing either version.

### 15.4 Lifecycle

- Leaving a viewer removes its subscriptions and save destination.
- A file event on Home cannot mutate the previous viewer.
- Repeated navigation does not retain old `ViewerState` objects.
- Session commands on Home return `NO_SESSION`.

### 15.5 Multiple watches and bindings

- Changes to file A cannot alter the viewer for file B.
- A plain watch never changes the current route.
- An explicit open does change it.
- One origin bound to several records reconciles each according to its own mode.

### 15.6 Integration

- `gx open loose.dot` against a cold desktop displays the correct document, and reports success only after the page acknowledges it (§4). The acknowledgment names the document session that displayed it.
- Opening the same loose path again while another view is active reactivates that document even though its watch already exists, completes the handshake again, and lands on the SAME session.
- `gx open <unbound-record>` works.
- The CLI receives an error or timeout if the webview does not acknowledge.
- `GX_HOME` points the CLI and desktop at the same library and runtime roots, including a deliberately tested relative-value policy.
- No raw path is introduced into the route URL.
- No credential is introduced into any page event or IPC payload.

---

## 16. Invariant

The design is correct only if the following invariant always holds:

> The active route, persistence owner, event target, and save destination describe the same typed document identity.

If any of those four can disagree, wrong-record persistence, cross-document event delivery, and stale file saves remain possible.
