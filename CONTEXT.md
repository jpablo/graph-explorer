# Graph Explorer

Graph Explorer shows, edits, and keeps DOT and Mermaid diagrams. The desktop app and the `gx` command line work on the same files and the same library.

## Diagrams and files

**Library**:
The collection of diagrams that Graph Explorer keeps for a person.
_Avoid_: store, projects

**Diagram**:
An entry in the library. A diagram has a source and metadata, and it can have a binding to an origin.
_Avoid_: project, record, library record, library diagram

**Loose file**:
A DOT or Mermaid file that is not in the library. A loose file has a source and no metadata.
_Avoid_: loose document, document

**Origin**:
The file that a binding names. The source of a diagram and the content of its origin can be different until the next sync.
_Avoid_: backing file, source file

**Binding**:
The link from a diagram to its origin, with a sync mode.
_Avoid_: link, attachment

**Sync mode**:
The directions in which a binding moves a change: `pull` (from the origin to the diagram), `push` (from the diagram to the origin), `sync` (both directions), or `detached` (neither direction).

## Where a change lives

**Source**:
The DOT or Mermaid text of a diagram or a loose file. It holds the nodes, the arrows, the groups, and their attributes.
_Avoid_: document, text, document tier

**Metadata**:
What the library keeps about a diagram outside its source: the name, the folder, the tags, the notes, the hidden elements, and the collapsed groups. Only a diagram has metadata.
_Avoid_: record, record tier, diagram state

**Live view**:
What a desktop window shows now: one diagram or loose file, with its selection and its viewport. A live view is not saved, and it exists only while the window is open. In a live view of a loose file, hidden elements and collapsed groups are part of the live view, because a loose file has no metadata.
_Avoid_: session, session tier, view state

## Other meanings to keep apart

**Record node**:
A DOT node with the `record` shape, which divides the node into cells. It has no relation to the metadata of a diagram.
_Avoid_: record (alone)
