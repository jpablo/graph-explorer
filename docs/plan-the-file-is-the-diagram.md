# Plan: the file is the diagram

Status: proposed (2026-11-08). Decision: `docs/adr/0001-the-file-is-the-only-copy.md`. Terms: `CONTEXT.md`.

## 1. Goal

A technical user can use `gx` without the documentation. Each command is a verb, the diagram file comes first, and the arguments are plain words.

The model has four concepts: a **diagram** (a file), its **metadata**, the **library** (the diagrams that have metadata), and the **live view**. There is no second copy of the source, so there is nothing to import, bind, or sync.

## 2. Commands

```
gx — Graph Explorer command line

Read a diagram
  gx nodes  <file> [--json]
  gx arrows <file> [--json]
  gx groups <file> [--json]
  gx attrs  <file> <element>... [--json]

Change a diagram (gx writes the file)
  gx set     <file> <element>... <name>=<value>...
  gx unset   <file> <element>... <name>...
  gx reset   <file> <element>...           remove each attribute except the label
  gx group   <file> <element>... [--label L]
  gx ungroup <file> <group>...
  gx combine-record   <file> <node>...
  gx split-record     <file> <node>
  gx transpose-record <file> <node>

Remember about a diagram (gx writes the metadata, never the file)
  gx hide     <file> <element>...    gx unhide <file> (<element>... | --all)
  gx collapse <file> <group>...      gx expand <file> (<group>... | --all)
  gx tag      <file> <tag>...        gx untag  <file> <tag>...
  gx note     <file> (<text> | --stdin | --clear)
  gx info     <file> [--json]        the metadata of a diagram
  gx ls [--tag T] [--json]           the library
  gx add <file>...                   put a diagram in the library
  gx remove <file>...                take it out (the file stays)

The desktop app
  gx open      <file>
  gx select    <file> <element>...
  gx selection [--json]              what is selected, and in which diagram
  gx fit       <file>                show the whole diagram

Other
  gx status    gx watch <file>... [--json]    gx skill    gx --version
```

Rules that apply to each command:

- A change to the source is refused when it would remove comments or `strict` from the file. The message names what the change removes. `--reformat` accepts the rewrite.
- gx reads the file, makes the change, and writes the file only if the file did not change in between. Otherwise it stops with exit 5.
- A desktop command names its diagram. When the desktop shows a different diagram, the command is refused, and the message names the diagram on the screen.
- `--json` on each command that prints a result.
- The exit codes do not change.

Removed: `import`, `get`, `set --stdin` / `--text`, `bind`, `unbind`, `sync`, `run`, `view`, `--mode`, `--params`, `--base`, `--loose`, `rename-diagram`, `move-to-folder`, `push-text`.

### 2.1 Element names

- `api` is the node `api`.
- `api..db` names each arrow from `api` to `db`, in a directed or an undirected graph. When there are several, `api..db#1`, `api..db#2` name one of them (from 1, in the order of the source). The form is safe in a shell with no quotes, and `gx arrows` prints it, so an agent can copy it. (`api->db` is not used: a shell reads `>` as a redirect, and writes to a file named `db` with no message.)
- A group has the name that the source gives it: `cluster_svc` in DOT, the subgraph id in Mermaid.
- `node:x`, `arrow:...` and `group:x` stay valid. A prefix is necessary only when a node and a group have the same name. gx then refuses the bare name and lists both.

## 3. Metadata store

- One JSON file for each diagram, in `$GX_HOME/library/metadata/`. The file name is the hex SHA-256 of the canonical `file:` URI of the diagram (`LibraryEntry.keySource`). The desktop app gets the canonical path from its shell, makes the URI with the same shared function, and hashes it with `hash_text`.
- Fields: `path` (canonical), `tags`, `notes`, `hiddenElements`, `collapsedGroups` (both in the element-ref spelling), `autoDetectFormat`, `createdAt`, `updatedAt`.
- The canonical path follows the existing rules (`OriginUri`, and V-13 cross-tested with the desktop): resolved symlinks, and one spelling for each file.
- The library is the set of these files. `gx ls` and the desktop home page list them. A diagram whose file is missing stays in the list, marked as missing, until a person removes it.

## 4. Migration (one time, never loses text)

For each old record in `$GX_HOME/library/diagrams/`:

- **Bound to a file that exists:** the metadata moves to that file. If the record's source differs from the file, gx writes the record's source as a new file in `~/Documents/Graph Explorer/Recovered/`, adds it to the library, and reports it.
- **Bound to a file that is missing, or not bound:** gx writes the record's source as a new file in `~/Documents/Graph Explorer/`, with the record's name as the file name (made unique), and adds it to the library.
- **Folder:** a folder that is not the root becomes a tag (`/systems/core` → `systems/core`).
- **Name:** when the record's name is not the title in the source and not the file name, it becomes a tag.
- The old directory `diagrams/` stays UNCHANGED, so an older desktop app that is still installed shows its library as before. The migration runs when `metadata/` does not exist. It builds the new store in a temporary directory and renames it to `metadata/` last, so a crash leaves no partial store.
- The migration is a pure plan (which files to write, which entries to make), and a caller performs it: `gx` on the JVM, and the desktop app through its IPC commands. It never overwrites a file that exists.
- The folder for new diagrams is `~/Documents/Graph Explorer/`. `$GX_DIAGRAMS_DIR` (an absolute path) replaces it, for tests and for a person who wants a different folder.

The browser-to-desktop migration (`DesktopMigration`) writes files into `~/Documents/Graph Explorer/` in the same way.

## 5. Phases

Each phase ends with `sbt --client testFull` and `cargo test` green. The desktop app and `gx` share the library, so they ship together, in one release after phase 4.

1. **gx-core.** Add the metadata store by path, the migration plan with its JVM executor, and the element-name parser. Keep the old record types (`Diagram`, `Binding`, `SyncMode`, `SyncState`, `Reconciler`), because the desktop app still uses them until phase 3. `DiagramMetadata` keeps `name` and `folder` until phase 3, but the new store does not save them.
2. **gx.** The commands in §2, `--reformat` (through `RoundTripLoss.scan`), the help text, and the skill files. Remove the old commands.
3. **The desktop app.** Run the migration through IPC. Each diagram opens through the file path that loose files use today, with an explicit Save. The home page lists the metadata store. "New diagram" creates a file in `~/Documents/Graph Explorer/`. A desktop command names its diagram and is refused for a different one. `gx selection` answers with the diagram. Delete `OriginReconciler`, `OriginStateBanner`, and the library copy. Then delete the old record types in gx-core, and `name` and `folder` from `DiagramMetadata`. The web app keeps a stored name for each browser diagram, because a browser diagram has no file name. The UI says "arrow" where it says "edge".
4. **Docs.** `HANDOFF.md`, `local-protocol/README.md`, and a note that `docs/sources-and-library-architecture.md` is superseded.

## 6. Decisions after the first draft

- Most users of `gx` are agents. Thus the arrow form `api..db` (safe in a shell) is better than the DOT spelling, and each command has `--json`.
- The command names in §2 are accepted.
