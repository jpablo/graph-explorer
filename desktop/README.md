# Graph Explorer Desktop (v1 scaffold)

This directory contains the desktop companion shell for local capabilities.

Current scope (Phase 0):

- Tauri runtime scaffold
- desktop window booting Graph Explorer UI
- config prepared for:
  - dev mode against Vite (`http://localhost:5173`)
  - production mode against root `dist/`

## Run (dev)

From this directory:

```bash
cargo tauri dev
```

This expects the main project dev server (`npm run dev`) to be available on `localhost:5173`.

## Build

From this directory:

```bash
cargo tauri build
```
