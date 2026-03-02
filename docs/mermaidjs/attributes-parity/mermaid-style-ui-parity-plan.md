# Mermaid Style UI Parity Plan

Last updated: 2026-03-01

Related findings:
- `docs/mermaidjs/attributes-parity/mermaid-style-ui-parity-findings-2026-03-01.md`

## Goal

Make Mermaid diagrams first-class in the attributes toolbar:
- selected Mermaid elements show effective style values in toolbar controls
- toolbar edits round-trip to Mermaid text with high visual fidelity

## Non-goals (initial track)

- Full parity with every Graphviz-only attribute
- Mermaid diagrams beyond flowcharts
- Backward migration of old persisted Mermaid metadata formats

## Status Legend

- `todo`: not started
- `in_progress`: actively being worked
- `blocked`: waiting on a concrete blocker
- ✅: acceptance criteria met, with evidence

## Tracking Snapshot

Current track: `Phase 3`  
Current focus: `MP3-T2`  
Resume from: `MP3-T2 - implement node write-back from normalized attrs`

## Design Principle (confirmed)

- GE core remains low-level/canonical: graph structure + effective visual attributes.
- Importers flatten higher-level source semantics into effective element attributes.
- Exporters prioritize visual fidelity over source-structure fidelity.
- DOT already follows this policy; Mermaid parity will follow the same policy.
- Higher-level semantics (for example, Mermaid `classDef`) are future semantic-layer candidates, not GE-core concerns today.

## Confirmed Constraint

- Mermaid (`11.12.0` in this repo) does not provide a public flatten/effective-style API comparable to the Graphviz simplegraph flattening flow.
- Therefore, `MP0-T2` + `MP1-T1` must implement app-side style flattening from Mermaid layered inputs (`classDef`/class assignment/inline style).

## Task Board

