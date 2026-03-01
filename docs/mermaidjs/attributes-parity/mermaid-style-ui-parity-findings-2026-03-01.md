# Mermaid Style UI Parity Findings (2026-03-01)

## Scope

This document captures findings from investigating why a selected Mermaid node (example: `CodeMirror (text editor)`) does not populate the style toolbar with the node's effective style.

Focus area:
- Mermaid parser -> internal `ViewerGraph` attribute mapping
- `ViewerGraph` attributes -> toolbar read model
- Toolbar edits -> Mermaid serialization

Design-policy context:
- GE core is intentionally low-level (effective graph + visual attrs), with no higher-level source semantics.
- This already applies to DOT style layering (flattened import/export behavior).
- Mermaid should follow the same approach in the current architecture.

## Symptom

- A Mermaid node is selected in the canvas.
- The style toolbar does not reflect visible node style (fill, border, font).

This indicates that selection works, but Mermaid style data is not being normalized into the attribute model consumed by the toolbar.

## What Works Today

### Selection and IDs

- Mermaid selection strategy can resolve node/edge/group IDs from Mermaid SVG (`data-id`, Mermaid DOM ids, `LS-*`/`LE-*` classes).
- Relevant code:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/selection/SelectableElementStrategy.scala`

Conclusion: this is not primarily a selection bug.

### Mermaid parsing and conversion is partially implemented

- Mermaid backend parses:
  - vertices (`styles`, `classes`, `shape`)
  - edges (`stroke`, `type`, `text`)
  - subgraphs (`id`, `title`, `nodes`)
  - classDefs (but only `styles`, excluding `"default"`)
- Relevant code:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/MermaidBackend.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/MermaidJS.scala`

### Mermaid serialization supports basic class/style output

- Emits:
  - `classDef ...` from `mermaid_classDef_*` graph attributes
  - `:::className` from node `mermaid_class`
  - `style nodeId ...` only when `Style` contains CSS-like declarations (`:`)
- Relevant code:
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/FromViewerGraph.scala`

## Root Cause

The toolbar reads selected element attributes directly from `ViewerGraph` without Mermaid-specific style resolution.

- Toolbar read path:
  - `state.elementAttributesUpdates(...)` in
    `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/AttributesToolbar.scala`
  - `getAttributesUpdatesById(...)` in
    `shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala`

For Mermaid nodes, conversion currently stores raw CSS in `Style` and class names in `mermaid_class`, but does not project these into toolbar-facing DOT attributes:
- `FillColor`
- `Color` / `PenColor`
- `FontColor`
- `PenWidth`
- `FontName`
- `FontSize`
- style sub-attributes used by existing toolbar controls

Relevant code:
- `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/ToViewerGraph.scala`

## Mermaid Flattening API Investigation

Question explored:
- Does Mermaid already expose a Graphviz-like flatten API that returns effective per-element styles?

Finding:
- In the version used by this repo (`mermaid@10.9.5`), Mermaid does not expose a public flatten/normalize API for effective styles.
- Public API surface is parse/render oriented (`parse`, `render`, `getDiagramFromText`, config helpers).
- Flowchart DB surfaces layered inputs (`getVertices`, `getEdges`, `getClasses`) where:
  - node inline styles are stored as raw style arrays
  - class assignments are stored separately on nodes
  - class definitions are stored separately (`classes`)
  - classDefs are rendered as CSS rules at render time

Conclusion:
- Mermaid leaves style composition to renderer/CSS application; it does not return a flat effective-style graph model.
- GE must compute effective styles in the Mermaid import/read path if toolbar parity is required.

## Attribute Parity Gaps

### 1. Read-side parity gap (primary issue)

- No Mermaid effective-style resolver combines:
  - `classDef default`
  - classDefs referenced by node classes
  - inline node style directives
- Result: toolbar sees missing/default values even when SVG is visibly styled.

### 2. Model parity gap

- `MermaidGraph` currently stores `classDefs: Map[String, List[String]]` only.
- Mermaid also has `textStyles`; these are exposed in facade but dropped in conversion.
- `"default"` classDef is filtered out, preventing default-style inheritance modeling.

Files:
- `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/MermaidGraph.scala`
- `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/MermaidBackend.scala`

### 3. Write-side parity gap

- Toolbar edits are DOT-attribute-oriented.
- Mermaid serializer primarily emits node `style ...` from raw `Style` and class references, not from normalized toolbar fields.
- Many style edits performed through toolbar may not round-trip back to Mermaid style/classDef directives.

File:
- `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/FromViewerGraph.scala`

### 4. Coverage gap

- Existing tests emphasize Mermaid text serialization.
- Missing tests for parser -> resolved attribute model parity for toolbar consumption.

File:
- `shared/src/test/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/FromViewerGraphSpec.scala`

## What Is Needed For Full Mermaid Style Awareness

1. Introduce a Mermaid style resolution layer for selected elements.
   - Resolve effective node style from class/default/inline Mermaid sources.
2. Map resolved Mermaid CSS into existing toolbar attributes.
   - `fill -> FillColor`
   - `stroke -> Color/PenColor`
   - `stroke-width -> PenWidth`
   - `color/text fill -> FontColor`
   - `font-family -> FontName`
   - `font-size -> FontSize`
   - dashed/dotted/bold mappings to style controls
3. Keep GE core flattened and low-level.
   - Do not introduce Mermaid higher-level semantics (for example, first-class `classDef`) into GE core.
   - Treat Mermaid constructs as import/export concerns that resolve into effective element-level attrs.
4. Extend parity to edges/groups.
   - Include `linkStyle`, edge defaults, subgraph classes/styles where available.
5. Add bidirectional mapping for toolbar edits in Mermaid mode.
   - Serialize toolbar changes into a flat Mermaid representation that preserves visual output.
   - Source-level structural fidelity (`classDef` reuse patterns) is not required in this phase.
6. Add focused parity tests.
   - parser -> resolved attributes
   - toolbar edit -> Mermaid text
   - round-trip with mixed classDef + inline styles

## Suggested Delivery Plan

### Phase 1 (fix current bug)
- Implement read-side style resolution for Mermaid nodes.
- Ensure toolbar reflects selected Mermaid node effective styles.

### Phase 2
- Extend read-side resolution to edges and groups.

### Phase 3
- Implement write-side Mermaid serialization from normalized toolbar attributes.

### Phase 4
- Add tests for all above paths and mixed precedence cases.

## Open Design Decisions

1. Precedence order for multiple node classes (left-to-right in Mermaid class assignment).
2. Exact visual-fidelity target for flattened Mermaid export (what diffs are acceptable).
3. Where to draw the boundary for future semantic-layer integration (outside GE core).
