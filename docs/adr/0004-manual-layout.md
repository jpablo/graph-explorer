# A diagram keeps hand positions in a layout file next to its source

Status: accepted (2026-11-26)

A person wants to put the nodes of a diagram at places of their choice. We use an ambient model: there is no mode switch. A node with a stored position stays exactly at that position. A node with no stored position gets an automatic place around the pinned nodes. A diagram with no stored position draws exactly as before, with the same engine.

The positions are a **layout**. The layout is not in the source. A diagram with a file keeps its layout in a side file next to the file: `<dir>/<base name>.layout.json`. This is the name pattern of the `<base name>.assets/` folder of the images. A diagram of the browser library keeps the text of that file in its library entry. Thus the stored text of a browser layout is the text of a layout file, as for a board (ADR 0003).

```json
{
  "version": 1,
  "nodes": {
    "api": { "x": 27, "y": 18 },
    "db":  { "x": 127.5, "y": -18.25 }
  },
  "arrows": {
    "api->db#1": { "points": [ { "x": 60, "y": 30 } ] }
  }
}
```

- The units are Graphviz points (1/72 inch). A node position is the center of the node. The y axis goes up, as in Graphviz.
- The key of a node is its id. The key of an arrow is `<source>[:<port>]-><target>[:<port>]#<n>`, where `n` counts the arrows with the same ends in the order of the source. The arrows hold the bend points (see "Bend points" below). The key does not use the DOT edge number, because a new arrow earlier in the file changes that number.
- A reader refuses a newer `version`. A reader keeps each key that it does not know, and a write gives it back.
- The layout keeps an id that the graph does not have. The render ignores it. Thus a rename and its undo do not lose a position. Freeze replaces the positions of the drawn nodes, and Arrange removes all positions.

## How a diagram with positions draws

When a node of the graph has a stored position, Graphviz draws the diagram with the `neato` engine. The text for the render gets `pos="x,y!"` on each node with a position, and the graph gets `layout=neato`, `inputscale=72`, `splines=true` and `overlap=true`. The source never gets these attributes.

- `neato` keeps each pinned node exactly at its place, also in a group. We checked this with viz-js 3.14 (Graphviz 13). `fdp` lays out a group as one unit. It moved the pinned nodes of a group, and it moved the pinned nodes around the group.
- `neato` draws each group as a box around its nodes. A node with no position can go inside the box of a group that it is not in. We accept this for now.
- `inputscale=72` reads a position in points. With no `inputscale`, Graphviz reads a position in inches.
- `overlap=true` (the default) turns off the overlap removal. The overlap removal (`overlap=false`) moved a pinned node in our check.
- Graphviz moves the box of the drawing to the origin. Thus the drawing of a pinned node is at its position plus one offset for the whole drawing. The app finds the offset from a pinned node when it reads places from a drawing.

A Mermaid flowchart with stored positions also draws with Graphviz, from its graph. The renderer selector shows this: Auto says "Auto — Free layout (Graphviz)". A renderer that the person selects still wins. Mermaid then draws the diagram, and the positions stay with no effect. A Mermaid kind with no graph (a sequence diagram) has no positions.

## Freeze and Arrange

**Freeze** reads the place of each drawn node from the current drawing, and stores it. Freeze stores no bend point: `neato` routes each arrow until the person changes the route. Graphviz gives the places in its `json0` output. Mermaid gives the place of a node in the `translate` transforms of its SVG, in pixels, and the app uses one pixel as one point. **Arrange** removes each position and each bend point, and the diagram goes back to the automatic layout. An automatic layout has no bend points. Each is one step of the undo history. The history holds the layout with the text, the hidden elements and the folded groups. The changes of one gesture are one step, so a later drag of a node is one step.

## The drag of a node

In a free layout, a press on a node and a move of more than 4 pixels drags the node. A press on a node of the selection, or on a node in a selected group, drags all the selected nodes. A shorter move is a click. A drag on an arrow handle or on the empty canvas keeps its meaning.

