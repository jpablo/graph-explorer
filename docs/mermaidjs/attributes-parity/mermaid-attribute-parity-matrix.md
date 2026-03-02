# Mermaid Attribute Parity Matrix

Last updated: 2026-03-01

Legend:
- `supported`: implemented and covered by tests
- `partial`: available with constraints
- `unsupported`: not implemented in current track

## Node Attributes

| Attribute | Mermaid Source | GE Attribute | Read Path (M->GE->Toolbar) | Write Path (Toolbar->GE->M) | Status | Example Output |
|---|---|---|---|---|---|---|
| Fill color | `fill` | `FillColor` | yes (effective style resolver) | yes (node `style` directive) | supported | `style A fill:#f9f` |
| Border color | `stroke` | `Color` | yes | yes | supported | `style A stroke:#333` |
| Border width | `stroke-width` | `PenWidth` | yes (numeric extraction) | yes (`px` emission) | supported | `style A stroke-width:2.0px` |
| Font color | `color` / text fill | `FontColor` | yes | yes | supported | `style A color:#fff` |
| Font family | `font-family` | `FontName` | yes | yes | supported | `style A font-family:Arial` |
| Font size | `font-size` | `FontSize` | yes (numeric extraction) | yes (`px` emission) | supported | `style A font-size:18.0px` |
| Mermaid class assignment | `:::class` / `class ...` | `mermaid_class` | yes (captured, used in resolver) | yes (`:::class` emitted) | supported | `A[Label]:::warn` |
| Class definitions | `classDef ...` | `mermaid_classDef_*`, `mermaid_classDefText_*` | yes | yes (merged emission) | supported | `classDef warn fill:#f66,color:#fff` |

## Edge Attributes

| Attribute | Mermaid Source | GE Attribute | Read Path (M->GE->Toolbar) | Write Path (Toolbar->GE->M) | Status | Example Output |
|---|---|---|---|---|---|---|
| Edge color | `linkStyle ... stroke` | `Color` | yes | yes (`linkStyle <index>`) | supported | `linkStyle 0 stroke:#f00` |
| Edge width | `linkStyle ... stroke-width` | `PenWidth` | yes | yes | supported | `linkStyle 0 stroke-width:2.0px` |
| Edge label font color | `linkStyle ... color` | `FontColor` | yes | yes | supported | `linkStyle 0 color:#fff` |
| Edge label font family | `linkStyle ... font-family` | `FontName` | yes | yes | supported | `linkStyle 0 font-family:Arial` |
| Edge label font size | `linkStyle ... font-size` | `FontSize` | yes | yes | supported | `linkStyle 0 font-size:16.0px` |
| Edge line style token | arrow/operator and `stroke` kind | `Style` | partial (subset mapping) | partial (subset mapping) | partial | `A -.-> B`, `A ==> B` |
| Edge interpolation | `linkStyle ... interpolate` | `mermaid_edgeInterpolate` | yes (captured) | yes (re-emitted in `linkStyle`) | supported | `linkStyle 0 interpolate:basis` |
| Default link style | `linkStyle default ...` | `mermaid_linkStyle_default` | yes (captured and resolved for read) | yes (graph-level re-emission) | supported | `linkStyle default stroke:#0044ff` |

## Group (Subgraph) Attributes

| Attribute | Mermaid Source | GE Attribute | Read Path (M->GE->Toolbar) | Write Path (Toolbar->GE->M) | Status | Example Output |
|---|---|---|---|---|---|---|
| Fill color | group style/class | `FillColor` | yes (effective resolver) | yes (`style <groupId>`) | supported | `style SG fill:#eef` |
| Border color | group style/class | `PenColor` | yes | yes | supported | `style SG stroke:#333` |
| Border width | group style/class | `PenWidth` | yes | yes | supported | `style SG stroke-width:2.0px` |
| Font color | group style/class | `FontColor` | yes | yes | supported | `style SG color:#111` |
| Font family | group style/class | `FontName` | yes | yes | supported | `style SG font-family:Arial` |
| Font size | group style/class | `FontSize` | yes | yes | supported | `style SG font-size:13.0px` |
| Group class abstraction reconstruction | `classDef` + class assignment patterns | semantic layer (future) | n/a | n/a | unsupported | n/a |

## Known Constraints

1. Export is intentionally flattened; source-structure fidelity is not guaranteed.
2. Mermaid class abstractions are preserved when present as metadata but not reconstructed from effective attrs.
3. Edge style/operator mapping remains a subset of Mermaid capabilities.
