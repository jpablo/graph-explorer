# Repository Guidelines

## Project Structure & Module Organization
- Root: SBT multi‑module (`build.sbt`).
- `shared/`: Cross‑compiled core (JVM/JS) — graph models, DOT parsing, utilities. Src: `shared/src/main/scala`; tests: `shared/src/test/scala`.
- `viewer/`: Scala.js frontend (Laminar). Src: `viewer/src/main/scala`; tests: `viewer/src/test/scala`.
- Web tooling: `index.html`, `vite.config.js`, `style.scss`, `tailwind.config.cjs`, `postcss.config.cjs`.
- Scripts: `scripts/` (e.g., `build-viewer-netlify.sh`, `bump-patch-version.sh`).

## Build, Test, and Development
- Prefer existing tmux sessions named `sbt` and `vite`; inspect them after every code change for errors. Only if these sessions don't exist should you start `sbt "~viewer/fastLinkJS"` or `npm run dev` yourself.
- Dev compile (Scala.js): `sbt "~viewer/fastLinkJS"` — incremental compile with hot reload.
- Dev server: `npm run dev` — Vite on http://localhost:5173.
- All tests: `sbt testFull`. (Under sbt 2, `test` is the *incremental* task — sbt 1's `testQuick` — so it is not a full run.)
- Single suite: `sbt "sharedJVM/testOnly <TestName>"`, `sbt "viewer/testOnly <TestName>"`.
- Format: `sbt scalafmtAll`.
- Production: `sbt "viewer/fullLinkJS" && npm run build`.
- First‑time UI deps: `npm install`.

## Coding Style & Naming Conventions
- Language: Scala 3 with fewer‑braces, strict equality, and `-Xfatal-warnings`.
- Prefer immutable data; use QuickLens for lens‑style updates.
- Formatting: scalafmt per `.scalafmt.conf` (maxColumn 140), 2‑space indentation.
- Naming: `PascalCase` types, `camelCase` vals/defs; avoid `snake_case`.

## Testing Guidelines
- Frameworks: MUnit (+ ScalaCheck where useful).
- Locations: see module test dirs above; test files end with `*Spec.scala`.
- Keep tests deterministic; prefer JVM tests via `sharedJVM/testOnly` for speed.
- Run locally before PR: `sbt testFull`.

## Commit & Pull Request Guidelines
- Commits: Conventional Commits (e.g., `feat:`, `fix:`, `refactor:`, `test:`, `chore:`). Keep messages imperative and scoped.
- PRs: include a clear description, linked issues, and screenshots/GIFs for UI changes. Note any breaking changes.
- Quality gate: run `sbt scalafmtAll`, `sbt testFull`, and (for UI changes) `npm run build` before requesting review.

## Security & Configuration Tips
- No secrets required for local dev; do not commit tokens. The app uses `localStorage` during development.
- Prereqs: recent Node.js and sbt installed; prefer `sbt --client` for faster startup.
