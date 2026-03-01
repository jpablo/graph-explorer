# DOT vs Mermaid Feature Comparison

This document provides an exhaustive comparison of features supported by the DOT (Graphviz) and Mermaid backends in Graph Explorer.

## Feature Matrix

### Graph Structure

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Directed graphs | ✅ | ✅ | ✅ | ✅ (always TD) |
| Undirected graphs | ✅ | ✅ | ❌ | ❌ |
| Graph name/id | ✅ | ✅ | ❌ | ❌ |

### Nodes

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Basic nodes | ✅ | ✅ | ✅ | ✅ |
| Node labels | ✅ | ✅ | ✅ | ✅ |
| Node shapes (~60 types) | ✅ | ✅ | ✅ (subset) | ✅ (subset) |
| HTML labels | ✅ | ✅ | ❌ | ❌ |

### Edges

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Basic edges | ✅ | ✅ | ✅ | ✅ |
| Edge labels | ✅ | ✅ | ✅ | ✅ |
| Edge styles (dashed/dotted/bold) | ✅ | ✅ | ✅ | ✅ |
| Ports (head/tail) | ✅ | ✅ | ❌ | ❌ |
| Arrow head/tail types | ✅ | ✅ | ❌ | ❌ |
| Edge direction (dir) | ✅ | ✅ | ❌ | ❌ |

### Subgraphs/Groups

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Subgraphs | ✅ | ✅ | ✅ | ✅ |
| Nested subgraphs | ✅ | ✅ | ❌ | ❌ |
| Subgraph labels | ✅ | ✅ | ✅ | ✅ |
| Cluster attribute | ✅ | ✅ | ❌ | ❌ |

### Layout

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Direction (rankdir) | ✅ TB/LR/BT/RL | ✅ | ✅ (direction) | ✅ (defaults to TB) |
| Layout engine | ✅ (8 engines) | ✅ | ❌ | ❌ |
| Node separation | ✅ | ✅ | ❌ | ❌ |
| Rank separation | ✅ | ✅ | ❌ | ❌ |
| Splines | ✅ | ✅ | ❌ | ❌ |

### Styling

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Fill color | ✅ | ✅ | ❌ | ❌ |
| Border color | ✅ | ✅ | ❌ | ❌ |
| Font name | ✅ | ✅ | ❌ | ❌ |
| Font size | ✅ | ✅ | ❌ | ❌ |
| Font color | ✅ | ✅ | ❌ | ❌ |
| Pen width | ✅ | ✅ | ❌ | ❌ |
| CSS classes | ✅ | ✅ | ✅ | ❌ |
| Inline styles | ❌ | ❌ | ✅ | ❌ |

### Default Attributes

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| Default node attributes | ✅ | ✅ | ❌ | ❌ |
| Default edge attributes | ✅ | ✅ | ❌ | ❌ |

### Other Features

| Feature | DOT Parse | DOT Serialize | Mermaid Parse | Mermaid Serialize |
|---------|:---------:|:-------------:|:-------------:|:-----------------:|
| URLs | ✅ | ✅ | ❌ | ❌ |
| Tooltips | ✅ | ✅ | ❌ | ❌ |
| Position (pos) | ✅ | ✅ | ❌ | ❌ |
| Rank constraints | ✅ | ✅ | ❌ | ❌ |
| Images | ✅ | ✅ | ❌ | ❌ |

---

## Shape Mapping

The following table shows how shapes are mapped between Mermaid and DOT formats:

| Mermaid Shape | DOT Shape | Mermaid Syntax |
|---------------|-----------|----------------|
| rect / rectangle | box | `[text]` |
| round / rounded | box | `(text)` |
| stadium | box | `([text])` * |
| circle | circle | `((text))` |
| ellipse | ellipse | `([text])` * |
| diamond / rhombus | diamond | `{text}` |
| hexagon | hexagon | `{{text}}` |
| parallelogram | parallelogram | `[/text/]` |
| trapezoid | trapezium | `[/text\]` |
| trapezoid-alt | invtrapezium | `[\text/]` |
| cylinder / database | cylinder | `[(text)]` |
| doublecircle | doublecircle | `(((text)))` |

