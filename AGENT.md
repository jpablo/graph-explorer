# AGENT.md

## Build/Test Commands
- `sbt --client "~viewer/fastLinkJS"` - Scala.js hot reload for frontend
- `npm run dev` - Vite development server  
- `sbt --client testFull` - Run all tests (sbt 2's `test` is incremental only)
- `sbt --client "sharedJVM/testOnly <TestName>"` - Run single test (prefer JVM version)
- `sbt --client "viewer/testOnly <TestName>"` - Run single frontend test
- `sbt --client projects` - List all SBT modules
- `npm install` - Install dependencies
- `sbt "viewer/fullLinkJS" && npm run build` - Production build

## Architecture
- **Root** - Aggregates submodules and build configuration
- **Shared** (`/shared/`) - Cross-compiled (JVM/JS) core logic: graph models, DOT parsing, utilities
- **Viewer** (`/viewer/`) - Scala.js frontend: UI components, state management, canvas interactions
- **Key Components**: ViewerGraph, DOT Parser, ViewerState, SvgCanvas, AttributesToolbar
- **State Management**: Laminar reactive streams with QuickLens for immutable updates

## Code Style
- **Scala 3 "fewer braces" syntax** 
- **Functional programming** with immutable data structures
- **Lens-based updates** using QuickLens for state modifications
- **VectorMap** for ordered collections with fast lookup
- **Test files** use "*Spec" suffix with munit framework
- **Imports**: Prefer QuickLens in scope (`-Yimports`)
- **Strict type safety** with `-Xfatal-warnings` and strict equality
