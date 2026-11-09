# `gx` command reference

Each command, what it takes, and what it gives. The command names and the JSON keys are
API. A change to them is a change of version.

Each command takes the diagram FILE first. gx resolves the path against the working
directory, checks it with the policy (see "The sandbox" below), and makes it canonical
(symlinks resolved, one spelling for each file). Each path in an output is canonical.

Element names (`api`, `api..db`, `api..db#2`, `cluster_svc`, `node:x`) come from
`gx nodes`, `gx arrows` and `gx groups`. See the "Element names" section of
[SKILL.md](SKILL.md).

`--` ends the flags. Each word after it is an argument, also when it starts with `--`:
`gx note f.dot -- --draft`.

## Read a diagram

These parse the file and write nothing.

| Command | Text output | `--json` |
|---|---|---|
| `gx nodes <file>` | `name  "label"` | `[{name, ref, label}]` |
| `gx arrows <file>` | `name  source -> target  "label"` | `[{name, ref, source, target, label}]` |
| `gx groups <file>` | `name  "label"  (in parent)` | `[{name, ref, label, parent}]` |
| `gx attrs <file> <element>...` | the name, then `  key=value` lines | `[{name, ref, attributes: {key: value}}]` |

- The order is the order of the source. Arrows come in the order that Graphviz numbers
  them (by their tail node).
- `label` and `parent` are `null` when there is none. `source`, `target` and `parent` are
  element names.
- `ref` is the element in the ref spelling (`node:api`, `arrow:api->db/0`, `group:svc`).
- `attrs` gives the attributes as the DOT writer prints them. It leaves out `_gvid` and the
  other internal attributes, empty values, and group attributes that have their default.

## Change a diagram

These write the file. Each one takes `--json` and `--reformat`.

| Command | Takes |
|---|---|
| `gx set <file> <element>... <name>=<value>...` | each kind of element. `<name>=` (empty value) removes the attribute |
| `gx reset <file> <element>...` | each kind. It removes each attribute except the label |
| `gx group <file> <element>... [--label L]` | nodes and groups. It makes a new group around them |
| `gx ungroup <file> <group>...` | groups. The members move to the parent of the group, and the group goes |
| `gx combine-record <file> <node>...` | two or more nodes in the same group. It makes one record node |
| `gx split-record <file> <node>` | one record node (shape `record` or `Mrecord`) |
| `gx transpose-record <file> <node>` | one record node |

- In `set`, an argument that matches `^[A-Za-z_][A-Za-z0-9_]*=` is an attribute, and each
  other argument is an element. A value can contain `=`. Give each attribute one time. A
  `set` with no element or with no attribute is exit 1. gx refuses an attribute whose name
  starts with `_` (exit 1). The reader uses these names for its own data, for example `_gvid`.
- An element of the wrong kind is exit 4: `gx: ungroup takes groups; 'api' is a node`.
- **Output:** one line, `<path>  <hash>`. When the change gives the same text, gx writes
  nothing and adds `unchanged`. `gx group` adds `group <name>`.
  `--json`: `{"path": …, "hash": …, "changed": true|false}`, and `"group": …` for `gx group`.
- **Refusal:** a change to a file with content that the graph does not keep is exit 4. The
  message names the content: the comments, the `strict` keyword, and for Mermaid the
  `%%{…}%%` directives and the front matter, the `click` lines, `accTitle`/`accDescr`, and
  `direction` lines in subgraphs. `--reformat` accepts the rewrite.
- **Conflict:** gx writes the file only if it has the hash that gx read. Else it writes
  nothing, and the exit code is 5. Run the command again.
- A write keeps the line endings of the file (LF or CRLF) and its end (a final newline, or
  none). It leaves out an empty label on an arrow or on the graph, because that is the
  default of Graphviz.
- A new group gets the name `cluster_<id>` in DOT (for example `cluster_g1`). A group from
  the source keeps its source name (`clusterB`, `cluster01`, `cluster_svc`), with one
  exception: `subgraph x { cluster=true }` becomes `cluster_x`.
