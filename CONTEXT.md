# Graph Explorer

Graph Explorer shows, edits, and keeps DOT and Mermaid diagrams. The desktop app and the `gx` command line work on the same files and the same library.

## Diagrams and the library

**Diagram**:
A DOT or Mermaid file. In the desktop app and in `gx`, the file is the only copy of the diagram's source. The name of a diagram is the title in its source, or else its file name.
_Avoid_: project, record, document, loose file, origin, library copy

**Library**:
The list of diagrams that Graph Explorer has metadata for. A diagram is in the library when it has metadata, and it leaves the library when its metadata is removed. The diagram file stays where it is. In the web app, the library also keeps the source of each diagram, because a browser has no files.
_Avoid_: store, projects, import

## Elements of a diagram

**Element**:
A node, an arrow, or a group of a diagram.

**Node**:
One box or shape in a diagram.

**Arrow**:
A connection from one node to another node.
_Avoid_: edge, link

**Group**:
A set of nodes that a diagram draws in one box: a cluster in DOT, a subgraph in Mermaid.
_Avoid_: cluster, subgraph

## Where a change lives

**Source**:
The DOT or Mermaid text of a diagram. It holds the nodes, the arrows, the groups, and their attributes.
_Avoid_: document, text, document tier

**Metadata**:
What Graph Explorer keeps about a diagram outside its source: the tags, the notes, the hidden elements, and the collapsed groups. Any diagram can have metadata.
_Avoid_: record, record tier, diagram state

**Live view**:
What a desktop window shows now: one diagram, with its selection and its viewport. A live view is not saved, and it exists only while the window is open.
_Avoid_: session, session tier, view state

## Other meanings to keep apart

**Record node**:
A DOT node with the `record` shape, which divides the node into cells. It has no relation to the metadata of a diagram.
_Avoid_: record (alone)
