# A diagram comes from a data source through an extractor, and a renderer draws it

Status: accepted (2026-11-15)

Before, one tag (`DiagramFormat`: DOT or Mermaid) selected the parser, the printer, the renderer, and the selection strategy together, and a library entry held only a file path. Thus Graphviz was the only renderer for a DOT source, and a diagram could come only from a DOT or Mermaid file. We divide the path into three independent parts. A data source has a location (a canonical URI) and gives raw content. An extractor makes a graph from that content, and some extractors also have a printer that writes a graph back. A renderer draws a graph. The library entry of a diagram holds the location, the extractor, and a `locked` flag, with the metadata.

This does not bring back the binding of the old design (ADR 0001). There is still one copy of the content, and no sync. For a file, the file is still the only copy of the source. For a database, the entry is the definition of the diagram, because there is no file.

## Considered options

- **A descriptor file for each data source that is not a file.** The descriptor names the database and the extractor, so each diagram stays a file. We did not select it, because the library already is an index by location. A descriptor file can come later as one more extractor, which reads a location from a file.
- **A stored "mutable" flag.** We did not select it, because a stored value becomes wrong when the permissions of a file change. The app calculates the value: the scheme can write, the extractor has a printer, the write check passes now, and the person did not set `locked`.
- **One renderer for each language (no change).** This keeps the code simple, but a new renderer (3D, hand-placed) and a new kind of data source would each need a new language tag.

## Consequences

- A canvas edit goes through the printer of the extractor. The renderer has no part in the write.
- A Mermaid source with the Mermaid renderer keeps the draw from the raw source. A kind of Mermaid diagram that has no graph (a sequence diagram) works only with that pair.
- The choice of renderer is metadata. Thus it survives when a tool writes the file again.
- The key of an entry stays the SHA-256 of the canonical URI, so each current entry stays valid. When an extractor has parameters, the parameters are a part of the key text. Thus two extractors on one database give two diagrams.
- For a file, the stored entry keeps the `path` key, and the location comes from the path. The `extractor` key and the `locked` key are in the entry only when they have a value. Thus an entry from before has the same bytes, and an older build can read a new entry. A read keeps each key of the entry that it does not know, and a write gives the key back after the known keys, in the order of the read. Thus a write of this build does not remove the keys of a later build.
- A locked data source gets no write from the app and no write from `gx`. A change of the metadata stays possible, and the app still shows a change that a different tool makes.
- A location holds no secret, because the app hashes it and shows it. The parameters of the extractor refer to an environment variable or to the keychain.
- An extractor for a database runs in `gx`, not in a browser. The web app has only sources that the browser keeps.
- "Remove from library" for a database diagram removes its definition. For a file, the file stays, as before.
- `locked` gives back the good part of the old `pull` mode: a tool makes the file again, and the app does not write to it.
- A new diagram that the app makes is still a file in `~/Documents/Graph Explorer/`. It needs no special rule.
