# InternalPhases Session Log

Append-only log for cross-session continuity.

## 2026-02-19

Summary:

- Added persistent planning doc: `docs/internal-phases-functional-refactor-plan.md`.
- Added progress tracker and matrix docs.
- Ran targeted internal phases tests to establish a baseline.

Evidence:

- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` failed at `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:80` (`nodes.size` expected `1`, obtained `0`).
  - `InternalPhasesSpec` not reached because the previous suite failed.

Next action:

- Investigate and fix stale-parse orchestration behavior behind `PH0-T2`.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH0-T2`

## 2026-02-19 (PH0-T2 fix)

Summary:

- Fixed PH0-T2 stale-parse orchestration issue in `InternalPhases` parse-completion path.
- Kept changes minimal and targeted to reducer-orchestrator snapshot handoff.
- Re-ran the requested InternalPhases suite baseline.

Evidence:

- Code change:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala`
  - Parse completion now uses `transition.state.snapshot` directly instead of reading `machineState.now()` immediately after `machineState.set(...)`.
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH0-T3`

## 2026-02-19 (PH1 event-fold refactor)

Summary:

- Completed PH0 remaining verification tasks (`PH0-T3`, `PH0-T4`, `PH0-T5`) from the green baseline run.
- Implemented PH1 functional refactor core:
  - Added `MachineInput` envelope and pure total `step` transition in `InternalPhasesMachine`.
  - Replaced imperative orchestration updates in `InternalPhases` with `inputBus.events.scanLeft(initialTransition)`.
  - Centralized effect execution under transition stream handling and fed parse completions back as `MachineInput.Parse`.

Evidence:

- Code changes:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachine.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala`
  - `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH2 (optional)`

## 2026-02-19 (PH2 optional: explicit ports compatibility)

Summary:

- Added explicit read/write ports in `InternalPhases`:
  - `sourceTextS` / `sourceTextWriter`
  - `fullGraphS` / `fullGraphWriter`
- Kept existing `sourceText` and `fullGraphV` `Var` APIs for compatibility, but routed their writes through the new observers.
- Re-ran InternalPhases suites to validate behavior did not regress.

Evidence:

- Code change:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH2 (optional)`

## 2026-02-19 (PH2 optional: docs alignment + duplicate-parse guard)

Summary:

- Updated architecture docs to match the current fold-driven runtime model (`inputBus + scanLeft + transitions.changes`).
- Added a new phase-level integration guard that asserts one source edit schedules exactly one parse request.
- Re-ran InternalPhases machine/phase/baseline suites after the test and doc updates.

Evidence:

- Code/docs changes:
  - `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala`
  - `docs/internal-phases-dataflow.md`
  - `docs/internal-phases-functional-refactor-plan.md`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed (including `single source edit schedules one parse request through fold`).
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH2 (optional)`

## 2026-02-19 (PH2 optional: fold-only UI dispatch)

Summary:

- Removed duplicate transition pre-computation from the UI dispatch path in `InternalPhases`.
- UI writes now enqueue `MachineInput.Ui` directly, keeping transition evaluation exclusively in the `scanLeft` fold.
- Re-ran the InternalPhases machine/phase/baseline suites to verify no behavior change.

Evidence:

- Code change:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala`
  - `dispatchUiEvent` now emits input to `inputBus` and returns the current snapshot; it no longer calls `InternalPhasesMachine.step` directly.
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH2 (optional)`

## 2026-02-19 (PH2 optional: direct write-callsite migration)

Summary:

- Migrated direct text writes in UI components to `sourceTextWriter`.
- Introduced explicit graph write API in `InternalPhases` (`setFullGraph`, `updateFullGraph`) and migrated direct `fullGraphV.update(...)` callsites in state ops.
- Kept compatibility `fullGraphV` lens-based callsites (`zoomLazy` / `zoomLens`) unchanged for now.

Evidence:

- Code changes:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/codeMirror/CodeMirror.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/Toolbar.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/DiagramSelectionOps.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/VisibilityOps.scala`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: PH2-T6`

## 2026-02-19 (PH2-T6 closeout validation)

Summary:

- Completed closeout validation by running full test and production build commands.
- Resolved a stale mermaid unit-test expectation that blocked `sbt test` (`flowchart TD` -> `flowchart TB`).
- Confirmed InternalPhases refactor checkpoints remain green after the migration and validation sweep.

Evidence:

- Code change:
  - `shared/src/test/scala/org/jpablo/graphexplorer/viewer/backends/mermaid/FromViewerGraphSpec.scala`
- Commands:
  - `sbt test`
  - `npm run build`
- Result:
  - `sbt test` passed.
  - `npm run build` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: Optional follow-up: migrate lens-based compatibility writes`

## 2026-02-19 (PH2-T7 optional follow-up: compatibility write-path migration)

Summary:

- Migrated remaining `ViewerState` compatibility var writes off `zoomLens` and onto explicit `updateFullGraph` ports.
- Kept public `Var` APIs (`graphType`, `diagramAttributesUpdates`, `elementAttributesUpdates`) intact for callsite compatibility.
- Re-ran InternalPhases baseline suites plus `ViewerStateSpec` to verify no behavior regressions.

Evidence:

- Code change:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec org.jpablo.graphexplorer.viewer.state.ViewerStateSpec"`
- Result:
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.
  - `ViewerStateSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: No pending InternalPhases refactor tasks (track complete as of PH2-T7)`

## 2026-02-19 (PH2-T7 cleanup: remove remaining AttributesOps lens helpers)

Summary:

- Removed `AttributesOps.diagramAttributesUpdates` and `AttributesOps.elementAttributesUpdates`.
- Inlined equivalent get/update functions at `ViewerState` callsites while keeping explicit `updateFullGraph` write boundary.
- Updated `AttributesOpsSpec` to use `ViewerGraph.updateAttributes` directly instead of the removed helper.

Evidence:

- Code changes:
  - `shared/src/main/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOps.scala`
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala`
  - `shared/src/test/scala/org/jpablo/graphexplorer/viewer/graph/AttributesOpsSpec.scala`
- Command:
  - `sbt "sharedJVM/testOnly org.jpablo.graphexplorer.viewer.graph.AttributesOpsSpec" "viewer/testOnly org.jpablo.graphexplorer.viewer.state.ViewerStateSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `AttributesOpsSpec` passed.
  - `ViewerStateSpec` passed.
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: No pending InternalPhases refactor tasks (track complete as of PH2-T7)`

## 2026-02-19 (PH2-T7 readability follow-up)

Summary:

- Extracted `zoomViaFullGraphUpdate` helper in `ViewerState` to reduce duplication in compatibility vars.
- Kept write semantics unchanged: all compatibility writes still route through `phases.updateFullGraph`.

Evidence:

- Code change:
  - `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala`
- Command:
  - `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.ViewerStateSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"`
- Result:
  - `ViewerStateSpec` passed.
  - `InternalPhasesMachineSpec` passed.
  - `InternalPhasesPhaseSpec` passed.
  - `InternalPhasesSpec` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: No pending InternalPhases refactor tasks (track complete as of PH2-T7)`

## 2026-02-19 (PH2-T7 post-merge closeout revalidation)

Summary:

- Re-ran full closeout verification on top of commit `126ef9eb`.
- Confirmed repository-wide tests and production build remain green after compatibility write-path migration.

Evidence:

- Commands:
  - `sbt test`
  - `npm run build`
- Result:
  - `sbt test` passed.
  - `npm run build` passed.

Resume pointer:

- `docs/internal-phases-progress.md` -> `Resume from: No pending InternalPhases refactor tasks (track complete as of PH2-T7)`
