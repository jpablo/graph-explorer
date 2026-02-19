# InternalPhases Data Flow

```mermaid
flowchart TD
    %% External inputs
    subgraph INPUTS["External inputs"]
        direction LR
        CM["CodeMirror text edits"]
        GUI["Canvas graph edits"]
        FMT["Format selector"]
    end

    %% Public Vars
    ST["sourceText: Var[String]"]
    FGV["fullGraphV: Var[ViewerGraph]"]

    %% Orchestrator internals
    AUI["applyUiEvent"]
    AP["applyParseEvent"]
    EFX["runEffects"]
    BUS["textChangeBus: EventBus[InFlightRequest]"]
    RES["resolveBackend(format).textToGraph(text)"]

    %% Reducer internals
    MS["machineState: State ADT"]
    RUI["reduce(state, UiEvent, serializeGraph)"]
    RPARSE["reduce(inFlightState, ParseEvent)"]
    FX["Effect: StartParse or SetEditorError"]

    %% Observable app state
    STATE["state: Var[GraphState]"]
    EE["editorError: Var[Option[String]]"]

    %% Derived signals
    HN["hiddenNodes: Signal[HiddenElements]"]
    SG["simpleGraph: Signal[SimpleGraph]"]
    VG["visibleGraph: Signal[ViewerGraph]"]
    VDOT["visibleDOT: Signal[DotText]"]
    CF["currentFormat: Signal[DiagramFormat]"]
    SS["selectionStrategy: Signal[SelectableElementStrategy]"]

    %% UI entry points
    CM --> ST
    GUI -->|"1"| FGV
    FMT --> AUI

    %% sourceText / fullGraphV setter paths
    ST --> AUI
    FGV -->|"2"| AUI
    AUI -->|"3"| RUI
    MS --> RUI
    RUI -->|"4"| MS
    RUI --> FX

    %% effect execution
    FX --> EFX
    EFX -->|"StartParse(request)"| BUS
    EFX -->|"SetEditorError(...)"| EE

    %% async parse path
    BUS --> RES
    RES --> AP
    AP --> RPARSE
    MS --> RPARSE
    RPARSE --> MS
    RPARSE --> FX

    %% machine snapshot to exposed var
    MS -->|"5"| STATE
    STATE -.->|"6"| ST
    STATE -.->|"7"| FGV

    %% derived signals
    STATE -.-> SG
    FGV -.-> VG
    HN -.-> VG
    VG -.-> VDOT
    STATE -.-> CF
    CF -.-> SS

    %% Emphasize Canvas graph edits trace (steps 1..7)
    linkStyle 1,4,5,7,18,19,20 stroke-width:3px

    classDef core fill:#4a90d9,color:#fff,stroke:#2a70b9
    classDef public fill:#7bc67e,color:#fff,stroke:#5aa65d
    classDef reducer fill:#d9a84a,color:#fff,stroke:#b9882a
    classDef effects fill:#e07e39,color:#fff,stroke:#c05e19
    classDef derived fill:#b8b8b8,color:#fff,stroke:#989898
    classDef external fill:#d97ab5,color:#fff,stroke:#b95a95

    class STATE,MS core
    class ST,FGV public
    class RUI,RPARSE,AUI,AP reducer
    class FX,EFX,BUS,RES,EE effects
    class SG,VG,VDOT,CF,SS,HN derived
    class CM,GUI,FMT external
```

## Canvas Edit Trace (1..7)