| ID | Task | Status | Dependencies | Acceptance Criteria | Evidence | Notes |
|---|---|---|---|---|---|---|
| MP0-T1 | Define Mermaid effective-style precedence and flattening rules | ✅ | none | Rules doc merged and reflected in implementation notes | `docs/mermaidjs/attributes-parity/mermaid-effective-style-rules.md` | Includes node/edge/group precedence and conflict resolution |
| MP0-T2 | Extend Mermaid parsing to capture fields needed for flattening | ✅ | MP0-T1 | Parser exposes enough style/default/class data to compute effective styles | `MermaidGraph.scala`, `MermaidJS.scala`, `MermaidBackend.scala`, `ToViewerGraph.scala` | Preserves `classDef default`, `textStyles`, and linkStyle defaults as mermaid-specific attrs |
| MP0-T3 | Add CSS declaration parser utility (`k:v` list -> normalized map) | ✅ | MP0-T1 | Utility handles whitespace, repeated keys, malformed fragments safely | `MermaidStyleDeclarations.scala`, `MermaidStyleDeclarationsSpec.scala` | Shared utility with unit tests |
| MP0-T4 | Add baseline tests for effective-style inputs | ✅ | MP0-T2, MP0-T3 | Tests cover inline style, class styles/text styles, default styles | `ToViewerGraphSpec.scala`, `FromViewerGraphSpec.scala`, `MermaidStyleDeclarationsSpec.scala` | Baseline input-capture coverage in `shared` tests |
| MP1-T1 | Implement node effective-style resolver with precedence | ✅ | MP0-T4 | Resolver output deterministic and documented precedence | `AttributesOps.scala`, `MermaidStyleDeclarations.scala` | Applies default classDef < classDefs < inline precedence with explicit-attr override protection |
| MP1-T2 | Map resolved node style to toolbar attributes | ✅ | MP1-T1 | Selected Mermaid node populates fill/border/font fields | `AttributesOps.scala` | Maps Mermaid CSS into `FillColor`, `Color`, `PenWidth`, `FontColor`, `FontName`, `FontSize` |
| MP1-T3 | Integrate resolver into toolbar read path for Mermaid mode | ✅ | MP1-T2 | `elementAttributesUpdates` returns normalized statuses in Mermaid mode | `AttributesOps.getAttributesUpdatesById` | Resolver is applied in the shared attribute read path |
| MP1-T4 | Add node parity tests (selection -> toolbar updates) | ✅ | MP1-T3 | Tests assert expected `AttributeUpdates` for styled nodes | `AttributesOpsSpec.scala` | Added precedence and explicit-override test coverage |
| MP1-T5 | Manual verification pass for node toolbar parity | ✅ | MP1-T4 | Visual verification on sample Mermaid diagrams | Manual UI check in local app (`localhost:5173`) for node/edge/group selection against controlled Mermaid sample | Node, edge, and group toolbar values match expected effective styles |
| MP2-T1 | Capture edge style metadata needed for parity (`linkStyle`, defaults) | ✅ | MP0-T2 | Edge model carries style directives required for read/write parity | `MermaidGraph.scala`, `MermaidJS.scala`, `MermaidBackend.scala`, `ToViewerGraph.scala` | Captures per-edge style/interpolate and `linkStyle default` |
| MP2-T2 | Implement edge effective-style resolver and toolbar mapping | ✅ | MP2-T1 | Arrow toolbar reflects Mermaid edge color/style/width where representable | `AttributesOps.scala` | Resolves `default + per-edge` Mermaid style into edge attrs with explicit-attr override protection |
| MP2-T3 | Extend subgraph/group style capture (classes and style directives) | ✅ | MP0-T2 | Group model preserves Mermaid class/style where available | `MermaidGraph.scala`, `MermaidJS.scala`, `MermaidBackend.scala`, `ToViewerGraph.scala` | Captures subgraph classes and maps to `mermaid_class` |
| MP2-T4 | Implement group effective-style mapping to toolbar attrs | ✅ | MP2-T3 | Group toolbar reflects resolved border/fill/font values | `AttributesOps.scala` | Maps group effective Mermaid style to `FillColor`, `PenColor`, `PenWidth`, `Font*` |
| MP2-T5 | Add edge/group parity tests | ✅ | MP2-T2, MP2-T4 | Automated tests cover read-model parity for edges/groups | `AttributesOpsSpec.scala`, `ToViewerGraphSpec.scala` | Includes precedence and explicit-override coverage for edge/group |
| MP3-T1 | Define flat Mermaid export policy from GE attrs | ✅ | MP1-T5, MP2-T5 | Policy documented with deterministic rules | `docs/mermaidjs/attributes-parity/mermaid-flat-export-policy.md`, `FromViewerGraph.scala`, `FromViewerGraphSpec.scala` | Added deterministic emission order and metadata-preserving export scaffolding |
| MP3-T2 | Implement node write-back from normalized attrs to Mermaid text | todo | MP3-T1 | Node toolbar edits persist in flat Mermaid output | pending | No regressions for existing serialization tests |
| MP3-T3 | Implement edge write-back to Mermaid `linkStyle`/edge syntax | todo | MP3-T1 | Edge toolbar edits persist in Mermaid text | pending | Handle index-based linkStyle safely |
| MP3-T4 | Implement group write-back strategy | todo | MP3-T1 | Group toolbar edits persist in Mermaid syntax when supported | pending | Graceful fallback for unsupported attrs |
| MP3-T5 | Add round-trip tests (parse -> edit -> serialize -> parse) | todo | MP3-T2, MP3-T3, MP3-T4 | Round-trip preserves visual intent with flattened output | pending | Source-structure diffs are acceptable by policy |
| MP4-T1 | Update Mermaid docs with supported attribute parity matrix | todo | MP3-T5 | Docs list supported/partial/unsupported fields with examples | pending | Update `docs/mermaidjs/*` |
| MP4-T2 | Regression sweep (`sbt test`, `npm run build`) | todo | MP4-T1 | All tests pass and production build succeeds | pending | Record exact commands/date |
| MP4-T3 | Closeout note with residual gaps and follow-ups | todo | MP4-T2 | Clear residual scope + next backlog | pending | Final wrap-up for this track |

## Phase Gates

| Phase | Gate | Exit Criteria |
|---|---|---|
| Phase 0: Modeling + Parse Preservation | G0 | MP0-T1..T4 ✅; style model and parser coverage in place |
| Phase 1: Node Read Parity | G1 | MP1-T1..T5 ✅; selected Mermaid nodes correctly populate toolbar |
| Phase 2: Edge/Group Read Parity | G2 | MP2-T1..T5 ✅; edge and group toolbars reflect Mermaid style |
| Phase 3: Write Parity | G3 | MP3-T1..T5 ✅; toolbar edits round-trip into Mermaid text |
| Phase 4: Hardening + Docs | G4 | MP4-T1..T3 ✅; docs and regression verification complete |

## Test Matrix (Target)

| Invariant ID | Invariant | Planned Test Coverage | Status |
|---|---|---|---|
| MP-INV-01 | Node effective style precedence is deterministic | unit tests on resolver precedence chain | todo |
| MP-INV-02 | `default` classDef contributes when no stronger override exists | parser/resolver tests | todo |
| MP-INV-03 | ClassDef `textStyles` map to font-related toolbar attrs | parser + mapping tests | todo |
| MP-INV-04 | Inline node style overrides classDef for conflicting keys | resolver tests | todo |
| MP-INV-05 | Selected Mermaid node produces expected `AttributeUpdates` statuses | viewer/state tests | todo |
| MP-INV-06 | Edge style metadata survives parse and maps to arrow toolbar attrs | parser + mapping tests | todo |
| MP-INV-07 | Group style metadata survives parse and maps to group toolbar attrs | parser + mapping tests | todo |
| MP-INV-08 | Node toolbar edit writes Mermaid text according to policy | round-trip tests | todo |
| MP-INV-09 | Edge toolbar edit writes Mermaid `linkStyle` or equivalent | round-trip tests | todo |
| MP-INV-10 | Serialization remains stable for existing Mermaid scenarios | existing + new serializer tests | todo |

