# The `gx` library, syncing, watching, and the sandbox

Load this when the task involves the diagram library rather than a loose file on disk, or
when a path is refused by policy.

## What the library is for

The library lives in `~/.graph-explorer/library`. A loose file
needs no library at all — `gx run ./arch.dot list-nodes` works immediately. What importing
adds is a **record**: the stored metadata that makes tags, notes, folders, hidden elements
and collapsed groups possible. That is the whole difference between the document tier and
the record tier.

```bash
gx import ./arch.dot --mode sync --name "Architecture"
gx ls
gx ls --json          # id, name, folder, format, origin, mode
```

Importing the same file twice does not create a second record. It warns
(`already imported as <id>`) and reuses the first — a second record fighting the first over
one file is worse than a duplicate.

## Bindings and sync modes

An imported diagram carries a **binding**: the origin it came from, a mode, and the content
hash both sides last agreed on.

| Mode | The file wins | The library wins | `gx set` writes the file |
|---|---|---|---|
| `pull` (default) | yes | no | no — edits stay local |
| `push` | no | yes | yes |
| `sync` | both directions | both directions | yes |
| `detached` | no origin at all | — | — |

```bash
gx bind <ref> ./arch.dot --mode sync
gx unbind <ref>
gx sync --all --json      # reconcile everything bound
```

`gx sync` reports a state per diagram and **exits 5 if any diverged**. Divergence is a
state, not an error — but a script that just pushed and wants to know whether it landed
deserves a non-zero code.

A binding against a file that does not exist yet is legal: it binds against the diagram's
own text, because the origin is where it is going, not only where it came from.

### The `pull`-mode trap

A `pull` diagram accepts `gx set` and keeps the edit **in the library without writing the
file**. It says so:

```
architecture  saved locally
(Pull does not write back; the origin is unchanged)
```

If you meant to change the file, bind it `push` or `sync`, or address the path directly
rather than the library ref.

## Watching

```bash
gx watch ./arch.dot --json
gx watch --all --json
```

Streams one JSON object per line until killed:

```json
{"event":"changed","origin":"file:///path/arch.dot","hash":"b163c1d6…"}
```

Events are `changed`, `restored`, `deleted`. This is the headless way to react to a
diagram changing — no window anywhere in the picture. **Run it in the background** and read
its output; it never returns on its own. `--interval <ms>` sets the poll and debounce
(default 50).

`--open` is accepted and warned about rather than silently ignored; it needs a desktop.

## The filesystem sandbox

`GX_ALLOWED_ROOTS` and `GX_DENY_ROOTS` (path-separated, like `PATH`) restrict which files
`gx` will touch. `GRAPH_EXPLORER_ALLOWED_ROOTS` / `GRAPH_EXPLORER_DENY_ROOTS` are accepted
as aliases. With no allow-list set, everything outside the built-in denied roots is
allowed.

A refusal is exit **4** with the reason, and it is recorded in
`~/.graph-explorer/runtime/audit.log.jsonl` along with every write and every conflict.

**If a path is denied, do not work around it** — do not copy the file somewhere allowed,
and do not edit the environment. Report the refusal and let the user decide.