\* Stadium and ellipse both serialize to `([text])`. On re-parse, `([text])` is interpreted as ellipse, so stadium does not roundtrip correctly.

---

## Implementation Files

### DOT Backend

| Purpose | File |
|---------|------|
| Parse (SimpleGraph → ViewerGraph) | `shared/.../backends/graphviz/vizjs/simplegraph/ToViewerGraph.scala` |
| Serialize (ViewerGraph → DOT text) | `shared/.../graph/ViewerGraphElementsToText.scala` |
| Attributes definitions | `shared/.../formats/dot/attributes/DotAttributes.scala` |
| SimpleGraph model | `shared/.../backends/graphviz/vizjs/simplegraph/SimpleGraph.scala` |

### Mermaid Backend

| Purpose | File |
|---------|------|
| Parse (MermaidGraph → ViewerGraph) | `shared/.../backends/mermaid/ToViewerGraph.scala` |
| Serialize (ViewerGraph → Mermaid text) | `shared/.../backends/mermaid/FromViewerGraph.scala` |
| MermaidGraph model | `shared/.../backends/mermaid/MermaidGraph.scala` |

---

## Key Gaps in Mermaid Implementation

### High Priority

1. **No nested subgraphs** - Only flat subgraph structure supported

### Medium Priority

2. **No color support** - Fill color, border color, font color not serialized
3. **No font attributes** - Font name and size ignored during serialization
4. **No CSS class serialization** - Classes are parsed but not output

### Lower Priority

5. **Limited edge customization** - No arrow head/tail type support
6. **No port support** - Edge connection points not supported
7. **No URL/tooltip support** - Interactive attributes not available

---

## DOT Attributes Reference

The DOT backend supports the following attribute categories:

### Node Attributes
- `label`, `shape`, `style`, `color`, `fillcolor`, `fontcolor`, `fontname`, `fontsize`
- `width`, `height`, `fixedsize`, `margin`, `orientation`
- `penwidth`, `peripheries`, `regular`, `sides`
- `pos`, `rects`, `vertices`
- `URL`, `target`, `tooltip`, `class`, `colorscheme`
- `image`, `imagepath`, `imagepos`

### Edge Attributes
- `label`, `style`, `color`, `fontcolor`, `fontname`, `fontsize`
- `penwidth`, `len`, `constraint`, `dir`
- `arrowhead`, `arrowtail`, `arrowsize`
- `headport`, `tailport`, `samehead`, `sametail`
- `labeldistance`, `labelfloat`, `labelfontcolor`, `labelfontname`
- `URL`, `target`, `tooltip`, `tailURL`, `tailtarget`, `tailtooltip`
- `pos`, `lp`, `tail_lp`, `tailclip`
- `layer`, `class`, `colorscheme`

### Graph Attributes
- `label`, `labelloc`, `labeljust`
- `rankdir`, `layout`, `splines`, `overlap`, `normalize`, `start`
- `nodesep`, `ranksep`, `pad`, `ratio`
- `bgcolor`, `fontname`, `fontsize`
- `lp`, `lheight`, `lwidth`

### Cluster/Subgraph Attributes
- `label`, `labelloc`, `labeljust`
- `style`, `color`, `pencolor`, `bgcolor`, `fillcolor`
- `fontname`, `fontsize`, `fontcolor`
- `penwidth`, `cluster`, `rank`
- `URL`, `target`, `tooltip`, `class`, `colorscheme`

---

## Mermaid Attributes Reference

The Mermaid backend supports a simpler attribute model:

### Vertex (Node) Attributes
- `id`, `text` (label), `shape`
- `domId`, `labelType`
- `styles` (inline CSS), `classes` (CSS classes)

### Edge Attributes
- `start`, `end` (source/target)
- `text` (label), `labelType`
- `edgeType`, `stroke` (normal/dotted/thick)

### Subgraph Attributes
- `id`, `title` (label)
- `nodes` (list of contained node IDs)

### Graph Attributes
- `direction` (TB/BT/LR/RL)
