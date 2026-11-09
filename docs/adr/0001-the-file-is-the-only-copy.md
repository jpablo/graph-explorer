# The file is the only copy of a diagram

Status: accepted (2026-11-08)

Before, each diagram in the library kept its own copy of the source, apart from its file. A binding connected the copy to the file, with one of four sync modes (`pull`, `push`, `sync`, `detached`), and a comparison of three hashes decided which copy changed. The commands `import`, `get`, `set`, `bind`, `unbind`, and `sync` existed only to manage that copy. We removed the copy. In the desktop app and in `gx`, a diagram is a file, and the library keeps only metadata about each file, by its path.

The two copies were a deliberate design (`docs/sources-and-library-architecture.md` §3.2). They let a diagram exist with no file, let a diagram follow a read-only origin such as a URL, and let `pull` mode keep the edits in the app out of a generated file. In practice, only file origins were ever read, most diagrams had no file, and most open defects came from the sync between the copies: an edit in the app did not reach the file, and a lossy edit could reach a file through the `Ahead` state. The desktop app already had a simpler path for a loose file, with an explicit Save and a conflict banner, and that path now applies to every diagram.

## Consequences

- A new diagram in the desktop app is a new file.
- `pull` mode goes away. Hidden elements and collapsed groups still survive when a tool writes the file again, because they are metadata.
- One file has one set of metadata. A rename or a move of a file outside the app loses its metadata.
- The web app keeps the source in the browser, because a browser has no files.
- The library has no separate diagram name and no separate folders. A person organizes diagrams with tags and with the folders of the file system.
