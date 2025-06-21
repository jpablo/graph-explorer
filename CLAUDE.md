# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Graph Explorer is a web-based visual graph exploration tool for DOT (Graphviz) diagrams built with Scala.js, Laminar, and modern web technologies. The project uses a modular architecture with shared cross-compiled code and a frontend viewer
module.

## Build Commands

### Development Setup (requires two terminals)

```bash
# Terminal 1: Scala.js compilation with hot reload
sbt "~viewer/fastLinkJS"

# Terminal 2: Development server
npm run dev
```

### First-time Setup

```bash
npm install
```

### Production Build

```bash
sbt "viewer/fullLinkJS"
npm run build
```

### Testing

```bash
sbt test
```

## Architecture

The codebase is organized into three main modules:

- **Root** (`/`) - Aggregates submodules and build configuration
- **Shared** (`/shared/`) - Cross-compiled (JVM/JS) core logic including graph models, DOT parsing, and utilities
- **Viewer** (`/viewer/`) - Scala.js frontend with UI components, state management, and canvas interactions

### Key Components

- **ViewerGraph** (`shared/`) - Core graph data structure with nodes, arrows, and groups
- **DOT Parser** (`shared/`) - Parses DOT format into internal AST representation
- **ViewerState** (`viewer/`) - Central application state using Laminar reactive streams
- **SvgCanvas** (`viewer/`) - Interactive canvas component for graph visualization
- **AttributesToolbar** (`viewer/`) - Dynamic attribute editing interface

### State Management Pattern

The application uses **Laminar's reactive streams** with **QuickLens** for immutable state updates. State flows unidirectionally from ViewerState through components via Signals and EventStreams.

## Technology Stack

- **Scala 3.7.1** with strict compiler flags (`-Xfatal-warnings`)
- **Scala.js 1.19.0** compiling to ES modules
- **Laminar 17.2.1** for reactive UI
- **Vite 6.2.6** for build tooling and dev server
- **Tailwind CSS 4.0.14** + **DaisyUI 5.0.22** for styling
- **MUnit** + **ScalaCheck** for testing

## Code Conventions

- **Functional programming** with immutable data structures
- **Lens-based updates** using QuickLens for state modifications
- **VectorMap** for ordered collections requiring both fast lookup and iteration order
- **Strict type safety** with Scala 3's strict equality and comprehensive compiler warnings
- **ES Module output** format for modern JavaScript interop

## Development Notes

- The project requires **Node.js** for frontend tooling and **sbt** for Scala compilation
- **Source maps** are enabled for debugging compiled Scala.js code
- **Hot reloading** works for both Scala.js changes (via sbt) and CSS/asset changes (via Vite)
- The application persists state to **localStorage** and integrates with browser **Clipboard API**
- **Canvas and SVG** are used extensively for graph rendering and interactions
- Try to use the metals mcp instead of sbt commands if possible