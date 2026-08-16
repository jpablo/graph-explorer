# Graph Explorer

**[https://graph-explorer.net](https://graph-explorer.net)**

## Getting Started

### Prerequisites

*   [sbt](https://docs.scala-lang.org/getting-started/index.html) (Scala Build Tool)
*   [Node.js](https://nodejs.org/en/) (which includes npm)

### Installation (First time)

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/jpablo/graph-explorer.git
    cd graph-explorer
    ```
2.  **Install Node.js dependencies:**
    ```bash
    npm install
    ```
    That is the whole first-time setup: the facades for the JS libraries the
    viewer binds to (CodeMirror, three.js) are hand-written and checked in, so
    there is no code-generation step to run.

### Running Locally

3. **Run the Scala.js build in watch mode:**
    Open a terminal and run:
    ```bash
    sbt "~viewer/fastLinkJS"
    ```
4.  **Start the Vite development server:**
    Open a second terminal and run:
    ```bash
    npm run dev
    ```
5.  **Open the application:** Navigate to the URL provided by Vite (usually `http://localhost:5173`).

## Tech stack

*   Frontend: Scala.js, Vite, Tailwind CSS, daisyUI, CodeMirror
*   Graph Rendering: Viz.js
*   Build: sbt, npm

## Cutting a release

**Push the tag BEFORE the branch.** The version shown in the app comes from
`BuildInfo.version`, which sbt-dynver derives from `git describe` *at build
time*. Netlify rebuilds the site on a push to `viewer` and ignores tag pushes,
so pushing the branch first bakes in the version from the *previous* tag —
`v0.6.0-17-g2425435a` instead of `v0.6.1` — even though the code is right.
(The GitHub release binaries are unaffected: `release-binaries.yml` is
triggered by the tag, so it always sees it.)

```bash
git checkout viewer && git merge --ff-only <work-branch>
sbt testFull                   # release gate (NOT `test` — see below)
git tag vX.Y.Z                 # lightweight, as scripts/bump-patch-version.sh creates
git push origin vX.Y.Z         # tag first: triggers release-binaries.yml
git push origin viewer         # then the branch: triggers the Netlify build
```

If the site ends up on the wrong version anyway, no commit is needed — trigger
**Clear cache and deploy site** in the Netlify dashboard and dynver will pick
the tag up.

**Use `testFull`, not `test`, as the gate.** Under sbt 2 the names moved: `test`
is now the incremental task — it runs only what failed last time or whose
dependencies changed — and `testQuick` is an alias for it. `testFull` is what
sbt 1 called `test`. A green `test` on a warm machine can mean almost nothing
ran, which is exactly the wrong thing to learn from a release gate.

## Contributing

Contributions are welcome! Please feel free to open an issue or submit a pull request.

## License

Distributed under the [Apache License](./LICENSE).

Copyright 2025 Juan Pablo Romero and the graph-explorer contributors.
