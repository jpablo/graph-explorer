# `gx` command reference

Every command name, what it takes, and what it returns. Names are API — they appear in
`gx run --list`, in the audit log, and in every recorded command a replay would re-run.

Params are a JSON object, passed as `--params '<json>'` or on stdin with `--stdin`.
Element refs (`targets`, `groups`, `nodes`, `node`) must come from a `list-*` query —
see the "Never construct an element ref" section of [SKILL.md](SKILL.md).

## Document tier — operates on the diagram text

`gx run <ref> <command>`. Works on a loose file or a library diagram. A mutation rewrites
the whole file in canonical form; a query never writes.

| Command | Params | Returns |
|---|---|---|
| `list-nodes` | none | `[{ref, label}]` |
| `list-arrows` | none | `[{ref, source, target}]` |
| `list-groups` | none | `[{ref, label}]` |
| `get-attributes` | `targets` | `{ref: {attr: value}}` |
| `set-attribute` | `targets`, `name`, `value` | the updated diagram |
| `remove-attribute` | `targets`, `name` | the updated diagram |
| `reset-attributes` | `targets` | the updated diagram |
| `group` | `targets`, `label` (optional) | the updated diagram |
| `ungroup` | `targets` | the updated diagram |
| `combine-into-record` | `nodes` (node refs only) | the updated diagram |
| `split-record` | `node` (one ref string) | the updated diagram |
| `transpose-record` | `node` (one ref string) | the updated diagram |

`combine-into-record` and friends take **nodes**, not a mixed set: passing `group:g1` is a
category error and is refused by name rather than silently dropped.

## Record tier — operates on stored metadata

`gx run <ref> <command>`, **library diagrams only**. On a loose file these exit 4 with
`'x.dot' is not in the library, so it has no record to change`.

Record edits never write the origin file, whatever the sync mode says — hiding a node must
not make a regenerating origin conflict.

| Command | Params |
|---|---|
| `hide` | `targets` |
| `unhide` | `targets` |
| `unhide-all` | none |
| `collapse` | `groups` (group refs only) |
| `expand` | `groups` (group refs only) |
| `expand-all` | none |
| `tag` | `tags` (non-empty array of strings) |
| `untag` | `tags` |
| `set-notes` | `notes` (string) |
| `move-to-folder` | `folder` (string) |
| `rename-diagram` | `name` (string) |
| `get-record` | none |

## Session tier — operates on the live view

`gx session <command>`. Needs a running desktop; there is no `<ref>`, because the live
view already knows what it is displaying.

| Command | Params |
|---|---|
| `select` | `targets` |
| `add-to-selection` | `targets` |
| `clear-selection` | none |
| `reset-view` | none |
| `what-is-selected` | none |

Without a desktop these exit **2**. So does a desktop with nothing on screen
(`NO_SESSION`) — the caller's next move is the same either way: open something.

## Top-level commands

| Command | Notes |
|---|---|
| `gx status [--json]` | library root, diagram count, desktop state. Never fails for want of a desktop |
| `gx ls [--folder F] [--json]` | library contents |
| `gx get <ref> [--json]` | the text; `--json` adds `path` and the content `hash` |
| `gx set <ref> (--stdin \| --text T) [--base H]` | replace the text |
| `gx import <path> [--mode M] [--folder F] [--name N]` | see [library.md](library.md) |
| `gx bind <ref> <path> [--mode M]` / `gx unbind <ref>` | see [library.md](library.md) |
| `gx sync [<ref>] [--all] [--json]` | see [library.md](library.md) |
| `gx watch [<ref>...] [--all] [--json]` | see [library.md](library.md) |
| `gx run <ref> <cmd>` / `gx run --list` | document and record tiers |
| `gx session <cmd>` / `gx session --list` | live view |
| `gx open <ref>` | show it in the desktop |
| `gx skill [<version>] [--latest] [--json]` | where this skill lives |
| `gx --version` | the binary's version |

## Exit codes

| Code | Meaning | What to do |
|---|---|---|
| 0 | ok | |
| 1 | usage — including a **malformed** element ref | fix the command line |
| 2 | needs a desktop | only `open` / `session`; everything else still works |
| 4 | element doesn't exist, bad path, policy refusal, or unparseable diagram | check `gx ls` / `list-nodes`, or import the file first |
| 5 | conflict, or `sync` found divergence | re-read the file and retry |
| 6 | unexpected | report it |

Exit 3 does not exist and is not reused: it meant an auth failure in v1, and v2 has no
token to fail against.