- Each subgraph is a different group. When the names of two subgraphs give one id
  (`clusterB` and `cluster_B` both give `B`), the second group gets the id `B_2`
  (`group:B_2`). Two subgraphs with the same name in the same parent are one group, as in
  Graphviz, and a write prints one subgraph with the members of both.

## Metadata

These write the library entry of the file, and never the file. The library is in
`$GX_HOME/library/metadata/` (default `~/.graph-explorer/library/metadata/`): one JSON
file for each diagram, found by the canonical path of the diagram.

| Command | Notes |
|---|---|
| `gx hide <file> <element>...` | each kind of element. The file must parse |
| `gx unhide <file> (<element>... \| --all)` | not both, not neither |
| `gx collapse <file> <group>...` | groups only |
| `gx expand <file> (<group>... \| --all)` | not both, not neither |
| `gx tag <file> <tag>...` | the tags keep their order, with no duplicates |
| `gx untag <file> <tag>...` | |
| `gx note <file> (<text> \| --stdin \| --clear)` | exactly one. One `<text>`: put it in quotes. `--stdin` removes the last line endings |
| `gx info <file>` | the metadata of one file |
| `gx ls [--tag T]` | each entry, or each entry with the tag `T` |
| `gx add <file>...` | put files in the library. A file that does not exist is exit 4, and gx adds none of the files |
| `gx remove <file>...` | take files out of the library. The files stay. It works on a missing file too |

- A command that adds metadata (`hide`, `collapse`, `tag`, `note <text>`, `add`) makes the
  entry when there is none. The file must exist (else exit 4).
- A command that removes metadata (`unhide`, `expand`, `untag`, `note --clear`) on a file
  with no entry does nothing and exits 0. It prints `not in the library; nothing to change`.
- gx stores the elements in the ref spelling (`node:api`, `group:svc`). `info` prints them
  as names when the file parses, else as refs. `unhide` and `expand` take a ref of an
  element that the file no longer has.
- **Output** of `hide`, `unhide`, `collapse`, `expand`, `tag`, `untag`, `note`: one line,
  `<path>  <field>: <value>`. `--json`: the same object as `gx info --json`.
- `gx info --json`: `{"path", "inLibrary", "tags", "notes", "hidden", "collapsed",
  "autoDetectFormat"}`. `autoDetectFormat` is `null` when it is not set. On a file that is
  not in the library, `inLibrary` is `false` and the other fields are empty. On a file that
  does not exist and has no entry, `info` is exit 4.
- `gx ls`: one line for each entry, `<path>  tags: a, b  missing`. `--json`:
  `[{"path", "tags", "missing"}]`. An empty library prints `(no diagrams)`, or `[]`.
- `gx add --json`: `[{"path", "added"}]`. `gx remove --json`: `[{"path", "removed"}]`.

### The one-time migration

The first command that reads or writes the library (`status`, `ls`, `info`, `add`,
`remove`, and the metadata commands) moves an old library (`library/diagrams/`) to the
metadata store, when `metadata/` does not exist. It reports each step to stderr. It never
writes over a file, and the old records stay unchanged. A record with no file gets a new
file in `$GX_DIAGRAMS_DIR` (default `~/Documents/Graph Explorer/`). A read or change
command does not run it.

## The desktop app

| Command | Sends | `--json` |
|---|---|---|
| `gx open <file>` | `show` with `{"target": {"kind": "file", "path"}}` | `{path, focused}` |
| `gx select <file> <element>...` | the live view command `select` with the refs and the path | `{path, selected: [refs], names}` |
| `gx selection` | the live view command `what-is-selected` | `{path, selection: [names]}` |
| `gx fit <file>` | the live view command `reset-view` with the path | `{path}` |

- With no desktop, each of these is exit 2. A desktop with no window, or with no diagram on
  the screen, is exit 2 too. Your next step is the same: show the diagram.
