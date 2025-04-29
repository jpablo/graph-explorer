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
3. **Install coursier:**  
    ```bash
    curl -Lo cs https://git.io/coursier-cli && chmod +x cs && ./cs setup
    ```
4. **Install and run ScalablyTyped**: 
    ```bash
    ./cs install stc
    export PATH="$PATH:/opt/buildhome/.local/share/coursier/bin"
    stc --ignoredLibs node @codemirror/view @codemirror/lang-javascript @viz-js/viz @codemirror/commands jsdom @viz-js/lang-dot codemirror
    ```
### Running Locally

5. **Run the Scala.js build in watch mode:**
    Open a terminal and run:
    ```bash
    sbt "~viewer/fastLinkJS"
    ```
6.  **Start the Vite development server:**
    Open a second terminal and run:
    ```bash
    npm run dev
    ```
7.  **Open the application:** Navigate to the URL provided by Vite (usually `http://localhost:5173`).

## Tech stack

*   Frontend: Scala.js, Vite, Tailwind CSS, daisyUI, CodeMirror
*   Graph Rendering: Viz.js
*   Build: sbt, npm

## Contributing

Contributions are welcome! Please feel free to open an issue or submit a pull request.

## License

Distributed under the [Apache License](./LICENSE).

Copyright 2025 Juan Pablo Romero and the graph-explorer contributors.
