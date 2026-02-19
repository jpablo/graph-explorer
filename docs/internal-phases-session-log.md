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
