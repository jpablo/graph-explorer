# Graph Explorer Attribute Model

This document explains how Graph Explorer represents DOT attributes in a simpler model and how it converts between DOT and the internal structures.

- Global defaults currently exist for nodes and edges (arrows).
- Per‑element attributes exist for nodes, edges, and groups.
- Style is expanded into sub‑attributes internally and recombined on export.

## Overview

1) DOT → Graphviz (flattened)
   - Graphviz parses DOT and returns a flattened structure — no `node[...]`/`edge[...]` default scopes remain.
   - See: viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:116

2) Flattened → Internal model
   - We expand `style` into sub‑attributes but do not auto‑extract common attributes into global defaults at this time.
   - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/VizViewerGraphElements.scala
   - The result is `ViewerGraphElements` with per‑element attributes and optional default node/edge attributes (when set by theme or code).
   - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala

3) Internal → DOT
   - Before emitting DOT, style sub‑attributes are recombined into a single `style` string, simulating inheritance by merging defaults and locals.
   - DOT text is then generated including graph attributes, optional `node[...]`/`edge[...]` default blocks, and elements.
   - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraph.scala:443 and shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElementsToText.scala

## Data Flow (Pipeline)

DOT text
 → Graphviz JSON (flattened, no defaults)
   - viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala
 → SimpleGraph → VizViewerGraphElements
   - shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/vizjs/simplegraph/ToViewerGraph.scala
 → Expand style sub‑attributes (filled/bold/invis/border/corner)
   - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/VizViewerGraphElements.scala
 → ViewerGraph (two‑level) for UI + edits
   - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraph.scala
 → Recombine style + emit DOT (with optional node/edge defaults)
   - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala
   - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElementsToText.scala

## Global vs Local

- Global defaults
  - `elements.defaultNodeAttributes`
  - `elements.defaultArrowAttributes`
  - Note: there is no `elements.defaultGroupAttributes` field; groups use per‑element attributes only. A constant baseline exists for groups when constructing them; it is not a global default block.
  - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala and shared/src/main/scala/org/jpablo/graphexplorer/viewer/models/ViewerElement.scala

- Local per‑element attributes
  - `ViewerNode.attributes`, `Arrow.attributes`, `ViewerGroup.attributes`
  - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/models/ViewerElement.scala

- Access/update APIs (UI wiring)
  - Diagram (graph) attributes lens: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala
  - Element attributes lens (by selection): shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala
  - There is no dedicated “default attributes” lens at present; defaults are typically set by theme (`withDefaultTheme`) or code paths.
  - ViewerState integrates these lenses: viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala

## Style Expansion and Consistency

- `StyleSubAttributes` expands and contracts style:
  - Expansion on import: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/VizViewerGraphElements.scala
  - Recombination on export: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala
- Consistency cleanup: remove `fillcolor` if `filled` isn’t effectively set (to mirror Graphviz behavior):
  - shared/src/main/scala/org/jpablo/graphexplorer/viewer/attributes/styleSubAttributes/StyleSubAttributes.scala

## Reading Effective Values

- Generic resolver `effectiveAttributeValue` returns from the provided attributes or the DOT default for that attribute.
- Global defaults (node/edge) are merged into style only at DOT export time via `combineStyleAttributes`; there is no general “effective value” merge across all attributes.
- See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraph.scala

## DOT Emission

- Recombine style sub‑attributes (defaults + locals):
  - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala
- Emit `graph [...]`, optional `node [...]` and `edge [...]` default blocks, groups as `subgraph` with `graph [...]`, then elements:
  - shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElementsToText.scala

## Special Handling

- Theme defaults for visible graphs (non‑destructive):
  - Set via `withDefaultTheme`; applied in `toVisibleGraph(...)`.
  - See: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala and shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraph.scala

## Key Files & Functions

- Parse + flatten: viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala
- Bridge to ViewerGraph: shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/vizjs/simplegraph/ToViewerGraph.scala
- Style expansion: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/VizViewerGraphElements.scala
- Two‑level structure: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala
- Effective value resolution: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraph.scala
- Recombine style + output DOT: shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala; shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElementsToText.scala
- UI wiring (lenses/signals): viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala; shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala

## Tests (Behavior Coverage)

- Style expansion: shared/src/test/scala/org/jpablo/graphexplorer/viewer/graph/VizViewerGraphElementsSpec.scala
- Style recombination and invariants: shared/src/test/scala/org/jpablo/graphexplorer/viewer/graph/CombineStyleAttributesSpec.scala
- DOT output formatting (defaults, groups, elements): shared/src/test/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/vizjs/ViewerGraphToTextSpec.scala

## Notes

- Graphviz’s JSON is the flattened truth. Graph Explorer expands style for editing and recombines it at export.
- Nested default scopes from DOT are not preserved; the app chooses a single optional set of defaults per element kind (node/edge) when present.
