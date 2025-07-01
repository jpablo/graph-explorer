# Graph Explorer Attribute Model

This document explains how Graph Explorer translates DOT’s multi‑scope attribute inheritance into a simpler, two‑level model composed of:

- Global defaults per element kind (graph/node/edge/group)
- Local per‑element overrides

It also points to the key functions, files, and tests that implement and validate this behavior.

## Overview

1) DOT → Graphviz (flattened): Graphviz parses DOT and returns a structure where inheritance is already applied — no `node[...]`/`edge[...]` default scopes remain.
- viewer/backends/graphviz/Graphviz.scala:14
- viewer/state/InternalPhases.scala:131
- viewer/state/InternalPhases.scala:134 (note about “no node/arrow defaults”)

2) Flattened → Two‑Level: The app reconstructs a single global defaults set per element kind by scanning element attributes, extracting common values, and leaving only true overrides on elements.
- shared/graph/VizViewerGraphElements.scala:31 (pipeline)
- shared/graph/VizViewerGraphElements.scala:71 (extract defaults)
- Resulting structure: `ViewerGraphElements` (global defaults + local overrides)
  - shared/graph/ViewerGraphElements.scala:12

3) Two‑Level → DOT: When emitting DOT, style sub‑attributes are recombined into a `style` string and global defaults are printed via `node[...]` / `edge[...]` sections.
- shared/graph/ViewerGraphElements.scala:54 (combine style)
- shared/graph/ViewerGraphElementsToText.scala:210, 221, 231 (graph/node/edge blocks)

## Data Flow (Pipeline)

DOT text
 → Graphviz JSON (flattened, no defaults)
   - viewer/backends/graphviz/Graphviz.scala:14
 → SimpleGraph → VizViewerGraphElements
   - shared/backends/.../ToViewerGraph.scala:26
 → Expand style sub‑attributes (filled/bold/invis/border/corner)
   - shared/graph/VizViewerGraphElements.scala:52
 → Extract defaults and strip them from elements
   - shared/graph/VizViewerGraphElements.scala:71
 → ViewerGraph (two‑level) for UI + edits
   - shared/backends/.../ToViewerGraph.scala:10
 → Recombine style + emit DOT (with node/edge defaults)
   - shared/graph/ViewerGraphElements.scala:54
   - shared/graph/ViewerGraphElementsToText.scala

## Global vs Local (Two‑Level)

- Global defaults (per kind):
  - `elements.defaultNodeAttributes`
  - `elements.defaultArrowAttributes`
  - `elements.defaultGroupAttributes`
  - shared/graph/ViewerGraphElements.scala:28

- Local overrides (per element):
  - `ViewerNode.attributes`, `Arrow.attributes`, `ViewerGroup.attributes`
  - shared/models/ViewerElement.scala:21

- Access/update APIs (wired to UI):
  - Get defaults by target (graph/node/edge): shared/graph/AttributesOps.scala:84
  - Modify defaults by target: shared/graph/AttributesOps.scala:90
  - Lenses used by ViewerState for updates:
    - Diagram (graph) attrs: shared/graph/AttributesOps.scala:144
    - Default attrs by target: shared/graph/AttributesOps.scala:151
    - Element attrs by selection: shared/graph/AttributesOps.scala:158
  - ViewerState integration:
    - defaults + update vars: viewer/state/ViewerState.scala:174, 177, 180, 183

## How Defaults Are Extracted

- Expand `style` into sub‑attributes (virtual):
  - `StyleSubAttributes` parses and expands: shared/attributes/styleSubAttributes/StyleSubAttributes.scala
  - Applied to nodes/edges/groups/graph: shared/graph/VizViewerGraphElements.scala:52

- Find common attributes across all elements of the same kind, excluding element‑specific/layout keys:
  - Core: shared/graph/VizViewerGraphElements.scala:74
  - Node exclusions: `_gvid`, `name`, `pos`, `height`, `width`, `label` (94)
  - Edge exclusions: `_gvid`, `pos`, `lp`, `label` (104)
  - Group exclusions: `_gvid`, `name`, `cluster`, `lp`, `lheight`, `lwidth`, `label`, `rank` (114)
  - Skip extraction when there are 0/1 elements (76)
  - Strip found defaults from elements; store into `default*Attributes` (122)

- Style consistency cleanup:
  - Remove `fillcolor` if `filled` isn’t set, to match Graphviz behavior: shared/attributes/styleSubAttributes/StyleSubAttributes.scala:196

## Reading Effective Values

- Resolution order: local override → global defaults (kind) → DOT hardcoded default.
  - shared/graph/ViewerGraph.scala:202 (`effectiveAttributeValue`)

## Back to DOT

- Recombine style sub‑attributes using defaults (`combineStyleAttributes`):
  - shared/graph/ViewerGraphElements.scala:54–79
- Emit graph attributes, then `node[...]` and `edge[...]` defaults, then elements:
  - shared/graph/ViewerGraphElementsToText.scala:210 (graph), 221 (node), 231 (edge)
- Groups (clusters) carry attributes under `subgraph` with `graph [...]` block; label handling avoids accidental inheritance.
  - shared/graph/ViewerGraphElementsToText.scala

## Special Handling

- Theme defaults for visible graphs (non‑destructive):
  - Node/edge defaults added without overriding existing defaults: shared/graph/AttributesOps.scala:96, 111
  - Applied in `toVisibleGraph(...)`: shared/graph/ViewerGraph.scala:164

- Cluster attribute normalization during conversion (preserve/force cluster=true/false):
  - shared/backends/.../ToViewerGraph.scala:220

## Key Files & Functions

- Parse + flatten: viewer/backends/graphviz/Graphviz.scala:14
- Bridge to ViewerGraph: shared/backends/.../ToViewerGraph.scala:10, 26
- Style expansion: shared/graph/VizViewerGraphElements.scala:52
- Defaults extraction: shared/graph/VizViewerGraphElements.scala:71
- Two‑level structure: shared/graph/ViewerGraphElements.scala:12
- Effective value resolution: shared/graph/ViewerGraph.scala:202
- Recombine style + output DOT: shared/graph/ViewerGraphElements.scala:54; shared/graph/ViewerGraphElementsToText.scala
- UI wiring (lenses/signals): viewer/state/ViewerState.scala:174–184; shared/graph/AttributesOps.scala:144–162

## Tests (Behavior Coverage)

- Default extraction from flattened input:
  - shared/test/.../graph/VizViewerGraphElements2Spec.scala

- Style expansion & recombination:
  - shared/test/.../graph/AttributesOpsSpec.scala:62 (expand)
  - shared/test/.../graph/CombineStyleAttributesSpec.scala (combine cases)

- Separation of defaults vs local overrides:
  - shared/test/.../graph/AttributesOpsSpec.scala:129

- ViewerState updates for defaults/elements:
  - viewer/test/.../state/ViewerStateSpec.scala:75, 136

- DOT output shows node/edge default blocks and group handling:
  - shared/test/.../vizjs/ViewerGraphToTextSpec.scala (multiple tests)

- Cluster attribute normalization (conversion):
  - shared/test/.../vizjs/SimpleGraphConverterClusterAttributeSpec.scala

## Notes

- Graphviz’s JSON is the “flattened truth”. Graph Explorer reconstructs a clean global‑defaults + local‑overrides model for editing and for a consistent UI.
- The app does not attempt to preserve nested default scopes from DOT; it intentionally chooses a single set of defaults per element kind.

