# Mermaid Diagram Support

Graph Explorer now supports [Mermaid](https://mermaid.js.org/) flowchart diagrams in addition to DOT/Graphviz diagrams.

## Quick Start

- Pick the diagram format from the dropdown beside "Documentation" (MermaidJS or Graphviz/DOT). The selection drives parsing—no auto-detection while you type.
- Paste or type your diagram source; the editor placeholder updates to match the chosen format.
- When loading a saved/shared diagram, the app preselects a format using simple heuristics (below). Switch manually if it guesses wrong.

## Initial Format Heuristics

We still infer an initial format on load using these patterns:

| Pattern | Format |
|---------|--------|
| `flowchart ...` | Mermaid |
| `graph TD`, `graph TB`, `graph BT`, `graph LR`, `graph RL` | Mermaid |
| `sequenceDiagram`, `classDiagram`, `stateDiagram` | Mermaid |
| `digraph ...`, `graph ...` (without direction) | DOT |
| `strict digraph ...`, `strict graph ...` | DOT |

## Supported Mermaid Features

### Flowcharts

```mermaid
flowchart TD
    A[Rectangle] --> B(Rounded)
    B --> C{Diamond}
    C -->|Option 1| D[[Subroutine]]
    C -->|Option 2| E[(Database)]
    E --> F((Circle))
```

### Subgraphs (Groups)

```mermaid
flowchart TB
    subgraph Frontend
        A[React App]
        B[Vue App]
    end
    subgraph Backend
        C[API Server]
        D[Database]
    end
    A --> C
    B --> C
    C --> D
```

### Edge Styles

```mermaid
graph LR
    A --> B
    A -.-> C
    A ==> D
    B --text--> E
    C -.text.-> F
```

- `-->` Solid arrow
- `-.->` Dotted arrow
- `==>` Thick arrow
- `--text-->` Arrow with label

### Node Shapes

| Mermaid | Syntax | DOT Equivalent |
|---------|--------|----------------|
| Rectangle | `[text]` | box |
| Rounded | `(text)` | box (rounded) |
| Stadium | `([text])` | box |
| Diamond | `{text}` | diamond |
| Circle | `((text))` | circle |
| Database | `[(text)]` | cylinder |
| Hexagon | `{{text}}` | hexagon |
| Parallelogram | `[/text/]` | parallelogram |

## Architecture Overview

The viewer has three kinds of parts for each language (ADR 0002). An extractor makes a graph from a source. A printer writes a graph as text. A renderer draws text.

```
              ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
              │ Extractor        │   │ Printer          │   │ Renderer         │
              │ (trait)          │   │ (trait)          │   │ (trait)          │
              │ - textToGraph()  │   │ - graphToText()  │   │ - textToSvg()    │
              └──────────────────┘   └──────────────────┘   └──────────────────┘
DOT           DotExtractor           DotPrinter             GraphvizRenderer (synchronous)
Mermaid       MermaidExtractor       MermaidPrinter         MermaidRenderer (asynchronous)
```

`DiagramLanguages` is the registry. It gives each part by `DiagramFormat` (DOT or Mermaid).

### Key Components

| File | Description |
|------|-------------|
| `DiagramFormat.scala` | Enum for supported formats with auto-detection logic |
| `Extractor.scala`, `Printer.scala`, `Renderer.scala` | The traits of the three parts |
| `DiagramLanguages.scala` | The registry of the parts |
| `MermaidExtractor.scala` | Reads a Mermaid source into a graph, with the Mermaid.js parser |
| `MermaidPrinter.scala` | Writes a graph as Mermaid text |
| `MermaidRenderer.scala` | Draws Mermaid text, with the Mermaid.js library |
| `MermaidBackend.scala` | What the extractor and the renderer share: the start of Mermaid.js, the queue of its operations, and the changes to its SVG |
| `MermaidJS.scala` | Scala.js facade for Mermaid.js |
| `MermaidGraph.scala` | Data models for parsed Mermaid diagrams |
| `ToViewerGraph.scala` | Converts MermaidGraph to ViewerGraph |
| `SelectableElementStrategy.scala` | Handles SVG element selection per format |

### Data Flow

```
                    ┌──────────────────────────────────────────────────┐
                    │               InternalPhases                      │
                    │                                                   │
User Input ───────► │  sourceText ──► format detection                 │
(Mermaid text)      │       │                 │                        │
                    │       │         ┌───────┴───────┐                │
                    │       │         ▼               ▼                │
                    │       │     DOT (sync)    Mermaid (async)        │
                    │       │         │               │                │
                    │       │         ▼               ▼                │
                    │       └──► ViewerGraph ◄────────┘                │
                    │              │                                   │
                    └──────────────│───────────────────────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────────────────────────┐
                    │               ViewerState                         │
                    │                                                   │
                    │  svgWithPositions ──► finalSVG ──► Canvas        │
                    │                                                   │
                    └──────────────────────────────────────────────────┘
```

### Async vs Sync Processing

- **DOT/Graphviz**: Synchronous parsing via WebAssembly (viz.js)
- **Mermaid**: Asynchronous parsing via JavaScript Promises

The `InternalPhases` class handles this difference by:
1. Parsing DOT synchronously in the signal setter
2. Triggering async parsing for Mermaid via an EventBus
3. Updating state when the async result arrives

## Limitations

Current Mermaid support has some limitations compared to DOT:

1. **Read-only**: Mermaid diagrams cannot be edited graphically (no drag-and-drop node creation or edge manipulation)
2. **No hidden elements**: The visibility toggle feature is not yet implemented for Mermaid
3. **Limited selection**: Edge selection may not work reliably due to Mermaid's SVG structure
4. **Flowcharts and state diagrams only**: Only flowchart/graph diagrams and `stateDiagram-v2` state diagrams are fully supported. Other kinds (sequence diagrams, the older `stateDiagram` header, etc.) render, but they are view-only. A canvas edit on a state diagram writes `stateDiagram-v2` text, and it does not keep notes or concurrent regions (`--`).

## Example: Software Architecture

```mermaid
flowchart TB
    subgraph Client
        UI[Web UI]
        Mobile[Mobile App]
    end

    subgraph Services
        API[API Gateway]
        Auth[Auth Service]
        Users[User Service]
        Orders[Order Service]
    end

    subgraph Data
        DB[(PostgreSQL)]
        Cache[(Redis)]
        Queue[Message Queue]
    end

    UI --> API
    Mobile --> API
    API --> Auth
    API --> Users
    API --> Orders
    Auth --> Cache
    Users --> DB
    Orders --> DB
    Orders --> Queue
```

## Adding Mermaid Examples to the Library

To add Mermaid examples to the examples section on the library page, add entries to `DotExamples.scala` with Mermaid syntax. The format will be auto-detected and rendered correctly.

## Dependencies

Mermaid support requires the `mermaid` npm package:

```bash
npm install mermaid
```

This is already included in the project's `package.json`.
