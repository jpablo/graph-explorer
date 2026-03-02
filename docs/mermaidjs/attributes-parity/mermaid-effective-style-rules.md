# Mermaid Effective Style Rules (Flattening Spec)

Last updated: 2026-03-01

## Purpose

Define deterministic rules to convert Mermaid layered style constructs into GE's flattened low-level attribute model.

This document is the `MP0-T1` contract for subsequent implementation tasks.

## Design Constraint

- GE core stores effective element-level visual attributes only.
- Mermaid semantic constructs (`classDef`, class assignment, `style`, `linkStyle`) are import/export concerns.
- Roundtrip source-structure fidelity is not required; visual fidelity is prioritized.

## Inputs Considered

### Nodes

- Mermaid node shape/type
- `classDef default ...`
- node class assignments (`class ...`, `:::...`)
- node inline styles (`style nodeId ...`)

### Edges

- Mermaid edge operator/stroke (`-->`, `-.->`, `==>`)
- `linkStyle default ...`
- `linkStyle <index-list> ...`

### Groups (subgraphs)

- subgraph title/id and membership
- group class/style directives when available from parser/model

## Precedence Model

### Node precedence

From weakest to strongest:

1. Mermaid engine defaults (implicit)
2. `classDef default`
3. classDefs referenced by the node, in Mermaid class order
4. node inline `style ...`
5. explicit node structural tokens (shape syntax in node declaration)

Rule: later layers override earlier layers for the same normalized key.

### Edge precedence

From weakest to strongest:

1. Mermaid engine defaults (implicit)
2. edge stroke/operator defaults derived from arrow syntax
3. `linkStyle default`
4. `linkStyle` entries targeting the edge index
5. per-edge explicit style constructs (if present in parser model)

Rule: later layers override earlier layers for the same normalized key.

### Group precedence

From weakest to strongest:

1. Mermaid engine defaults
2. class/style defaults for groups (if represented)
3. classDefs assigned to the group
4. group inline style directives

Rule: later layers override earlier layers for the same normalized key.

## CSS Declaration Normalization

Input form: comma-separated declarations (`k:v`).

Normalization rules:

1. Trim surrounding whitespace.
2. Split each declaration at the first `:`.
3. Lowercase property names.
4. Keep original value casing except trim outer whitespace.
5. Ignore malformed fragments without `:`.
6. Last declaration wins for duplicated keys within the same layer.

## Mapping to GE Attributes (Read Path)

Target GE attributes (initial parity scope):

- `fill` -> `FillColor`
- `stroke` -> `Color` (nodes/edges) and `PenColor` (groups where applicable)
- `stroke-width` -> `PenWidth`
- `color` and text fill equivalents -> `FontColor`
- `font-family` -> `FontName`
- `font-size` -> `FontSize`

Style token mapping:

- Mermaid dotted/thick/solid concepts -> GE `Style`/`EdgeStyle` subset
- Unsupported style keys are ignored by GE core but may be preserved for exporter-side fallback where feasible.

## Conflict Handling

1. If two layers set the same property, higher precedence wins.
2. If mapped GE attributes conflict (`Color` vs `PenColor` target ambiguity), prefer target by element kind:
   - node/edge -> `Color`
   - group -> `PenColor`
3. If value parsing fails for typed fields (`FontSize`, `PenWidth`), drop that property and continue.
4. Unknown properties do not block import.

## Flattened Export Policy (GE -> Mermaid)

1. Serialize effective GE attrs into flat Mermaid output.
2. Prefer explicit per-element style directives where needed for visual fidelity.
3. Reconstructing original classDef abstractions is not required.
4. Structural differences are acceptable if rendered visuals are equivalent within tolerance.

Detailed write-policy contract:
- `docs/mermaidjs/attributes-parity/mermaid-flat-export-policy.md`

## Acceptance Criteria for MP0-T1

1. Node/edge/group precedence order is defined and deterministic.
2. Declaration normalization behavior is defined.
3. Mapping targets to GE attrs are defined for phase-1 parity fields.
4. Export policy explicitly states visual-fidelity-over-structure.
5. Rules are referenced by parity plan and can be implemented without ambiguity.

## Implementation Notes

- This spec is intentionally format-bridge-specific; it does not change GE core data model.
- Future semantic layers may model `classDef` and related constructs explicitly, outside current GE core scope.