- During the drag, the app does not draw the diagram again. It moves the SVG of each node with a `translate`. An arrow between two moved nodes moves with them. An arrow with one moved end shows as a straight dashed line. The release stores one move (`moveNodesBy`), and the next render routes the arrows. Thus a drag is one step of the undo history.
- The drag snaps the center of the pressed node to a grid of 9 points (1/8 inch), and the other nodes move by the same amount. The default node of Graphviz is 54 by 36 points, so its sides are also on the grid. During the drag, the canvas shows this grid under the drawing, in the units of the layout. The background of the canvas is a pattern in screen pixels, and it does not show the grid of the layout. ⌥ (Alt) drags with no snap.
- A drag turns Auto off, as a pan does. Then the next layout does not fit the view again, and the dropped node stays under the pointer.
- In a diagram with no position, a drag does not move the node, and it does not freeze the diagram. The canvas offers Freeze. Freeze and a drag in one gesture would draw the diagram again during the drag, with `neato` and new arrows.
- A locked diagram takes a drag. A lock protects the source, and the layout is not the source (see Save).
- ⇧ and an arrow key move the selected nodes by one step of the grid. As for a drag, Auto goes off. After the new layout, the canvas keeps the map from the layout to the screen, so the moved nodes move on the screen and the other nodes stay. Before this rule, the canvas kept the selected node still on the screen, and the rest of the drawing moved.

## A new node at a place

In a free layout, a new node goes to a place that the person chooses. The center of the node goes to the point, on the grid of 9 points, because the size of the node is known only after the render. In an automatic diagram, the layout places a new node, as before, and the app stores no position.

- A drop puts the new nodes at the drop point: the point of the DOM drop event, or the point of the Tauri drop on the desktop. The first node goes to the point. The next nodes go below it in one column, with one grid step or more between two boxes. The app estimates the height of each node from its item: the size of an image, or the lines of a label.
- A paste and New node (`n`, `N`, and the buttons of the toolbar) put the new node at the pointer when the pointer is over the canvas. Else the node goes to the center of the visible part of the canvas. With the label dialog, the place is the place at the time of the key or the click.
- A double-click on the empty canvas adds a node at that point, as New node does. In an automatic diagram, this double-click does nothing.
- A new node from the new-arrow control of a node gets an automatic place.
- The new nodes and their places are one change, so they are one step of the history. The new node goes out of the layout and out of the source with one undo. As for a drag, Auto goes off.
- After a new layout of a free layout, the canvas keeps the screen scale, and a pinned node keeps its screen place. Else a larger drawing gets a smaller scale (the svg shows its whole viewBox), and the nodes move on the screen.

## Bend points

In a free layout, a person can give an arrow a route of their choice. The route goes through **bend points**. The layout keeps the bend points of an arrow under `arrows`, by the key of the arrow, in the frame of the layout (points, the y axis up). An arrow with no bend point keeps the route of `neato`.

### How an arrow with bend points draws

`neato` uses the `pos` of an arrow only in its `-n2` mode. In that mode, `neato` does no layout at all, and each node must have a position. viz-js does not give that mode: it gives only `yInvert` and `reduce`. Thus `neato` routes each arrow, and the app draws the path of an arrow with bend points again in the SVG (`BendRender`):

- The path is a smooth curve through the bend points, in their order. The curve is a uniform Catmull-Rom spline, as cubic Bézier segments: at an inner point `Pi`, the control points are `Pi ± (Pi+1 − Pi−1) / 6`. At an end, a mirror point continues the curve, so the curve leaves the end straight toward the next point.
- The path starts where the ray from the center of the source to the first bend leaves the source shape. The arrowhead tip is where the ray from the center of the target to the last bend meets the target shape. The shape is the largest ellipse, polygon or path of the node in the SVG. Graphviz draws some shapes as a path, for example a rounded box or a cylinder. For a path, the shape is the convex hull of the points of the path. A node with no such shape uses its box from Graphviz. The drag of a bend and the render use the same shapes.
- The path stops at the base of the arrowhead, at the length of the arrowhead of `neato`. The arrowhead keeps its shape. It turns and moves from its old tip and direction to the new tip and direction. A tail decoration does the same at the start.
- A label moves with the nearest of three anchors: the start, the middle of the path, or the end.
- The positions of the arrow change to the new ends, so that the end controls of a selected arrow are at the new ends.

The canvas uses the same code for the look of a drag. Thus a drag shows the final path with no new render. A Mermaid flowchart in a free layout draws with Graphviz, so it uses the same path.

### The gestures

The gestures apply only in a free layout that Graphviz draws, and only to the selected arrow.

