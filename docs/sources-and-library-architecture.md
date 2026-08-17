# Sources and Library Architecture

Status: Proposed (2026-08-16)
Reads with: `desktop-gx-v2-architecture.md` (process architecture and trust
boundary — this document assumes its D1 content-hash revisions and D2 `gx-core`)

v2 answered *who owns the files*. This answers *what a diagram is*.

---

## 1. The idea

A diagram is no longer a blob of text in `localStorage`. It is a **record** with
a **name**, **metadata**, and optionally a **binding** to an **origin** — an
external thing identified by a URI that the text can be pulled from and/or
pushed to.

`file:` is the first scheme. `https:` and database connections are the reason
the abstraction is a URI rather than a path.

The motivating flow, stated concretely:

```bash
gx import ~/work/arch.dot --mode pull
# an LLM, a script, a build step rewrites arch.dot
# → the diagram re-renders in the desktop, with no window in the loop
```

---

## 2. Vocabulary

One naming decision first, because the obvious word is taken.

`PersistedDiagramState.source` (`Persistence.scala:170`) already exists and
already means **the diagram text**. It is not the thing this document calls a
source. To avoid a field name that means two opposite things:

| Term | Meaning |
|---|---|
| **text** | the DOT/Mermaid source code (today's `source` field, renamed) |
| **origin** | the external thing the text came from, identified by a URI |
| **binding** | the relationship between a diagram and an origin, with a sync mode |
| **library** | the collection of diagram records, with a folder tree |

---

## 3. Data model

```scala
case class Diagram(
  id:        DiagramId,          // primary key: stable, internal, never derived
  name:      String,
  folder:    FolderPath,         // virtual, e.g. /work/architecture
  format:    DiagramFormat,
  text:      String,             // the store's current copy
  binding:   Option[Binding],
  metadata:  DiagramMetadata,    // hidden elements, collapsed groups, tags, notes …
  createdAt: Long,
  updatedAt: Long
)

case class Binding(
  origin:     OriginUri,         // canonical (§4)
  mode:       SyncMode,          // Detached | Pull | Push | Sync
  baseHash:   ContentHash,       // the content both sides last agreed on
  lastSyncAt: Long
)
```

### 3.1 Identity: `DiagramId` is the key, origin is an attribute

Tempting to key by origin URI. Don't:

- diagrams created in the app have no origin
- one file may legitimately back several records (different metadata, different
  folded state, different notes)
- files get renamed and moved; a primary key must not

So `DiagramId` is the key, and origin is an **indexed attribute**. Uniqueness on
origin is a *policy* (warn on re-import, offer "open existing") rather than a
structural constraint.

Where URI-keying does pay is the **watch registry**: one watcher per canonical
origin URI, fanning out to every binding that references it. Two diagrams on one
file means one poller, not two.

### 3.2 Text is stored in both places, deliberately

The text lives in the record *and* at the origin. This is a cache with explicit
reconciliation, not an accident:

- `Detached` bindings and origin-less diagrams need text with no origin
- the origin can be missing, offline, or slow; the diagram still renders
- §5's conflict detection needs a local copy to compare against

The analogy is a git working tree against a remote: divergence is a normal,
representable state, not a failure.

---

## 4. Origins are URIs, and canonicalization is load-bearing

### 4.1 Scheme capabilities

Sync direction is not a free choice — it is bounded by what the scheme can do.

| Scheme | read | write | watch | Legal modes |
|---|---|---|---|---|
| `file:` | ✓ | ✓ | ✓ (poll) | Detached, Pull, Push, Sync |
| `https:` | ✓ | ✗ | ~ (poll + ETag) | Detached, Pull |
| `postgres:` etc. | ✓ | ? | ✗ (poll) | Detached, Pull |

A scheme registers `{canRead, canWrite, canWatch}`; a binding's mode is
validated against them at bind time and rejected with a clear message, not
silently downgraded.

### 4.2 Canonicalization

**This repo has already lost five months to URI handling** — the percent-decoding
bug documented at `main.rs:921-934`. That was URIs as an incidental detail. Here
they are the join key between the library, the watch registry, and the CLI, so
the hazard is now central.

One function produces a canonical `file:` URI, and every call site uses it:

1. **Resolve relative paths in `gx`, against the user's cwd** — never in the
   desktop, whose cwd is an artifact of how it was launched. (v1 already learned
   this: `gx/src/main.rs:233-247`.)
2. **`fs::canonicalize`** — resolves `..` and symlinks, and on macOS returns the
   true on-disk casing. That last part matters more than it looks: APFS is
   case-insensitive by default, so `/Users/x/A.dot` and `/Users/x/a.dot` are
   *one file with two spellings*. Without step 2, importing both creates two
   records that fight each other in `Sync` mode.
3. **Encode per RFC 8089** through one function. Never string-concatenate a URI,
   never hand-roll a decode.
4. **Windows**: strip the `\\?\` verbatim prefix, normalize separators, handle
   UNC. Every one of these characters is percent-encoded by a naive encoder —
   this is exactly what broke v1.

Invariant to test directly: `canonical(canonical(u)) == canonical(u)`, and two
spellings of one file produce one URI. Include a path with a space, a
non-ASCII path, a symlink, and a case variant.

---

## 5. Sync

### 5.1 Modes

| Mode | Direction | Use |
|---|---|---|
| `Detached` | none | imported once; origin kept for provenance |
| `Pull` | origin → diagram | the LLM/generator flow. UI edits are local-only until rebound (§5.3) |
| `Push` | diagram → origin | the app is the author; the file is output |
| `Sync` | both | genuine two-way editing |

### 5.2 Conflict detection is a three-hash comparison

I flagged bidirectional conflict as the hardest problem here. It is much smaller
than it looks, because v2's D1 already gives every participant an agreed content
identity. Compare three hashes:

- `base` = `binding.baseHash` — what both sides last agreed on
- `local` = `hash(diagram.text)` — what the store has now
- `remote` = `hash(origin content)` — what the origin has now

| `local` | `remote` | State | Action |
|---|---|---|---|
| `= base` | `= base` | **InSync** | nothing |
| `≠ base` | `= base` | **Ahead** | push (auto in Push/Sync) |
| `= base` | `≠ base` | **Behind** | pull (auto in Pull/Sync) |
| `≠ base` | `≠ base`, `local ≠ remote` | **Diverged** | surface it; user resolves |
| `≠ base` | `≠ base`, `local = remote` | **Converged** | advance `base`, no I/O |

This is git's fetch/status model, and it is worth copying rather than inventing.
Two consequences:

- **No merge UI is needed for v1 of this.** What is needed is an accurate,
  visible per-diagram status and two explicit actions: *keep mine* / *take
  theirs*. Merge can come later, or never.
- **`Diverged` is a state, not an error.** The file is not written, nothing is
  lost, and the diagram keeps rendering the local text. The LLM-writes-while-you-
  edit case becomes a badge, not a data-loss event.

The last row matters more than it seems: an LLM regenerating a file to
byte-identical content must not register as a conflict.

### 5.3 Editing a `Pull` diagram: local-only until rebound

**Decision:** UI edits to a `Pull`-mode diagram are kept in the store and never
written to the origin. The diagram is not read-only, and edits are not
discarded.

The question this leaves is the one that matters: *when the origin changes and
there are local edits, does the pull happen anyway?*

**No. Divergence blocks the auto-pull, visibly.** Silently overwriting the
user's work is the one outcome with no recovery. `Diverged` shows as a badge on
the diagram with two explicit actions:

- **Discard mine** — reset `local` to `remote`, advance `base`, resume following
- **Keep mine** — promote the binding to `Detached` (or `Sync`, if the user
  wants the edits to flow back). Following stops until they rebind

This would be an unacceptable amount of friction — a stray keystroke silently
halts the live-update flow — except that most interaction with a generated
diagram never touches the text at all.

#### 5.3.1 Two classes of edit, and only one of them conflicts

The codebase already draws this line:

| Edit | Where it lives | Conflicts with origin? |
|---|---|---|
| Hide element, fold group | `hiddenElements`, `collapsedGroups` — *"a view setting, saved with the page"* (`Persistence.scala:166-168`) | **No.** Survives every pull |
| Pan, zoom, layout, theme | `ViewerSettings` | **No** |
| Rename, folder, tags, notes | record metadata | **No** |
| Node/edge attributes | `AttributesOps` → round-trips into the DOT text | **Yes** |
| Typing in the code editor | the text | **Yes** |

So exploring a generated diagram — folding clusters, hiding noise, moving
around — is conflict-free by construction. Only editing the *code* stops the
flow, which is the case where stopping is correct.

This makes the metadata/text split load-bearing rather than incidental. New
features should be asked which side of the line they fall on: anything that can
live in metadata rather than in the text keeps the following flow alive.

#### 5.3.2 Stale element references must be retained, never pruned

`hiddenElements` and `collapsedGroups` hold element IDs. When the origin
regenerates, some of those IDs may not be in the new text.

**Keep them anyway.** A dangling reference is inert while the element is absent
and reapplies when it returns. Pruning on miss looks tidier and is much worse:
an LLM regenerating a file would destroy the user's folds on every run, and the
diagram would spring open a dozen clusters each time the generator ran. That
single behavior would make the primary flow unpleasant enough to abandon.

The cost is unbounded growth of stale IDs across many regenerations. Acceptable:
they are strings, and a "clear hidden/folded state" action already makes sense
on its own terms.

### 5.4 Watch fan-out

One watcher per canonical origin URI. On a change:

1. read + hash the origin
2. suppress if the hash matches a recent self-write (v2 §7 — this is what
   prevents write → watch → push → write)
3. for each binding on that URI, evaluate §5.2 and act per its mode

A file backing three diagrams polls once and fans out to three.

---

## 6. Store layout

```
~/.graph-explorer/
  library/
    folders.json              # the virtual folder tree (incl. empty folders)
    diagrams/
      <diagram-id>.json       # one record: text + metadata + binding
  runtime/
    control.sock              # v2 D4
    audit.log.jsonl
```

**One file per diagram, on purpose.** `gx` and the desktop are separate
processes writing concurrently; a monolithic library file would make every
unrelated edit a write conflict. Per-record files mean two processes editing
different diagrams never collide, and each record write reuses `gx-core`'s
existing atomic-write path.

**The listing is derivable.** Everything needed to render the library can be
recovered by scanning `diagrams/`. `folders.json` holds only what scanning
cannot recover — the tree's shape, including empty folders and ordering. A lost
or corrupt `folders.json` costs organization, never content.

**Store records are origins.** There is exactly one way to observe anything: a
resource observer keyed by canonical URI, emitting `(uri, contentHash)`. A store
record at `file:///~/.graph-explorer/library/diagrams/<id>.json` and a diagram
at `file:///Users/x/arch.dot` are both `file:` resources, and the observer does
not distinguish them. What varies is the **scheme's driver** — how you observe
(inotify, poll, ETag) — never the *purpose*.

So "the store is watched" needs no new capability: it falls out of the `file:`
driver that origins already require. Subscribers differ, which is ordinary — an
origin change runs §5.2 reconciliation; a record change means another process
edited the library, so reload it.

Two consequences:

- **`gx import` reaches the UI with no message sent.** The desktop observes the
  new record. The socket (v2 D4) is not involved in state propagation at all —
  it exists for the session tier, where queries need answers.
- **Cross-process self-write suppression falls out for free.** The desktop
  writes a record → its own observer suppresses by hash. `gx` writes it → the
  desktop's observer fires, because that hash was never in *its* recent-write
  table. Exactly the desired behavior, with nothing special-cased.

---

## 7. Library organization

**The folder tree is virtual.** Mirroring the filesystem breaks the moment
origins include URLs and database connections — there is no shared tree to
mirror. So folders are the user's own hierarchy, and *"group by origin"* is a
view over it, not the storage model.

`gx import <directory>` is a distinct operation: walk for `.dot`/`.mmd`, create
one binding each, and mirror the directory structure into virtual folders **at
import time**. After that the two are independent. This is also the answer to
the brief §5 gap that there is no notion of a workspace.

---

## 8. `gx` surface

Building on v2's D5 and D7.2 — every command below is **document** or **record**
tier, so all of them work with no desktop running. `gx` is Scala compiled with
native-image (v2 D2), so it holds the parser and graph model directly; `open` is
the one session-tier command here, and the only one needing a window.

```
gx import <path|uri> [--mode pull|push|sync|detached] [--folder /a/b] [--name N]
gx ls [--folder /a/b] [--origin] [--json]
gx get <diagram-ref>                  # ref = id, name, or origin URI
gx set <diagram-ref> --stdin
gx bind <diagram-ref> <uri> --mode M
gx unbind <diagram-ref>
gx sync [<diagram-ref>] [--pull|--push]    # one-shot reconcile; --all
gx watch [<diagram-ref>…] [--all]          # long-lived; change stream on stdout
gx status [--json]                          # per-diagram sync state (§5.2)
gx open <diagram-ref>                       # the only command needing a window
```

`--mode pull` is the default for `import`, since the generator flow is the
motivating case.

---

## 9. Migration

The existing `localStorage` library must survive. On first v2 desktop launch,
offer a one-time import of `graph-explorer.projects` and its payloads into the
disk store, as origin-less (`Detached`) records.

Two cautions:

- the web app keeps its own `localStorage` library, unsynced and unchanged. It
  has no filesystem; that is the whole reason it exists
- migration code touches persistence while a dev server may be hot-reloading
  real data. `ProjectsStorage` already carries scar tissue here (the
  `updateDirectory` empty-index guard, `guardedProjectName`). Migration must be
  idempotent and must never write an empty library over a populated one

---

## 10. Phasing

Interleaves with the v2 phases; P0–P3 there are prerequisites (P0 is the
blocking native-image gate).

**S1 — Record + store.** `Diagram`, `FolderPath`, `DiagramMetadata`; on-disk
layout; `gx-core` read/write with atomic writes. No origins yet. Migration from
`localStorage`. Rename `source` → `text`.

**S2 — Origins and canonicalization.** `OriginUri`, the scheme registry, the
canonicalization function and its test suite (§4.2). `gx import`, `gx bind`,
`gx ls`. Mode `Detached` only — provenance recorded, nothing synced.

**S3 — Pull.** Watch fan-out, three-hash status, `Pull` mode, `gx sync --pull`,
`gx watch --all`. **This is the point where the motivating flow works.**

**S4 — Push and Sync.** `Push`, `Sync`, divergence surfaced in the UI, keep-mine
/ take-theirs.

**S5 — Folders and workspaces.** Virtual tree in the library UI, `gx import
<directory>`, group-by-origin view.

**S6 — A second scheme.** `https:` pull-only. The real test of whether the URI
abstraction earned its keep; do it before anyone believes it did.

---

## 11. Open questions

1. **Sidecar metadata** was deferred (store-side chosen). Revisit when someone
   wants annotations in git.
2. **Does `gx watch --all` want to be `gx serve`?** A long-lived daemon that
   holds all watches is a different shape from a foreground process per
   invocation, and changes what "no desktop running" means.
3. **What happens to a binding when the origin file is deleted or moved?** §5.2
   has no row for "origin gone". Options: `OriginMissing` status and keep the
   text, or offer to re-bind. Related to v2's V-06.
4. **Format detection on pull** — the file may change language (`.dot` rewritten
   as Mermaid). Re-detect per pull, or pin at bind time?
