# Mermaid Flat Export Policy (GE -> Mermaid)

Last updated: 2026-03-01

## Purpose

Define deterministic rules for serializing GraphExplorer's low-level effective attributes into Mermaid flowchart text.

This is the `MP3-T1` contract for write-parity implementation tasks (`MP3-T2..T4`).

## Design Contract

- GE is canonical for this phase: flattened element-level structure + visual attrs.
- Mermaid layered semantics are not reconstructed by default.
- Export prioritizes rendered visual fidelity over source-structure fidelity.
- Round-trip source text can differ while preserving effective rendering.

## Deterministic Emission Order

Serializer output order must be stable:

1. Frontmatter title block (if graph `Label` is present).
2. `flowchart <direction>`.
3. `classDef` lines, sorted by class name:
   - merge `mermaid_classDef_<name>` + `mermaid_classDefText_<name>` into one declaration body.
4. `linkStyle default ...` line (if default link style/interpolate metadata exists).
5. `subgraph` blocks, sorted by group id:
   - nodes inside each group sorted by node id.
6. Standalone node declarations (remaining nodes).
7. Edge statements, sorted by `(sourceId, targetId, seq)`.
8. Node `style <id> ...` directives for nodes with CSS-like style payloads.

## Exported Metadata Sources

### Graph-level Mermaid metadata

- `mermaid_classDef_<name>` -> class visual declarations.
- `mermaid_classDefText_<name>` -> class text declarations.
- `mermaid_linkStyle_default` -> default edge style body.
- `mermaid_linkInterpolate_default` -> appended as `interpolate:<value>` in default link style.

### Node-level Mermaid metadata

- `mermaid_class` -> `:::className` suffix on node declarations.
- CSS-like `Style` values (`k:v,...`) -> `style <nodeId> ...`.

## Flattening Rules

1. Parse Mermaid declaration bodies with the shared declaration parser (`MermaidStyleDeclarations.parse`).
2. Normalize keys to lowercase and trim whitespace.
3. Merge layers in policy order:
   - for class definitions: style declarations, then text declarations.
   - for default link style: style declarations, then `interpolate`.
4. If the merged declaration map is empty, omit the directive.

## Planned Mapping Rules (Next Tasks)

The following rules are defined now and implemented in `MP3-T2..T4`:

- Node effective attrs (`FillColor`, `Color`, `PenWidth`, `FontColor`, `FontName`, `FontSize`) -> flat Mermaid node `style` declarations.
- Edge effective attrs -> Mermaid `linkStyle <index>` declarations (with deterministic edge index assignment).
- Group effective attrs -> Mermaid output only where Mermaid syntax supports equivalent rendering; unsupported attrs are omitted.

## Explicit Non-Goals (Current Track)

- Rebuilding original Mermaid abstractions (`class` grouping strategy, declaration placement, authoring layout).
- Guaranteeing text-level identity after round-trip.
- Supporting non-flowchart Mermaid diagram families.

## Validation Targets

- Export output is deterministic for identical GE input.
- Existing Mermaid metadata (`mermaid_*`) is preserved in emitted Mermaid text.
- New write-back work can layer onto this contract without changing GE core model.
