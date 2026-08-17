# Local Protocol

The control channel between `graph-explorer-desktop` and its clients.

## What happened to `v1/schema.json`

It is gone (P5, `docs/desktop-gx-v2-architecture.md` D4). It described loopback
HTTP with a bearer token, which no longer exists, and it advertised a
`GET /v1/events` SSE endpoint that **was never implemented anywhere** — the real
mechanism was always `webview.eval()` injecting a DOM `CustomEvent`. A contract
document that describes an endpoint nothing serves is worse than no document:
it is a promise the code never made.

This file replaces it, and describes only what runs.

## Transport

A **unix domain socket**, on every platform:

```
~/.graph-explorer/runtime/control.sock
```

Windows included — AF_UNIX has been supported there since Windows 10 1803, and
D4's "named pipe on Windows" would have meant two client implementations in `gx`
that could drift apart. One transport, one client, one thing to get right.

**Authentication is the socket's file permissions.** It is created `0600` inside
a per-user directory, so the OS decides who may connect and there is no
credential anywhere: nothing to hand out, leak, rotate, or accidentally give to
a webview. That last one is not hypothetical — it is what D3 spent a phase
undoing.

A webview cannot `fetch()` a unix socket, so D3's rule is now enforced by the
transport rather than by the page behaving.

## Discovery

`~/.graph-explorer/runtime/control.json`, written at startup:

```json
{ "pid": 26453, "socket": "/Users/you/.graph-explorer/runtime/control.sock", "version": "0.1.0" }
```

Read the socket path from here rather than reconstructing it.

**The file proves nothing about liveness.** It outlives a crash, and so does the
socket file. `connect()` is the only thing that distinguishes a running desktop
from its remains — a stale socket answers `ECONNREFUSED`, which every client
maps to its own "no desktop" outcome.

## Framing

One JSON object per line, in both directions. Request, then response, in order.

A JSON string cannot contain a raw newline, so **the line is the frame** — no
length prefix, and the traffic is readable by a human (`gx --debug-protocol`
prints both directions). Every frame is UTF-8, named explicitly: Windows'
default charset is `windows-1252`, and under D1 the bytes *are* a document's
revision (V-16).

Request:

```json
{"id": 1, "method": "get-document", "params": {"path": "/tmp/a.dot"}}
```

Response, on success:

```json
{"id": 1, "ok": true, "result": {"document": {"path": "/tmp/a.dot", "revision": 3, "…": "…"}}}
```

Response, on failure:

```json
{"id": 1, "ok": false, "error": {"code": "DOCUMENT_CONFLICT", "message": "…",
                                 "currentRevision": 5, "attemptedBaseRevision": 2}}
```

`id` is echoed so a desynchronized stream is caught rather than silently
answering the wrong question. It is a **JSON number** — a client that sends it
as a string will find its own mismatch check quietly matching nothing.

A frame larger than the configured limit (`GX_MAX_REQUEST_BODY_BYTES`) is
refused with `PAYLOAD_TOO_LARGE` and the connection ends: a body that overran
the cap may have been truncated mid-object, so the frame boundaries can no
longer be trusted.

## Methods

| Method | Params | Result | Tier (D7.2) |
|---|---|---|---|
| `status` | — | running, version, pid, socket, watches, policy, limits | — |
| `watch` | `path` | the watch descriptor | document |
| `show` | `path` | the watch descriptor plus `focused` | **session** |
| `unwatch` | `path` | `removed` | document |
| `get-document` | `path` | `document` | document |
| `put-document` | `path`, `text`, `baseRevision`, `source` | `document` | document |
| `push-text` | `text` | `pushed` | session |

`show` is `watch` plus raising the window — the same operation, not a parallel
one, which is what keeps a diagram opened from the shell and one opened in the
UI the same state. It reports `focused: false` rather than claiming success when
there was no window to raise.

Paths travel as JSON strings. There is no encoding step, and therefore no
decoding step to get wrong — the bug class that produced v1's Windows blocker
(a `%2F`-only decoder that mangled spaces and every Windows separator) cannot
occur here.

## Error codes

`INVALID_REQUEST`, `UNKNOWN_METHOD`, `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`,
`WATCH_FAILED`, `UNWATCH_FAILED`, `DOCUMENT_READ_FAILED`,
`DOCUMENT_WRITE_FAILED`, `DOCUMENT_CONFLICT`, `PUSH_FAILED`, `INTERNAL`.

## Implementations

Three, deliberately independent — a protocol with one client is a protocol whose
assumptions are invisible:

| | Where | Notes |
|---|---|---|
| server | `desktop/src-tauri/src/main.rs` | std `UnixListener`; `uds_windows` on Windows |
| `gx` | `gx-core/jvm/…/rpc/ControlChannel.scala` | JDK `SocketChannel` + `UnixDomainSocketAddress` |
| gates | `scripts/lib/control-client.py`, and .NET on the Windows runner | the gates test the contract, not one client's view of it |
