# InternalPhases Refactor Progress

Last updated: 2026-02-19

Current track: Phase 0 (`tests first`)

Resume from: `PH0-T3`

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
| PH0-T3 | Latest parse failure sets fallback graph + editor error | todo | Phase test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:84` | local run 2026-02-19 | Unblocked; currently green in latest phase suite run |
| PH0-T4 | Format switch reparses current text on selected backend | todo | Phase test passes | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:109` | local run 2026-02-19 | Unblocked; currently green in latest phase suite run |
| PH0-T5 | Graph edits serialize according to current format (no parse scheduling expected at machine level) | todo | Machine and phase tests pass | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:92`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:135` |  | Verify both reducer and orchestrator behavior |
| PH0-T6 | Baseline regression sweep for InternalPhases suites | done | `InternalPhasesMachineSpec`, `InternalPhasesPhaseSpec`, `InternalPhasesSpec` green | `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala`, `viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesSpec.scala` | local run 2026-02-19 | Command: `sbt "viewer/testOnly org.jpablo.graphexplorer.viewer.state.InternalPhasesMachineSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesPhaseSpec org.jpablo.graphexplorer.viewer.state.InternalPhasesSpec"` |
| PH1-T1 | Introduce `MachineInput` envelope + pure `step` function | todo | New pure transition API + tests | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachine.scala` |  | See `docs/internal-phases-functional-refactor-plan.md` |
| PH1-T2 | Replace imperative orchestration with event bus + `scanLeft` state fold | todo | No mutable control register in `InternalPhases` | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala` |  | Keep external API compatible in Phase 1 |
| PH1-T3 | Centralize effect interpreter and parse feedback loop | todo | Effects executed from one interpreter boundary | `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala` |  | Preserve stale request semantics |

## Update Protocol (Session-Safe)

1. When starting work: set one task to `in_progress`.
2. When finishing a task: set status to `done`, add evidence and commit hash.
3. If blocked: set status to `blocked` and add concrete unblock condition.
4. At session end: update `Resume from` and append one line to `docs/internal-phases-session-log.md`.
