# InternalPhases Functional Refactor Plan

## Implementation Status (2026-02-19)

- Phase 0 safety-net tasks: complete (machine + phase + baseline InternalPhases suites green).
- Phase 1 core refactor: complete.
  - `MachineInput` + pure `step(...)` added in `InternalPhasesMachine`.
  - `InternalPhases` orchestration now uses `inputBus.events.scanLeft(initialTransition)`.
  - effects execute from one interpreter boundary (`transitions.changes.foreach(runEffects)`).
  - parse feedback re-enters via `MachineInput.Parse`.
- Phase 2 optional hardening: in progress.
  - explicit read/write ports added (`sourceTextS` / `sourceTextWriter`, `fullGraphS` / `fullGraphWriter`).
  - direct internal write callsites migrated to explicit writer/update ports (`sourceTextWriter`, `updateFullGraph`).
  - compatibility `Var` APIs retained (`sourceText`, `fullGraphV`).
  - closeout validation completed: `sbt test` and `npm run build` are green.

## Problem (Original)

`InternalPhases` previously mixed:

1. Pure machine transitions (`InternalPhasesMachine.reduce`)
2. Mutable orchestration state (`machineState`)
3. Side effects (parse calls, editor error writes)

This makes control flow harder to reason about, and makes "state machine correctness" testing noisier because orchestration and effects are intertwined.

## Goals

1. Keep transition logic pure and explicit.
2. Drive machine evolution from a single event stream.
3. Isolate side effects in one interpreter boundary.
4. Preserve behavior (stale parse ignore, request ordering, error semantics).
5. Stay compatible with the Airstream API available at commit `99ce085caf75dcbcf4425dbdddab1f88f4461609`.

## Airstream Compatibility (Pinned Commit)

The plan relies only on APIs present at commit `99ce085caf75dcbcf4425dbdddab1f88f4461609`:

- `EventBus` (`events`, `writer`)
- `EventStream.scanLeft` / `scanLeftRecover`
- `flatMapSwitch`
- `withCurrentValueOf` / `sample`

## Target Architecture

### 1) Single Input Event Type

Add one envelope for all machine-driving inputs:

```scala
enum MachineInput:
  case Ui(event: InternalPhasesMachine.UiEvent)
  case Parse(event: InternalPhasesMachine.ParseEvent)
```

### 2) Pure Transition Step

Make one total transition function:

```scala
def step(
  state: InternalPhasesMachine.State,
  input: MachineInput
): InternalPhasesMachine.Transition[InternalPhasesMachine.State]
```

Behavior:

- `MachineInput.Ui` delegates to `reduce(state, uiEvent, serializeGraph)`
- `MachineInput.Parse` delegates to parse reducer only when state is `InFlight`; otherwise no-op transition

No side effects inside `step`.

### 3) State Built from Stream Fold

Create `inputBus: EventBus[MachineInput]`.

Build machine state with `scanLeft`:

```scala
transitionsS: Signal[Transition[State]] = inputBus.events.scanLeft(initialTransition) { (prev, input) =>
  step(prev.state, input)
}

machineStateS: Signal[State] = transitionsS.map(_.state)
snapshotS: Signal[GraphState] = machineStateS.map(_.snapshot).distinct
```

This removes mutable `machineState` as a control variable.

### 4) Effect Interpreter Boundary

Interpret effects outside the reducer:

- `StartParse(request)` -> call backend async, map result to `MachineInput.Parse(...)`, emit back to `inputBus.writer`
- `SetEditorError(error)` -> `editorError.set(error)` (or move into state in Phase 2)

This keeps side effects centralized and testable separately from transition logic.

### 5) Public State Projection

Use `snapshotS` as source of truth for read-side projections:

- `sourceText` read projection
- `fullGraphV` read projection
- `currentFormat`, `selectionStrategy`, `visibleGraph`, `visibleDOT`, etc.

## Migration Strategy

### Phase 0: Safety Net

1. Add/expand tests around:
- stale parse completions are ignored
- current parse success/failure updates snapshot correctly
- `GraphEdited` never schedules parse
- format changes schedule parse with current text

2. Keep docs diagrams as baseline references.

### Phase 1: Pure Core + Compatibility Adapter

1. Introduce `MachineInput` and `step(state, input)`.
2. Introduce `inputBus` + `scanLeft` state fold.
3. Move parse callback path to emit parse inputs into `inputBus` (no direct reducer calls).
4. Move `runEffects` into an explicit interpreter that subscribes to transition changes.
5. Keep existing public fields (`sourceText`, `fullGraphV`, `formatSelection`) using adapter logic so external call sites do not need immediate rewrites.

Expected outcome:

- Machine control flow is stream-driven and deterministic.
- Public API remains mostly unchanged.

### Phase 2 (Optional): Stronger Functional Boundary

Replace writable `Var` API exposure with explicit ports:

- read: `Signal`s
- write: `Observer`s / command methods

Example:

- `sourceTextS: Signal[String]`
- `sourceTextWriter: Observer[String]`

This removes hidden write semantics from `zoomLazy` lenses and makes updates always go through `MachineInput`.

## Invariants to Preserve

1. At most one active parse request in machine state (`State.InFlight` carries one request).
2. Parse completion only affects state when request is current (`id`, `text`, `selectedFormat` checks).
3. `GraphEdited` path updates text via serialization and does not start parse.
4. Editor error changes only through machine effects.
5. Snapshot remains the only source for exposed text/graph read projections.

## Risks and Mitigations

1. Transaction ordering surprises while moving from imperative calls to event loop.
- Mitigation: assert event ordering in tests; avoid hidden writes in observers.

2. Compatibility adapter for `Var` can introduce feedback loops.
- Mitigation: keep one-way write ingress through `MachineInput`; use `distinct` and explicit origin checks.

3. Duplicate effect execution during migration.
- Mitigation: ensure only the interpreter executes effects; remove old direct `runEffects(...)` call sites once each path is migrated.

## Verification Plan

1. Unit tests for `InternalPhasesMachine.step` cases (pure, no async).
2. Integration tests for `InternalPhases` event loop:
- overlapping parse requests
- stale parse completion
- parse failure sets fallback graph and error
- graph edit writes serialized text
3. Compile and existing viewer tests.
4. Manual smoke:
- rapid typing
- format toggling during parse
- canvas graph edits

## Definition of Done

1. No mutable machine control reference in `InternalPhases` (state comes from fold over events).
2. Reducer remains pure.
3. Effects executed only in one interpreter boundary.
4. Existing behavior and tests preserved.
5. Docs (`internal-phases-dataflow.md` and state-machine doc) updated to match final architecture.
