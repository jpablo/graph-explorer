# A board refers to its diagrams, and does not contain them

Status: accepted (2026-11-15)

A board shows many diagrams, images, and notes on one canvas. We store a board as one small file that lists its items. Each item has a location and a place on the canvas, and each diagram stays its own data source (ADR 0002). We selected this because the file of a diagram stays the only copy of its source (ADR 0001), `gx` works on each diagram with no change, and a write never replaces one region of a larger document.

```json
{
  "version": 1,
  "items": [
    { "id": "arch",   "location": "arch.dot",                    "x": 0,   "y": 0 },
    { "id": "login",  "location": "login.mmd",                   "x": 900, "y": 0 },
    { "id": "schema", "location": "postgres://db.internal/app",  "extractor": "sql-schema", "x": 0, "y": 700 },
    { "id": "shot",   "location": "assets/screenshot.png",       "x": 900, "y": 700, "width": 400 }
  ]
}
```

## Considered options

- **One file that contains each source** (Markdown with a block for each diagram, or JSON with the text inline). A person shares one file. But the metadata of each diagram needs a stable name inside the file, a canvas edit must replace one region of the file, and each `gx` command needs a way to name one diagram in the file. This can come later as a location with a fragment, and the board file does not change.
- **A board that exists only in the library**, with no file. We did not select it, because the places of the items are the work of the person, and that work must be a file that the person owns.

## Consequences

- A board is more than one file. A location in a board is relative to the board file, so that the folder moves as one unit. An item whose data source is missing stays on the board, marked as missing.
- The place and the size of an item are in the board file, because they are the source of the board. The hidden elements and the collapsed groups stay the metadata of the diagram. Thus two boards that show one diagram share them.
- One diagram can be on more than one board. When a tool writes a diagram file again, each board that shows it follows.
- A database diagram and an image are items with the same rule as a DOT file.
- The viewer keeps the source and the graph of one diagram for each window now. A board needs that state for each item. A window that opens one diagram shows it as the only item, with no board file.
- In the web app, the browser keeps the board and its items, because a browser has no files. The stored text of a browser board is the text of a board file. The location of an item there is `browser:<diagram id>`.
- A board file has the extension `.gxboard`. A reader refuses a `version` that is newer than its own, because its write could remove content. A reader keeps each key that it does not know, and a write gives the key back.
- The id of an item comes from the name of its diagram, and a number makes it different from the other ids of the board. An id names one item, so that a later tool can refer to an item.
