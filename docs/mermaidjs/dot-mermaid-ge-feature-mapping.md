# DOT / Mermaid / GraphExplorer Feature Mapping

Last updated: 2026-03-01

## Scope

This matrix documents how features map:

- DOT <-> GraphExplorer (GE internal model)
- Mermaid <-> GraphExplorer

Legend used in cells:

- `D->GE`: DOT parse/import path
- `GE->D`: DOT serialize/export path
- `M->GE`: Mermaid parse/import path
- `GE->M`: Mermaid serialize/export path
- `gap`: unsupported or lossy mapping

Note: GraphExplorer is canonical by definition; the GE column names the concrete internal fields used for each feature.

Design note: GE deliberately stores a flattened low-level representation. Layered source semantics (DOT defaults, Mermaid `classDef`, etc.) are import/export concerns in the current architecture.

## Mapping Matrix

| DOT | Mermaid | GraphExplorer |
|---|---|---|
| **Graph kind (directed/undirected)**<br>`D->GE`: `graph` / `digraph` -> `ViewerGraph.tpe`.<br>`GE->D`: preserved. | **Flowchart is directed**<br>`M->GE`: always mapped to `GraphType.digraph`.<br>`GE->M`: always emitted as `flowchart ...` (directed).<br>`gap`: no Mermaid-side undirected parity. | `ViewerGraph.tpe: GraphType`. |
| **Graph title / label**<br>`D->GE`: graph `label` -> `Label` in graph attrs.<br>`GE->D`: emitted as graph `label`. | `M->GE`: `getDiagramTitle` / `getAccTitle` -> graph `Label`.<br>`GE->M`: graph `Label` -> frontmatter `title:`. | `elements.graphAttributes(Label.attrId)`. |
| **Direction**<br>`D->GE`: `rankdir` -> `Rankdir`.<br>`GE->D`: emitted as `rankdir`. | `M->GE`: Mermaid direction (`TB/BT/LR/RL`) -> `Rankdir`.<br>`GE->M`: `Rankdir` -> `flowchart <dir>`. | `Rankdir` graph attribute. |
| **Node identity**<br>`D->GE`: DOT node id -> `NodeId`.<br>`GE->D`: `NodeId` emitted as node id. | `M->GE`: vertex id -> `NodeId`.<br>`GE->M`: `NodeId` emitted as Mermaid node id. | `NodeId`. |
| **Node label**<br>`D->GE`: node `label` -> `Label`.<br>`GE->D`: `Label` emitted as node `label`. | `M->GE`: vertex `text` -> `Label` when different from id.<br>`GE->M`: `Label` -> Mermaid bracket label; omitted when redundant. | Node `Label` attribute. |
| **Node shape**<br>`D->GE`: node `shape` -> `Shape`.<br>`GE->D`: emitted as `shape`. | `M->GE`: Mermaid shape/type -> mapped to DOT-like `Shape` subset.<br>`GE->M`: DOT-like `Shape` mapped to Mermaid bracket syntax subset.<br>`gap`: not all shapes are bijective. | Node `Shape` attribute. |
| **Node fill/border/font attrs (`fillcolor`, `color`, `font*`, `penwidth`)**<br>`D->GE`: mostly direct attr mapping.<br>`GE->D`: mostly direct attr emission. | `M->GE`: class/default/inline Mermaid styles are resolved to effective toolbar attrs in read path.<br>`GE->M`: normalized node attrs are emitted as flat `style <nodeId> ...` CSS (merged with existing Mermaid CSS metadata).<br>`gap`: does not reconstruct higher-level class abstraction from effective attrs. | DOT-like attrs (`FillColor`, `Color`, `FontColor`, `FontName`, `FontSize`, `PenWidth`, etc.). |
| **Node style tokens (`style=dashed`, etc.)**<br>`D->GE`: style tokens expanded/combined via style-subattribute logic.<br>`GE->D`: emitted as DOT `style` with inheritance handling. | `M->GE`: vertex inline style strings currently stored as raw `Style` text when present.<br>`GE->M`: node `Style` emitted as Mermaid `style ...` only when CSS-like (`:`) tokens present.<br>`gap`: DOT-style toolbar tokens vs Mermaid CSS-style tokens not fully bridged. | Mixed model (`Style` + synthetic style sub-attrs). |
| **Node classes**<br>`D->GE`: DOT `class` attr supported in model.<br>`GE->D`: emitted as DOT `class` where used. | `M->GE`: node classes -> custom attr `mermaid_class` (space-separated).<br>`GE->M`: `mermaid_class` -> `:::className` suffix.<br>`gap`: no toolbar-native class editor; class semantics not merged into effective style yet. | Custom attr for Mermaid classes (`AttributeId("mermaid_class")`). |
| **Class definitions**<br>`D->GE`: no direct equivalent to Mermaid `classDef`. | `M->GE`: classDefs saved in graph attrs as `mermaid_classDef_<name>` (styles) and `mermaid_classDefText_<name>` (text styles), including `default`.<br>`GE->M`: merged and emitted back as `classDef` lines.<br>`gap`: source-level abstraction is not reconstructed from GE-effective attrs yet. | Graph custom attrs `mermaid_classDef_*` / `mermaid_classDefText_*`. |
| **Edge identity**<br>`D->GE`: edge id/title parsing -> `ArrowId` with source/target/seq.<br>`GE->D`: emitted as DOT edge statements. | `M->GE`: edge list converted to `Arrow` with per-(source,target) sequence.<br>`GE->M`: emitted as Mermaid edge statements.<br>`gap`: Mermaid `linkStyle` index/default metadata not fully represented. | `ArrowId`, `Arrow(source,target,seq,attributes)`. |
| **Edge label**<br>`D->GE`: `label` -> edge `Label`.<br>`GE->D`: emitted as edge `label`. | `M->GE`: edge text -> edge `Label`.<br>`GE->M`: edge `Label` -> `-->|label|` form. | Edge `Label` attribute. |
| **Edge line style (solid/dashed/dotted/bold)**<br>`D->GE`: edge `style` mapped directly.<br>`GE->D`: emitted directly. | `M->GE`: Mermaid edge `stroke` maps to GE style subset (`dotted -> dashed`, `thick -> bold`, normal omitted).<br>`GE->M`: style token controls arrow operator (`-.->`, `==>`) and normalized edge visual attrs emit `linkStyle <index> ...` CSS.<br>`gap`: still a subset of Mermaid edge semantics. | Edge `Style` / `EdgeStyle` attributes. |
| **Edge arrows (head/tail types, dir, ports)**<br>`D->GE`: supported (`ArrowHead`, `ArrowTail`, `Dir`, ports).<br>`GE->D`: emitted. | `M->GE`: limited to Mermaid edge type/stroke subset; head/tail/ports are not represented as GE-native arrow attrs.<br>`GE->M`: serializer emits Mermaid arrow operators; no full DOT arrowhead parity.<br>`gap`: intentional capability mismatch. | Rich DOT-like edge attrs exist in GE; Mermaid bridge only uses subset. |
| **Subgraphs / groups membership**<br>`D->GE`: clusters/subgraphs -> `ViewerGroup` + `memberships`.<br>`GE->D`: emitted with cluster/subgraph blocks. | `M->GE`: Mermaid `subgraph` -> `ViewerGroup`; node membership imported.<br>`GE->M`: groups emitted as Mermaid `subgraph ... end` blocks. | `groups` + `memberships`. |
| **Group label**<br>`D->GE`: cluster/subgraph `label` -> group `Label`.<br>`GE->D`: emitted. | `M->GE`: subgraph `title` -> group `Label`.<br>`GE->M`: group `Label` -> `subgraph id [title]`. | Group `Label` attribute. |
| **Group style (fill/border/font/class)**<br>`D->GE`: supported through group attrs.<br>`GE->D`: emitted. | `M->GE`: group class/default/inline inputs are flattened to effective toolbar attrs in read path where representable.<br>`GE->M`: normalized group attrs are emitted as flat `style <subgraphId> ...` CSS directives.<br>`gap`: class abstraction/source-structure is not reconstructed. | Group attrs exist; Mermaid bridge now writes supported style subset via flat directives. |
| **Default node/edge attrs**<br>`D->GE`: DOT defaults imported/extracted and represented in GE defaults.<br>`GE->D`: emitted as `node[...]` / `edge[...]`. | `M->GE`: `classDef default` / `linkStyle default` are captured as Mermaid-specific graph attrs (not GE default-* attrs).<br>`GE->M`: default Mermaid metadata is re-emitted (`classDef default`, `linkStyle default`) when present.<br>`gap`: no generalized mapping from GE default-* attrs back into Mermaid defaults yet. | `defaultNodeAttributes`, `defaultArrowAttributes`, plus Mermaid graph custom attrs. |
| **Unsupported/extra attrs passthrough**<br>`D->GE`: many attrs preserved as `AttributeId -> AttrValue` pairs.<br>`GE->D`: emitted when serializer knows/keeps them. | `M->GE`: Mermaid-specific extras stored as custom attrs (`mermaid_*`) where implemented.<br>`GE->M`: only known custom attrs currently serialized (not a generic passthrough for all Mermaid constructs). | Flexible `Attributes(Map[AttributeId, AttrValue])`. |

## High-Level Gap Summary

1. DOT is close to GE's native attribute model, so mapping is mostly direct.
2. DOT already accepts non-faithful source roundtrips due to flattening layered styles into effective attributes; this is a deliberate simplification.
3. Mermaid style semantics are distributed across `classDef`, class assignment, and inline style directives, and should be flattened with the same policy.
4. The missing adapter is an effective-style resolver + bidirectional style mapper:
   - Mermaid style sources -> GE toolbar attrs (read parity)
   - GE toolbar edits -> flat Mermaid output with visual fidelity (write parity)

## References

- Mermaid import backend:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/MermaidBackend.scala`
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/ToViewerGraph.scala`
- Mermaid export backend:
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/FromViewerGraph.scala`
- DOT import/export:
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/backends/graphviz/vizjs/simplegraph/ToViewerGraph.scala`
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElementsToText.scala`
- GE attribute model:
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/models/Attributes.scala`
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/ViewerGraphElements.scala`