| # | Interaction | Description | Source |
|---|---|---|---|
| 1 | `Canvas graph edits -> fullGraphV` | Canvas mouse interactions call `viewerOps` handlers, and graph-changing handlers write through `phases.fullGraphV.update(...)`. | [SvgCanvas.scala#L119](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/components/svgCanvas/SvgCanvas.scala#L119), [ViewerState.scala#L218](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/ViewerState.scala#L218) |
| 2 | `fullGraphV -> applyUiEvent` | The `fullGraphV` setter converts the update into `UiEvent.GraphEdited` and routes it through `applyUiEvent`. | [InternalPhases.scala#L171](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L171), [InternalPhases.scala#L179](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L179) |
| 3 | `applyUiEvent -> reduce(state, UiEvent, serializeGraph)` | `applyUiEvent` delegates to the machine reducer. | [InternalPhases.scala#L71](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L71), [InternalPhases.scala#L73](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L73) |
| 4 | `reduce(...) -> machineState` | In the `GraphEdited` branch, the reducer serializes the graph and returns a new state, then `machineState` is replaced with `transition.state`. | [InternalPhasesMachine.scala#L79](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachine.scala#L79), [InternalPhases.scala#L78](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L78) |
| 5 | `machineState -> state` | The `fullGraphV` setter returns `transition.state.snapshot` via `state.zoomLazy(...)`, which updates the public `state` var. | [InternalPhases.scala#L66](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L66), [InternalPhases.scala#L171](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L171), [InternalPhases.scala#L181](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L181) |
| 6 | `state -.-> sourceText` | `sourceText` reads projected text from `state` (`currentState.text`). | [InternalPhases.scala#L150](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L150), [InternalPhases.scala#L155](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L155) |
| 7 | `state -.-> fullGraphV` | `fullGraphV` reads projected graph from `state` (`currentState.viewerGraph`). | [InternalPhases.scala#L171](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L171), [InternalPhases.scala#L175](../viewer/src/main/scala/org/jpablo/graphexplorer/viewer/state/InternalPhases.scala#L175) |

## Legend

| Color | Meaning |
|-------|---------|
| Blue | Core state (`machineState`, `state`) |
| Green | Public write/read Vars (`sourceText`, `fullGraphV`) |
| Gold | Reducer and event application |
| Orange | Effect execution and async parse pipeline |
| Gray | Derived signals |
| Pink | External inputs/signals |

Arrow style: solid arrows are event/write/effect flow; dotted arrows are derived/read dependencies.

## Write Paths

1. **Text edit**: `sourceText.set(newText)` emits `UiEvent.SourceEdited`, reducer updates snapshot, emits `StartParse`, and parser completion is reduced as `ParseSucceeded` or `ParseFailed`.
2. **Format change**: `formatSelection` emits `UiEvent.FormatChanged`, reducer emits `StartParse` for current text in new format.
3. **Graph edit**: `fullGraphV.set(newGraph)` emits `UiEvent.GraphEdited`, reducer serializes graph to text and updates snapshot synchronously.
4. **Editor errors**: only set through `Effect.SetEditorError(...)` in `runEffects`.

## Read Paths

- `sourceText` reads `state.text` (which mirrors `machineState.snapshot.text`)
- `fullGraphV` reads `state.viewerGraph`
- `simpleGraph` derives from `state.text` via `graphviz.textToSimpleGraph`
- `visibleGraph` combines `fullGraphV` with `hiddenNodes`
- `visibleDOT` serializes `visibleGraph` for rendering
- `currentFormat` and `selectionStrategy` derive from `state.format`

## Interaction Diagram

```mermaid
sequenceDiagram
    participant UI as UI
    participant IP as InternalPhases
    participant M as InternalPhasesMachine
    participant Q as textChangeBus handler
    participant B as DiagramBackend
    participant EE as editorError Var
    participant CV as SvgCanvas UI

    alt Text edit path
        UI->>IP: sourceText.set newText
        IP->>M: reduce UiEvent.SourceEdited
        M-->>IP: Transition + Effect StartParse request1
        IP->>Q: enqueue request1
        Q->>B: textToGraph request1

        UI->>IP: sourceText.set newerText
        IP->>M: reduce UiEvent.SourceEdited
        M-->>IP: Transition + Effect StartParse request2
        IP->>Q: enqueue request2
        Q->>B: textToGraph request2

        B-->>Q: future completed for request1
        Q-->>IP: parse callback request1
        IP->>M: reduce ParseEvent.ParseSucceeded or ParseFailed request1
        M-->>IP: Transition no-op stale

        B-->>Q: future completed for request2
        Q-->>IP: parse callback request2
        IP->>M: reduce ParseEvent.ParseSucceeded or ParseFailed request2
        M-->>IP: Transition + optional SetEditorError effect
        IP->>EE: apply SetEditorError if emitted
        IP->>IP: state.set machineState.snapshot
        IP-->>CV: state/fullGraph/currentFormat signals emit
        CV->>CV: recompute visibleGraph visibleDOT svgWithPositions finalSVG
        CV->>CV: mount or update rendered SVG
    end

    alt Graph edit path from canvas UI
        UI->>IP: fullGraphV.set newGraph
        IP->>M: reduce UiEvent.GraphEdited
        M-->>IP: Transition no parse effect
        IP->>IP: state.set machineState.snapshot
        IP-->>CV: state/fullGraph/currentFormat signals emit
        CV->>CV: recompute visibleGraph visibleDOT svgWithPositions finalSVG
        CV->>CV: mount or update rendered SVG
    end

    alt Format change path from UI
        UI->>IP: formatSelection.set Mermaid
        IP->>M: reduce UiEvent.FormatChanged
        M-->>IP: Transition + Effect StartParse requestN
        IP->>Q: enqueue requestN
        Q->>B: textToGraph requestN
        B-->>Q: future completed for requestN
        Q-->>IP: parse callback requestN
        IP->>M: reduce ParseEvent.ParseSucceeded or ParseFailed requestN
        M-->>IP: Transition + optional SetEditorError effect
        IP->>EE: apply SetEditorError if emitted
        IP->>IP: state.set machineState.snapshot
        IP-->>CV: state/fullGraph/currentFormat signals emit
        CV->>CV: recompute visibleGraph visibleDOT svgWithPositions finalSVG
        CV->>CV: mount or update rendered SVG
    end
```
