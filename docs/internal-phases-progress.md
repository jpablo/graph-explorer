# InternalPhases Refactor Progress

Last updated: 2026-02-19

Current track: Phase 2 (`optional hardening`) complete

Resume from: `No pending InternalPhases refactor tasks (track complete as of PH2-T7)`

## Status Legend

- `todo`: not started
- `in_progress`: actively being worked
- `blocked`: cannot continue until blocker resolved
- `done`: exit criteria met, with evidence

## Task Board

| ID | Task | Status | Exit Criteria | Evidence | Commit / Ref | Notes |
|---|---|---|---|---|---|---|
| PH0-T1 | Machine-level stale parse completion is ignored | done | `InternalPhasesMachineSpec` stale parse test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:34` | local run 2026-02-19 | Covered in pure reducer tests |
| PH0-T2 | Orchestrator-level stale parse completion does not overwrite latest graph / error | done | `InternalPhasesPhaseSpec` stale parse test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:52` | local run 2026-02-19 | Fixed by using transition snapshot directly in parse completion path (`InternalPhases.scala`) |
| PH0-T3 | Latest parse failure sets fallback graph + editor error | done | Phase test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:84` | local run 2026-02-19 | Verified green in three-suite baseline run |
| PH0-T4 | Format switch reparses current text on selected backend | done | Phase test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:109` | local run 2026-02-19 | Verified green in three-suite baseline run |
| PH0-T5 | Graph edits serialize according to current format (no parse scheduling expected at machine level) | done | Machine and phase tests pass | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:148`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:135` | local run 2026-02-19 | Verified machine + orchestrator serializer behavior in baseline run |
| PH0-T6 | Baseline regression sweep for InternalPhases suites | done | `InternalPhasesMachineSpec`, `InternalPhasesPhaseSpec`, `InternalPhasesSpec` green | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesSpec.scala` | local run 2026-02-19 | Command: `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"` |
| PH1-T1 | Introduce `MachineInput` envelope + pure `step` function | done | New pure transition API + tests | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachine.scala:37`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:68`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:90` | local run 2026-02-19 | Added `MachineInput` and total `step` with idle parse no-op semantics |
| PH1-T2 | Replace imperative orchestration with event bus + `scanLeft` state fold | done | No mutable control register in `InternalPhases` | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:68` | local run 2026-02-19 | `inputBus.events.scanLeft(initialTransition)` now drives machine transitions |
| PH1-T3 | Centralize effect interpreter and parse feedback loop | done | Effects executed from one interpreter boundary | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:131`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:127` | local run 2026-02-19 | `transitions.changes.foreach(runEffects)` is the single effect interpreter; parse completions feed back via `MachineInput.Parse` |
| PH2-T1 | Remove duplicate UI transition pre-computation (fold is only transition path) | done | UI writes dispatch to input bus without local preview reduce | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:93` | local run 2026-02-19 | `dispatchUiEvent` now only enqueues `MachineInput.Ui` and returns current snapshot |
| PH2-T2 | Add explicit read/write ports while preserving existing `Var` API | done | `Signal` + `Observer` ports exposed for text/graph with compatibility retained | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:141`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:144`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:153`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:156` | local run 2026-02-19 | Added `sourceTextS`/`sourceTextWriter` and `fullGraphS`/`fullGraphWriter`; existing `sourceText`/`fullGraphV` now delegate through writers |
| PH2-T3 | Align architecture docs with fold-driven orchestrator | done | `internal-phases-dataflow.md` and refactor plan reflect current runtime design | `docs/internal-phases-dataflow.md`, `docs/internal-phases-functional-refactor-plan.md` | local update 2026-02-19 | Replaced outdated `machineState/applyUiEvent` flow description with `inputBus + scanLeft + transitions.changes` model |
| PH2-T4 | Add integration guard against duplicate parse scheduling per source edit | done | Phase test proves one source edit yields one parse request through fold path | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:86` | local run 2026-02-19 | New test: `single source edit schedules one parse request through fold` |
| PH2-T5 | Migrate direct internal write callsites to explicit writer/update ports | done | UI text writes use `sourceTextWriter` and direct graph updates use explicit `updateFullGraph` API | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/codeMirror/CodeMirror.scala:43`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/Toolbar.scala:221`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala:159`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala:174`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/DiagramSelectionOps.scala:133`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/VisibilityOps.scala:71` | local run 2026-02-19 | Complex lens-based callsites (`zoomLazy`/`zoomLens`) intentionally kept on compatibility var for now |
| PH2-T6 | Broader closeout validation (`sbt test`, `npm run build`) | done | Full repo tests/build green after functional-boundary migration | `build.sbt`, `package.json` | local run 2026-02-19 | `sbt test` and `npm run build` both pass; re-validated after PH2-T7 on commit `126ef9eb` |
| PH2-T7 | Migrate remaining compatibility var writes off `zoomLens` onto explicit graph update port | done | `graphType`, `diagramAttributesUpdates`, and `elementAttributesUpdates` writes dispatch through `updateFullGraph` | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala:238`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala:256`, `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala:261` | local run 2026-02-19 | Compatibility var API retained; removed `zoomLens` usage in `ViewerState` while preserving callsites |

## Update Protocol (Session-Safe)

1. When starting work: set one task to `in_progress`.
2. When finishing a task: set status to `done`, add evidence and commit hash.
3. If blocked: set status to `blocked` and add concrete unblock condition.
4. At session end: update `Resume from` and append one line to `docs/internal-phases-session-log.md`.