- Each other refusal of the desktop is exit 4, and the message has its code. A desktop
  that is still starting gives `STARTING`: wait a few seconds, and run the command again.
- `gx select` resolves the names against the file before it sends them. A bad name is
  exit 4, and nothing goes to the desktop.
- `gx select` and `gx fit` name their file. When the desktop shows a different diagram,
  the desktop refuses the command: exit 4, and the message names the diagram on the
  screen (`the desktop shows /w/b.dot, not /w/a.dot`). Show the file first with
  `gx open <file>`.
- `selection` prints `<path>  selected: api, api..db` (or `<path>  nothing selected`).
  The names are element names of that file, so the next command can take them.
  `path` is `null` when the desktop shows no file (an example, or a browser diagram). Then
  stderr says so, the line has no `<path>`, the selection is in the ref spelling, and the
  exit code is still 0.

## Other

| Command | Notes |
|---|---|
| `gx status [--json]` | the library folder, the number of diagrams (and of missing files), and the desktop: running, starting, or not running. Exit 0 also with no desktop |
| `gx watch <file>... [--json] [--interval MS]` | one line for each change of a file: `changed`, `restored` or `deleted`. `--json`: `{"event", "origin", "hash"}` on each line. It does not stop: run it in the background. `--interval` sets the poll and the debounce (default 50) |
| `gx skill [<version>] [--latest] [--json]` | where this skill is, pinned to the running binary |
| `gx --version` | the version of the binary |
| `gx help` | the command list |

## The sandbox

`GX_ALLOWED_ROOTS` and `GX_DENY_ROOTS` (separated like `PATH`) set which files gx touches.
`GRAPH_EXPLORER_ALLOWED_ROOTS` and `GRAPH_EXPLORER_DENY_ROOTS` are aliases. With no allowed
roots, each path outside the denied roots is allowed. Some roots are always denied, for
example `~/.ssh` and `~/.aws`.

A refusal is exit 4 with the reason. The audit log
`$GX_HOME/runtime/audit.log.jsonl` records it, and each write and each conflict.

**When gx refuses a path, do not work around it.** Do not copy the file to a different
place, and do not change the environment. Report the refusal, and let the user decide.

## Exit codes

| Code | Meaning | What to do |
|---|---|---|
| 0 | ok | |
| 1 | usage: a bad command line, a **malformed** element name, an unknown or removed flag or command | correct the command line; the message says how |
| 2 | needs a desktop: only `open`, `select`, `selection` and `fit`. Also a desktop with no window, or with no diagram on the screen | start the desktop, or continue without it |
| 4 | a path, an element or a file is not usable: the file does not exist or does not parse, the policy refuses the path, a name names nothing or the wrong kind, or a change would remove comments or `strict`. Also each other refusal of the desktop, for example `VIEW_REJECTED` | read the message; use `gx nodes`/`arrows`/`groups` for names; `--reformat` for a refusal; `gx open <file>` for `VIEW_REJECTED` |
| 5 | conflict: the file changed after gx read it, and gx wrote nothing | run the command again |
| 6 | unexpected, or gx cannot read or write the library (for example, the one-time migration failed) | read the message, and report it |

Exit 3 does not exist. It meant an auth failure in v1.

## Removed commands

Each old command exits 1 with one line that names its replacement.

| Old | Instead |
|---|---|
| `import`, `bind` | `gx add <file>` |
| `unbind` | `gx remove <file>` |
| `get` | read the file, or `gx nodes` and `gx attrs` |
| `sync` | nothing: the file is the only copy |
| `run <cmd>` | the verbs: `gx nodes`, `gx set`, `gx hide`, … |
| `view`, `session` | `gx select`, `gx selection`, `gx fit` |
| `set --stdin` / `--text` | write the file yourself, or `gx set <file> <element>... <name>=<value>...` |
| `unset` | `gx set <file> <element>... <name>=` |
| `rename-diagram`, `move-to-folder`, `--folder` | rename or move the file; use tags |
| `--mode`, `--params`, `--base`, `--loose` | not necessary any more |