- The selected arrow shows a small circle (a handle) on each bend point.
- A drag of a handle moves its bend. A drag on the path of the selected arrow adds a bend at the place of the press, and the drag moves it. The new bend goes between the two points of the nearest part of the arrow. A move of less than 4 pixels is a click.
- The bend snaps to the grid of 9 points. ⌥ (Alt) drags with no snap. During the drag, the path follows the pointer with no new render. The release stores the bends: one step of the history.
- A double-click on a handle removes its bend. The removal of the last bend gives the route of `neato` again.
- **Reset arrow route** (⌘K, and "Straighten" in the strip of a selected arrow) removes all the bends of the selected arrows, in one step.
- A press on an arrow that is not selected selects it, as before. The end controls of a selected arrow still move an end to a different node. A drag of a bend turns Auto off, as a drag of a node does. A locked diagram takes a change of a bend, for the same reason.

### Node moves

When a move takes both ends of an arrow (a drag or a nudge of a selection), the bends of the arrow move by the same amount. The arrow then keeps its shape, as it does during the drag of the nodes. When a move takes one end only, the bends stay where the person put them. This rule is simple, and a person can predict it.

### Considered options for the render

- **The bend points in the text for `neato`.** `neato` reads the `pos` of an arrow only with `-n2`, and viz-js does not give it. Also, `-n2` needs a position for each node, and a free layout places the nodes with no position.
- **A second render with all positions.** The app reads the places of all nodes from the first render, and gives `pos` to each node and each arrow for a second render. This needs `-n2` too, and it doubles the time of each render.
- **A path through the bends in the SVG** (chosen). The path is ours, so it is the same in the drag and in the render. The cost: the arrow does not go around the other nodes, and the box of the drawing does not include a bend outside the nodes.

## Save

On the desktop, a change of the layout stays in memory until a save, as an edit of the source does, and the window shows the diagram as unsaved. ⌘S writes the source and the side file. The side file write is a compare-and-swap on the hash of the bytes that the app read (`write_layout`), and an empty layout removes the side file. The shell writes the side file only next to a watched diagram, with a name that comes from the diagram file, with no link at that name, and with an atomic rename. A locked diagram still saves its layout, because the layout is not the source: a tool can make the file again, and the positions of the person stay. A layout file is not a diagram: `gx add` refuses it. The library holds entries by path, so the side file does not show in `gx ls`.

## Considered options

- **`pos` attributes in the source.** One file holds all of the diagram, and Graphviz reads it with no help. But a Mermaid source has no `pos`, a canvas edit of a DOT file must then write each position into the text of the person, and a tool that makes the file again removes the positions. The source then also has the engine attributes, and the diagram draws differently in other tools.
- **Nudges of the `dot` layout.** The app keeps `dot`, and it moves each node by a stored offset after the layout. The look of the automatic layout stays. But the arrows do not follow a moved node, and an offset has no meaning after an edit that changes the ranks.
- **A separate "Manual" engine or mode.** The person selects a mode, and the app then draws each node at its place. This adds a switch that a person must find and understand, and a diagram with two positions must still place the other nodes. The ambient model gives the same result with no switch.
- **The layout in the library metadata on the desktop.** The metadata already holds the hidden elements. But the positions are the work of the person, and that work must be a file that the person owns, next to the diagram (ADR 0003 has the same rule for a board). A rename or a move of a file outside the app also loses its metadata.

## Consequences

- An arrow with bend points does not go around the other nodes, and Fit does not see a bend outside the box of the nodes.
- A bend point of an arrow with a port ends on the shape of the node, not on the port.
- A diagram with a file can have one more file next to it. A rename of the diagram in the app does not rename the side file yet. The next save then writes a new side file for the new name.
- The app reads the side file after the view opens. Thus a diagram with a layout can show its automatic layout for a moment before the free layout.
- The app does not follow a change of the side file by a different tool while the view is open. A save then reports a conflict.
- The thumbnails of the library draw with no layout.
- The API is on the viewer state: `placeNodes` and `moveNodesBy` for a drag (part 2), `layoutPointAt`, `newNodePlaceNow` and `setGraphWithPlaces` for a new node at a point (part 3), and `activeBends`, `setArrowBends`, `removeBend` and `resetArrowRoutes` for bend points (part 4), with `DiagramLayout.arrows` by the key of `FreeLayout.arrowKeys`.
