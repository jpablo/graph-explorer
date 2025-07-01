# Repository Guidelines

## Project Structure & Module Organization
- Root: SBT multi‑module build (`build.sbt`).
- `shared/`: Cross‑compiled core (JVM/JS) — graph models, DOT parsing, utilities. Code in `shared/src/main/scala`; tests in `shared/src/test/scala`.
- `viewer/`: Scala.js frontend (Laminar). Code in `viewer/src/main/scala`; tests in `viewer/src/test/scala`.
- Web tooling: `index.html`, `vite.config.js`, `style.scss`, `tailwind.config.cjs`, `postcss.config.cjs`.
- Scripts: `scripts/` (e.g., `build-viewer-netlify.sh`, `install-stc.sh`).

## Build, Test, and Development Commands
- Dev compile (Scala.js): `sbt "~viewer/fastLinkJS"` — incremental compile with hot reload.
- Dev server (Vite): `npm run dev` — serves UI (usually http://localhost:5173).
- Tests (all): `sbt test` — run unit/property tests across modules.
- Tests (single): `sbt "sharedJVM/testOnly <TestName>"` or `sbt "viewer/testOnly <TestName>"`.
- Format: `sbt scalafmtAll` — apply `.scalafmt.conf` rules.
- Production build: `sbt "viewer/fullLinkJS" && npm run build`.

## Coding Style & Naming Conventions
- Language: Scala 3 (fewer‑braces, strict equality, `-Xfatal-warnings`). Prefer immutable data; use QuickLens for lens‑based updates.
- Formatting: scalafmt (see `.scalafmt.conf`, maxColumn 140). Use 2‑space indentation.
- Naming: `PascalCase` for types, `camelCase` for vals/defs, `snake_case` not used.
- Tests: files end with `*Spec.scala` and use MUnit/ScalaCheck conventions.

## Testing Guidelines
- Frameworks: MUnit (+ ScalaCheck where appropriate).
- Locations: `shared/src/test/scala`, `viewer/src/test/scala`.
- Conventions: one behavior per test; prefer deterministic JVM tests (`sharedJVM/testOnly`) for speed; add fixtures/helpers under `.../utils`.
- Run locally before PR: `sbt test`.

## Commit & Pull Request Guidelines
- Commits: follow Conventional Commits (e.g., `feat:`, `fix:`, `refactor:`, `test:`, `chore:`). Keep messages imperative and scoped.
- PRs: include clear description, linked issues, and screenshots/GIFs for UI changes. Note any breaking changes.
- Quality gate: run `sbt scalafmtAll`, `sbt test`, and (for UI changes) `npm run build` before requesting review.

## Security & Configuration Tips
- No secrets are required for local dev; avoid committing tokens. The app uses `localStorage` for persistence during development.
- Prereqs: recent Node.js and sbt installed. Prefer `sbt --client` if available for faster startup.

