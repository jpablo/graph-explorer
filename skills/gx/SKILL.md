---
name: gx
description: Read and change DOT and Mermaid diagram files from the command line with `gx`, the Graph Explorer CLI — list the nodes, arrows and groups of a file, set attributes, group nodes, hide and tag elements, keep a library of diagrams, and show a file in the Graph Explorer desktop app. Use whenever a task involves .dot/.gv/.mmd diagram files, asks what a diagram contains, asks to restyle or restructure one, or asks to open/visualize a graph.
license: Apache-2.0
compatibility: Requires the `gx` binary on PATH (Graph Explorer; download from https://github.com/jpablo/graph-explorer/releases). Only `gx open`, `gx select`, `gx selection` and `gx fit` need the desktop app; every other command runs headless, with no display.
---

# Driving Graph Explorer with `gx`

`gx` is one native binary. It edits a diagram file **structurally**: it parses the DOT or
Mermaid text into a graph, makes the change, and writes the graph back. Thus you can say
"set `fillcolor` on these three nodes" and you do not write a regex over the DOT text.

**A diagram is a file.** Each command takes the file first. The file is the only copy of
the source. There is nothing to import, bind or sync.

**Only the desktop commands need the desktop app** (`open`, `select`, `selection`, `fit`).
Do not start the desktop app to read or change a diagram.

## Before anything else

```bash
gx --version
```

If that fails, `gx` is not installed. Say so, and point at
<https://github.com/jpablo/graph-explorer/releases> (the `gx-vX.Y.Z-<platform>` asset).
Do not build it from source unless the user asks. It is a GraalVM native image, and the
build is slow.

Then, one time for each task:

```bash
gx status
```

It prints the library folder, the number of diagrams in it, and the state of the desktop.
It exits 0 also when no desktop runs.

## Four kinds of command

| Kind | Commands | Writes | Needs a desktop |
|---|---|---|---|
| Read | `nodes`, `arrows`, `groups`, `attrs` | nothing | no |
| Change | `set`, `reset`, `group`, `ungroup`, `combine-record`, `split-record`, `transpose-record` | the **file** | no |
| Metadata | `hide`, `unhide`, `collapse`, `expand`, `tag`, `untag`, `note`, `info`, `ls`, `add`, `remove` | the **library**, never the file | no |
| Desktop | `open`, `select`, `selection`, `fit` | nothing | **yes** |

Add `--json` to each command that prints a result. Parse the JSON, not the columns. The
columns are for people, and they can change.

## Element names

Each command names elements the way that `gx nodes`, `gx arrows` and `gx groups` print
them. **Read the names from those commands, and copy them.**

| Name | Means |
|---|---|
| `api` | the node `api` |
| `api..db` | each arrow from `api` to `db` (also in an undirected graph) |
| `api..db#2` | the second arrow from `api` to `db`, in the order that `gx arrows` prints |
| `cluster_svc` | the group that the source calls `cluster_svc` (a DOT cluster, or a Mermaid subgraph id) |
| `node:x`, `group:x`, `arrow:…` | the same with a prefix. Use a prefix only when a node and a group have the same name |

Write an arrow as `api..db`, **never as `api->db`**. A shell reads `>` as a redirect, and
`gx set f.dot api->db color=red` writes a file named `db`.

A name that names nothing is exit **4**. A malformed name (for example `node:`) is exit
**1**. The message names each bad name at once:

```
gx: 'nope' names no node, arrow or group; 'api->db' names no node, arrow or group. Write an arrow as api..db. 'gx nodes', 'gx arrows' and 'gx groups' print the names.
```

## Read a diagram

```bash
gx nodes  arch.dot
gx arrows arch.dot
gx groups arch.dot
gx attrs  arch.dot api api..db cluster_svc
```

Real output, for a file with a cluster `cluster_svc` and two arrows:

```
$ gx arrows arch.dot
api..db   api -> db  "reads"
web..api  web -> api

$ gx arrows arch.dot --json
[
  {
    "name": "api..db",
    "ref": "arrow:api->db/0",
    "source": "api",
    "target": "db",
    "label": "reads"
  },
  ...
]
```

`attrs` prints the attributes as the DOT writer of gx prints them. It leaves out the
internal ones (`_gvid`) and the empty ones.

## Change a diagram

```bash
gx set   arch.dot api db fillcolor=lightblue style=filled
gx set   arch.dot api..db color=red label=          # label= removes the label
gx reset arch.dot api                               # remove each attribute except the label
gx group arch.dot web db --label "edge tier"        # the output names the new group
gx ungroup arch.dot cluster_svc                     # the members stay, the group goes
```

In `gx set`, an argument of the form `<name>=<value>` is an attribute. Each other argument
is an element. An empty value removes the attribute. One call can set some attributes and
remove others.

On success, a change prints one line with the path and the new content hash. `--json`
gives `{"path": …, "hash": …, "changed": …}`. `gx group` also gives `"group"`, the name
of the new group (for example `cluster_g1`).

### ⚠️ A change writes the whole file again

gx does not patch the text. It parses the file and **prints the whole graph again** in its
own form: it quotes each id, makes the indent the same, and moves each arrow into the
group that owns it.

- gx **refuses** a change to a file with comments or with `strict`, because the rewrite
  removes them. That is exit **4**, and the message names what the rewrite removes:

  ```
  gx: gx writes the whole file again, and that removes the comments from /path/arch.dot. Add --reformat to accept the rewrite, or change the text yourself.
  ```

  Ask the user before you add `--reformat` to a hand-written file.
- **On a file under version control, show the diff before a commit.** The first change
  to a hand-written file is mostly a new format.
- When the user wants small edits to a hand-written file, edit the text yourself, and use
  gx only to read.

The line endings and the end of the file (a final newline, or none) stay.
The name and the kind of the graph stay: `graph MyNet { a -- b }` stays undirected and
stays `MyNet`. Each group keeps its source name: `subgraph clusterB` stays `clusterB`.

The rewrite also loses some content with no refusal and no message. Check the diff:

- **Each `id` attribute goes.** A change to node `b` also removes `id=foo` from node `a`.
- `subgraph x { cluster=true }` becomes `subgraph "cluster_x"`.
- `clusterB` and `cluster_B` in one file are one group to gx. A write keeps one of the two
  subgraphs, and the members of the other subgraph leave the group.

### Conflicts

gx reads the file and its hash, makes the change, and writes the file only if it still has
that hash. If a different writer changed the file in between, gx does not write it, and
the exit code is **5**:

```
gx: /path/arch.dot changed after gx read it, so gx did not write it (expected 3f2a…, found 9c1b…). Run the command again.
```

Run the command again. It reads the new file.

## Remember about a diagram (metadata)

The library keeps metadata about diagram files: tags, notes, hidden elements and collapsed
groups. **A metadata command never changes the file.** Thus a file that a tool generates
again keeps its hidden elements and its collapsed groups.

```bash
gx hide     arch.dot web web..api
gx unhide   arch.dot web               # or --all
gx collapse arch.dot cluster_svc
gx expand   arch.dot --all
gx tag      arch.dot infra draft
gx untag    arch.dot draft
gx note     arch.dot "owned by the platform team"     # or --stdin, or --clear
gx info     arch.dot --json
gx ls --tag infra --json
gx add      arch.dot other.mmd         # put files in the library
gx remove   arch.dot                   # take it out; the file stays
```

- A command that adds metadata (`hide`, `collapse`, `tag`, `note <text>`, `add`) puts the
  file in the library when it is not there yet. The file must exist.
- A command that removes metadata (`unhide`, `expand`, `untag`, `note --clear`) on a file
  that is not in the library does nothing, and exits 0.
- `info` prints `path`, `inLibrary`, `tags`, `notes`, `hidden`, `collapsed` and
  `autoDetectFormat`. It prints the hidden elements and the collapsed groups with their
  names. When the file does not parse, it prints them as refs (`node:api`).
- `ls` marks an entry whose file is missing with `missing` (`"missing": true` in JSON). The
  entry stays until a person removes it.
- The library finds a file by its canonical path. **A rename or a move of the file loses
  its metadata.**
- To unhide an element that the file no longer has, give its ref, for example
  `gx unhide arch.dot node:old`, or use `--all`.

## Show it to the user (the desktop app)

```bash
gx open      arch.dot
gx select    arch.dot api api..db
gx selection --json
gx fit       arch.dot
```

These are the only commands that need the desktop app. With no desktop, they exit **2**
and say so. That is not a failure of the task: report it, and continue with the headless
work. A desktop with no diagram on the screen also gives exit 2.

`gx select` and `gx fit` name their file. When the window shows a different diagram, the
desktop refuses the command (exit **4**), and the message names the diagram on the screen.
Run `gx open <file>` first, then run the command again. `gx selection` prints the path of
the diagram on the screen with the names of the selected elements.

## Gotchas

- `gx watch <file>... --json` prints one JSON line for each change of a file, and it does
  not stop. Run it in the background and read its output.
- The library is in `~/.graph-explorer/library/metadata/`. `$GX_HOME` moves it. The
  folder for new diagrams is `~/Documents/Graph Explorer/`, and `$GX_DIAGRAMS_DIR` moves it.
  `$GX_HOME` does **not** move that folder: set both for a test.
- The first library command after an upgrade moves the old library to the new store, one
  time, and reports to stderr. It never writes over a file. The old records stay.
- A path can be refused by the policy (exit 4). **Do not work around a refusal**: do not
  copy the file to a different place, and do not change the environment. Report it.
- An old command (`import`, `get`, `bind`, `sync`, `run`, `view`, …) exits 1 with one line
  that names its replacement.
- `gx skill` prints where this skill is, pinned to the binary that runs. Read it again
  after an upgrade of gx: the commands are versioned API.

## More

- Each command, its flags, its output, and the exit codes: [commands.md](commands.md).
  Load it before a command whose flags or output you do not know.
