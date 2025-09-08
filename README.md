# Graph Explorer

https://graph-explorer.net

Explore graphs visually.

## Shareable URLs

Graph Explorer can load a diagram directly from the URL query string.

- Parameter: `dot=<urlencoded DOT text>`
- Example: `https://<host>/diagrams/<projectId>?dot=<encodeURIComponent(dot)>`
- Behavior on open:
  - If any existing project’s persisted source exactly matches `dot`, that project opens.
  - Otherwise, a new project is created and initialized with `dot`.

Create a link from the UI:
- Toolbar link icon (top‑right)
- Commands palette entry: “Share URL”

Notes:
- The `dot` parameter uses `encodeURIComponent`. Very large diagrams may produce long URLs; a compact format may be added later.

## Planed features
- Add support for Mermaid diagrams
- Improve performance
- I18n
- More precise DOT support
- Keyboard navigation
- Cloud storage
- User defined themes
- Documentation
- More examples

# License

[License](./LICENSE)

Copyright 2025 Juan Pablo Romero and the graph-explorer contributors.