## Session Update Protocol

1. At start of a session:
   - set one task to `in_progress`
   - update `Current focus` and `Resume from`
2. At task completion:
   - set task to ✅
   - add evidence (test/file/command)
3. If blocked:
   - set task to `blocked`
   - add exact blocker and unblock condition in Notes
4. At end of session:
   - append one line in Session Log
   - update `Last updated`

## Session Log

| Date | Session Summary | Tasks Touched | Verification | Next Resume Point |
|---|---|---|---|---|
| 2026-03-01 | Created initial parity findings and this execution plan | MP0-T1 (planned) | Analysis-only; no code changes yet for parity implementation | MP0-T1 |
| 2026-03-01 | Codified flatten-first policy (DOT-aligned) across findings/plan/mapping docs; clarified future semantic-layer direction | MP0-T1 (policy definition) | Docs review | MP0-T1 |
| 2026-03-01 | Defined formal Mermaid effective-style precedence and flattening spec (`MP0-T1`) | MP0-T1 | Docs review | MP0-T2 |
| 2026-03-01 | Verified Mermaid API surface lacks built-in flatten/effective-style export; documented as implementation constraint | MP0-T2 (clarification) | Local Mermaid API/code inspection | MP0-T2 |
| 2026-03-01 | Implemented style-input capture and parser utility for Mermaid flattening; added baseline tests and validated compile/test | MP0-T2, MP0-T3, MP0-T4 | `sharedJVM/testOnly ...MermaidStyleDeclarationsSpec ...ToViewerGraphSpec ...FromViewerGraphSpec`; `viewer/compile` | MP1-T1 |
| 2026-03-01 | Implemented Mermaid node effective-style resolver and toolbar mapping; integrated into attribute read path with tests | MP1-T1, MP1-T2, MP1-T3, MP1-T4 | `sharedJVM/testOnly ...AttributesOpsSpec ...MermaidStyleDeclarationsSpec ...ToViewerGraphSpec ...FromViewerGraphSpec`; `viewer/compile` | MP1-T5 |
| 2026-03-01 | Implemented edge/group read-path style parity (capture + resolver + tests), keeping explicit attrs authoritative | MP2-T1, MP2-T2, MP2-T3, MP2-T4, MP2-T5 | `sharedJVM/testOnly ...AttributesOpsSpec ...ToViewerGraphSpec ...MermaidStyleDeclarationsSpec ...FromViewerGraphSpec`; `viewer/compile` | MP1-T5 |
| 2026-03-01 | Completed manual UI verification for read parity using controlled Mermaid sample (classDef default/class/inline + linkStyle default/index) | MP1-T5 | DevTools/manual check on `localhost:5173` (node, edge, group toolbar values) | MP3-T1 |
| 2026-03-01 | Improved Mermaid 10 render parity with Mermaid Live for inline style precedence and marker color: inline node/cluster/edge styles are promoted to `!important`, edge markers are colorized from default linkStyle stroke, and inline node text styles are applied with label-box width compensation | MP1-T5 (hardening), MP2-T2 (hardening) | `viewer/testOnly org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackendNormalizationSpec`; `viewer/compile`; `viewer/fastLinkJS`; manual compare screenshots (`/tmp/ge-render-fixed2.png` vs `/tmp/official-render.png`) | MP3-T1 |
| 2026-03-01 | Upgraded Mermaid runtime from `10.9.5` to `11.12.0` and fixed parser compatibility (`parser.yy` can be empty in v11; fallback to `diagram.db`) so Mermaid read path works again; visual parity improved significantly for node/group padding/geometry | MP1-T5 (hardening), MP2-T2 (hardening) | `npm install mermaid@11.12.0`; `viewer/compile`; `viewer/testOnly org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackendNormalizationSpec`; `viewer/fastLinkJS`; `npm run build`; manual compare (`/tmp/ge-render-v11.png` vs `/tmp/official-render.png`) | MP3-T1 |
| 2026-03-01 | Defined and codified Mermaid flat export policy (`MP3-T1`): deterministic output order, merged classDef/classDefText emission, default linkStyle export, and stable edge ordering baseline for upcoming write-back tasks | MP3-T1 | `sharedJVM/testOnly org.jpablo.graphexplorer.viewer.backends.mermaid.FromViewerGraphSpec` | MP3-T2 |

## Open Questions

1. Should class order precedence follow Mermaid declaration order strictly for multi-class assignment?
2. Do we need a format-aware attribute adapter layer to avoid changing DOT behavior in `AttributesOps`?
3. What objective checks should define "visual fidelity acceptable" for flattened Mermaid export?
