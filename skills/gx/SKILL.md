---
name: gx
description: Read, query and edit graph diagrams from the command line with `gx`, the Graph Explorer CLI — list a DOT or Mermaid file's nodes/edges/groups, set attributes, group and hide elements, keep a file synced with the diagram library, and show it in the Graph Explorer desktop app. Use whenever a task involves .dot/.gv/.mmd diagram files, asks what a diagram contains, asks to restyle or restructure one, or asks to open/visualize a graph.
license: Apache-2.0
compatibility: Requires the `gx` binary on PATH (Graph Explorer; download from https://github.com/jpablo/graph-explorer/releases). Everything except `gx open` and `gx session` runs headless — no desktop app and no display needed.
---

# Driving Graph Explorer with `gx`

`gx` is a single native binary. It edits diagram files **structurally** — it parses
DOT/Mermaid into a graph, applies a named command, and writes the graph back — so you can
say "set `fillcolor` on these three nodes" without writing a regex over someone's DOT.

**Everything except `gx open` and `gx session` works with no GUI running.** Do not start
the desktop app to inspect or edit a diagram.

## Before anything else

```bash
gx --version
```

If that fails, `gx` is not installed — say so and point at
<https://github.com/jpablo/graph-explorer/releases> (the `gx-vX.Y.Z-<platform>` asset).
Do not try to build it from source unless asked; it is a GraalVM native image and the
build is not quick.

Then, once per task:

```bash
gx status
```

It prints the library root, how many diagrams are in it, and whether a desktop is up. It
never fails for want of a desktop.

## The one distinction that matters: a path vs. a library ref

Every command takes a `<ref>`, and a ref is resolved in this order:

1. a **library diagram id** (`gx ls`),
2. an exact **library diagram name**,
3. a **path** to a file on disk.

Ambiguity is reported, never guessed at.

A loose file on disk needs no setup — `gx run ./arch.dot list-nodes` works immediately.
But **the record tier only exists for library diagrams**; on a loose file those commands
refuse with exit 4 and tell you to `gx import` it first.

## Three tiers

| Tier | Verb | Operates on | Needs a desktop? |
|---|---|---|---|
| Document | `gx run <ref> <cmd>` | the diagram **text** — nodes, edges, attributes | no |
| Record | `gx run <ref> <cmd>` | stored **metadata** — hidden elements, tags, folder | no (library only) |
| Session | `gx session <cmd>` | the **live view** — selection, viewport | **yes** |

`gx run` accepts document and record commands interchangeably; the tier decides what gets
written, not how you spell the call.

```bash
gx run --list        # every headless command name
gx session --list    # the five live-view commands
```

## Reading a diagram

```bash
gx run ./arch.dot list-nodes
gx run ./arch.dot list-arrows
gx run ./arch.dot list-groups
gx get ./arch.dot                 # the raw text
gx get ./arch.dot --json          # text + path + content hash
```

Output is columns for a human, JSON with `--json`. Real output:

```
$ gx run demo.dot list-arrows
arrow:api->db/0              source=node:api  target=node:db
arrow:web->api/1             source=node:web  target=node:api
```

### Never construct an element ref — read it

Refs are `node:<id>`, `arrow:<id>`, `group:<id>`, and only node refs are guessable.
Arrow ids carry a disambiguating index (`arrow:api->db/0`) and group ids are **not** the
DOT cluster name (`subgraph cluster_svc` → `group:svc`). Always get refs from
`list-nodes` / `list-arrows` / `list-groups` first, then pass them verbatim.

A malformed ref is a **usage** error (exit 1), and every bad one in a batch is reported at
once rather than one per round trip:

```
gx: get-attributes: unknown element kind 'nde' in 'nde:a' (expected node, arrow, group)
```

A ref that is well-formed but names nothing is exit **4**: `gx: get-attributes: no such
element: node:nope`.

## Editing a diagram

Mutations take their arguments as a JSON object:

```bash
gx run ./arch.dot set-attribute \
  --params '{"targets":["node:api","node:db"],"name":"fillcolor","value":"lightblue"}'
```

For anything with quoting in it — an HTML label, a long node list — pipe it instead:

```bash
jq -n '{targets:$t,name:"label",value:$v}' --argjson t '["node:api"]' --arg v '<<b>API</b>>' \
  | gx run ./arch.dot set-attribute --stdin
```

You can also replace the whole text:

```bash
gx set ./arch.dot --stdin < new.dot
gx set ./arch.dot --text 'digraph G { a -> b }'
```

### ⚠️ A document mutation reformats the whole file

`gx run <file> <mutation>` parses the file and **prints the graph back out in Graph
Explorer's canonical form**. It is not a surgical patch. Every identifier gets quoted,
indentation is normalized, and edges move inside the cluster that owns them:

```dot
digraph G {                              digraph "G" {
  subgraph cluster_svc {                   graph [label=""];
    label="services"; api; db     ──►      subgraph "cluster_svc" {
  }                                          graph [label="services", cluster="true"];
  api -> db [label="reads"]                  "api"; "db";
}                                            "api" -> "db" [label="reads"];
                                           }
                                         }
```

The graph is preserved; the formatting, comments and layout of the source are not. So:

- **On a file under version control, show the diff before committing** — the first
  mutation on a hand-written file is mostly reformatting noise.
- If the user wants minimal edits to a hand-maintained file, edit the text directly and
  use `gx run` only for *queries*.
- Work on a copy when the source file is precious.

The graph's **name and kind are preserved**: `graph MyNet { a -- b }` stays undirected and
stays `MyNet`. (Both were silently rewritten to `digraph "G"` before v0.9.5 — on an older
`gx`, check the diff after any mutation on an undirected graph.)

## Conflict safety

Writes are compare-and-swap on the file's content hash. If the file changed underneath
you, the write is refused with **exit 5** rather than clobbering it:

```
gx: conflict — /path/arch.dot changed underneath this write
gx:   expected 000000000000, found b163c1d6bdfb
```

`gx get --json` gives you the current `hash`; pass it as `--base <hash>` to make a write
conditional on it. Without `--base`, `gx` reads the hash itself immediately before
writing, which is enough for ordinary sequential use.

## Showing it to the user

```bash
gx open ./arch.dot
```

This is the only reason to need the desktop app. If none is running it exits **2** and
says so — that is not a failure of the task, it is a missing window. Report it and carry
on with the headless work.

`gx session select --params '{"targets":["node:api"]}'` and friends act on whatever is
currently on screen — there is no `<ref>`, because the live view already knows what it is
displaying.

## Gotchas

- `--json` on every command. Parse that, not the column output, which is formatted for
  people and may add or drop columns.
- `get-attributes` includes `_gvid`, an internal layout id. Ignore it; do not set it.
- `gx status` reports `watching N files`, which is what the desktop is *following on
  disk* — not what is on screen, and not what you just imported.
- A `gx` before v0.9.5 wrote a synthetic `fillstyle="true"` into files it edited, and then
  could not re-read them (`could not parse the diagram: assertion failed`). Current `gx`
  reads those files fine, but drops the attribute — as `dot` does — so a node that looked
  filled loses its fill. Re-apply it with `style` + `fillcolor` if you see one.
- A `pull`-mode diagram accepts `gx set` and keeps the edit in the library **without
  writing the file**. It says so (`saved locally`). If you meant to change the file, bind
  it `push` or `sync`, or write the path directly.
- `gx skill` prints where this skill lives, pinned to the running binary. Re-read it after
  a `gx` upgrade — command names and params are versioned API.

## Additional resources

- Every command name, its parameters, and the exit codes: [commands.md](commands.md).
  Load it before issuing any command whose params you are not certain of.
- Importing, binding, sync modes, watching, and the filesystem sandbox:
  [library.md](library.md). Load it when the task involves the library rather than a
  loose file on disk, or when a path is refused by policy.
