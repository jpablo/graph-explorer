# Local Protocol

This file describes how `graph-explorer-desktop` talks to its clients and to its own page, as the code does it now:

- the **control channel**: a socket that `gx` and the smoke scripts use,
- the **Tauri IPC commands**: the calls that the page makes to the shell,
- the **events**: the messages that the shell sends to the page.

The server is `desktop/src-tauri/src/main.rs`. The page side is `viewer/src/main/scala/org/jpablo/graphexplorer/viewer/desktop/`. The `gx` side is `gx-cli/src/main/scala/org/jpablo/graphexplorer/gx/Cli.scala`. The terms (diagram, library, metadata, live view) are the terms of `CONTEXT.md`.

This file replaces `v1/schema.json`. That file described an HTTP API with a bearer token, and that API does not exist now (`docs/desktop-gx-v2-architecture.md` D4).

## Transport

The control channel is a unix domain socket, on each platform, Windows too:

```
$GX_HOME/runtime/control.sock
```

When `GX_HOME` is not set, it is `~/.graph-explorer`. A blank value means "not set". A relative value is refused. The desktop and `gx` read the same variable, so it moves the library, the socket and the audit log together.

Windows has AF_UNIX since Windows 10 1803. Thus there is one transport and one client in `gx`.

**The file permissions of the socket are the authentication.** The desktop makes the socket `0600`, in a directory of the user. The operating system decides who can connect, and there is no credential to give out or to steal. A webview cannot `fetch()` a unix socket, so the page cannot reach the control channel.

A socket address has a fixed size (104 bytes for the path on macOS, 108 on Linux). The desktop refuses a socket path of more than 100 bytes at start, and the message gives the length.

## Discovery

The desktop writes `$GX_HOME/runtime/control.json` after it binds the socket:

```json
{ "pid": 26453, "socket": "/Users/you/.graph-explorer/runtime/control.sock", "version": "0.9.5" }
```

Read the socket path from this file. Do not make the path yourself.

## Liveness

**The files do not prove that a desktop runs.** The runtime file and the socket file stay after a crash. Only `connect()` tells a running desktop from its remains. A stale socket gives `ECONNREFUSED`, and each client reads that as "no desktop".

The desktop binds the socket before its window exists. On a cold Windows machine, the window can take 30 seconds. In that time:

- `status` answers with `"state": "starting"` and `"running": false`.
- A method that needs the window answers `STARTING`.

After the window exists, `show` waits until the page listens, for 45 seconds at most. Thus a client must wait for `"running": true` before its first `show`.

## Framing

There is one JSON object on one line, in each direction. The desktop answers the requests of a connection in order.

A JSON string cannot hold a raw newline, so **the line is the frame**. There is no length prefix, and a person can read the traffic: `gx --debug-protocol` prints each frame in each direction. Each frame is UTF-8. The name of the charset is explicit, because the default charset of Windows is `windows-1252`, and the bytes of a file are its revision (V-16).

Request:

```json
{"id": 1, "method": "get-document", "params": {"path": "/tmp/a.dot"}}
```

Response, on success:

```json
{"id": 1, "ok": true, "result": {"document": {"path": "/tmp/a.dot",
                                              "revision": "9f86d081884c7d65…", "…": "…"}}}
```

Response, on failure:

```json
{"id": 1, "ok": false, "error": {"code": "DOCUMENT_CONFLICT", "message": "…",
                                 "currentRevision": "3b1f…", "attemptedBaseRevision": "9f86…"}}
```

The desktop sends back the `id` of the request, so a client can find a stream that is out of step. Send the `id` as a JSON number.

Limits:

- A frame that is not JSON, or not UTF-8, gets `INVALID_REQUEST`, with no `id`.
- A frame of more than `GX_MAX_REQUEST_BODY_BYTES` bytes (default 1 048 576) gets `PAYLOAD_TOO_LARGE`. Then the desktop closes the connection, because it cannot trust the frame boundary after a frame that is too long.
- More than `GX_RATE_LIMIT_MAX_REQUESTS` requests (default 240) in `GX_RATE_LIMIT_WINDOW_MS` milliseconds (default 10 000) get `RATE_LIMITED`. The limit stops an agent in a loop. The socket permissions keep out other users.

Paths are JSON strings. There is no encoding step, so there is no decoding step to get wrong. A decoder of that type made v1 fail on Windows.

## Revisions

A revision is the **hex SHA-256 of the bytes of a file** (D1), as a JSON string. It is not a counter.

Thus `baseRevision` works as `If-Match`: write the text to the file only if the file has this hash now. Each process can calculate the hash from the file, the hash is the same after a restart, and the desktop finds a write by a program that does not use this protocol.

Two writes of the same bytes give the same revision, and an edit A → B → A goes back to the first revision. For conflict detection on a text file, that is correct: if the file has the bytes that my edit started from, my edit is safe.

## Socket methods

| Method | Params | Result | Sent by |
|---|---|---|---|
| `status` | none | `running`, `state` (`starting` or `running`), `version`, `pid`, `socket`, `watches`, `allowedRoots`, `deniedRoots`, `maxBodyBytes`, `rateLimitMaxRequests`, `rateLimitWindowMs`, `unsavedInPage` | `gx status`, the gates |
| `show` | `target`: `{"kind": "file", "path": …}` | the watch descriptor (`path`, `format`, `revision`), `focused`, and `view`: `{"kind": "file", "sessionId", "revision"}` | `gx open` |
| `live-view` | `command`, `params` (see below) | the answer of the page | `gx select`, `gx selection`, `gx fit` |
| `watch` | `path` | the watch descriptor, and `focused: true` | the gates |
| `unwatch` | `path` | `removed` | the gates |
| `get-document` | `path` of a watched file | `document`: `path`, `text`, `format`, `revision`, `timestampMs` | the gates |
| `put-document` | `path`, `text`, `baseRevision`, and `source` (optional) | `document` | the gates |
| `push-text` | `sessionId`, `text` | `pushed: true` | the gates |

`gx` sends only `status`, `show` and `live-view`. The smoke scripts in `scripts/` use the other methods.

`unsavedInPage` is true when the page has an edit that is not in the file. The desktop then asks the person before a window closes, so a script can read it before it does something disruptive.

`watch` only registers a file. It does not show the file, and it does not change the window.

### `show`

`show` has one target kind only: `file` (ADR 0001). A different kind, for example the old `library` kind, gets `INVALID_REQUEST`. The desktop does these steps:

1. It waits for the page.
2. It watches the file. A file that does not exist, or a path that the policy refuses, gets `WATCH_FAILED` at once.
3. It sends the text of the file to the page (`ge:document.changed`). Then it sends the open request (`ge:open.requested`).
4. It waits until the page says that it shows the file (`complete_open`), for 45 seconds at most (`OPEN_TIMEOUT`).
5. It raises the window. `focused: false` tells that no window came to the front.

The page refuses a file that does not parse with `PARSE_FAILED`. When an open fails, the desktop removes the watch that this open made, and it keeps a watch that was there before. It also keeps the new watch for `PARSE_FAILED` and `ACTIVATION_REFUSED`, because the page holds the file: it shows the text with an error, or it can show the file after the unsaved-changes dialog. When the window goes to a different view before it shows the file, the page refuses with `OPEN_SUPERSEDED`. An open of the file that the window shows now does not ask about an unsaved edit. A second `show` of a file sends its text again, and the answer has the same `sessionId`. The `sessionId` is opaque. It never holds the path.

### `live-view`

The shell does not answer these commands. It sends the frame to the page as a `ge:live-view.command` event, with a request id. The page answers through the `live_view_reply` command, and the shell sends that answer on the socket. The shell waits 5 seconds, then answers `LIVE_VIEW_TIMEOUT`.

| `command` | `params` | Answer |
|---|---|---|
| `select` | `path`, `targets` (refs, for example `node:a`) | `null` |
| `add-to-selection` | `path`, `targets` | `null` |
| `clear-selection` | `path` | `null` |
| `reset-view` | `path` | `null` |
| `what-is-selected` | none | `{"path", "shows", "selection"}` |
| `push-text` | `sessionId`, `text` | `null` |

- A command that changes the live view must give the canonical `path` of its diagram (plan §2). When the window shows a different diagram, the page refuses with `VIEW_REJECTED`, and the message names what the window shows. A command with no `path` gets `INVALID_REQUEST`.
- In the answer of `what-is-selected`, `path` is the canonical path of the file on the screen. It is `null` when the window shows an example or a browser diagram. `shows` names the diagram on the screen for a person. `selection` holds the refs of the selected elements. `gx selection` changes the refs into element names.
- With no window, or with no diagram on the screen, the answer is `NO_LIVE_VIEW`.
- `push-text` puts the text into the editor of one document session. It does not write the file, so the editor then has an unsaved edit. The page refuses it with `VIEW_REJECTED` when the window shows a different session. The socket method `push-text` sends the same command.
- The shell passes on three codes of the page only: `NO_LIVE_VIEW`, `INVALID_REQUEST` and `VIEW_REJECTED`. Each other code of the page becomes `LIVE_VIEW_FAILED`.

## Tauri IPC commands

The page calls these commands with `window.__TAURI__.core.invoke`. The argument names are camelCase. A rejection is `{code, message}`. A conflict adds `currentRevision` and `attemptedBaseRevision`.

The shell applies the access policy (`GX_ALLOWED_ROOTS`, `GX_DENY_ROOTS`) to each path that the page gives, as it does for a socket client. It writes each file operation and each library operation to the audit log, `$GX_HOME/runtime/audit.log.jsonl`.

### Diagram files

A snapshot is `{path, text, format, revision, timestampMs}`. The `path` of a snapshot is canonical.

| Command | Arguments | Result | Errors |
|---|---|---|---|
| `open_document` | `path` | a snapshot. It starts a watch | `WATCH_FAILED`, `DOCUMENT_READ_FAILED` |
| `save_document` | `path`, `text`, `baseRevision` | a snapshot. It writes only if the file has the revision `baseRevision` | `DOCUMENT_CONFLICT`, `DOCUMENT_NOT_WATCHED`, `DOCUMENT_WRITE_FAILED` |
| `read_document` | `path` | a snapshot, with no watch. The home page reads each file of the library with it | `DOCUMENT_DENIED`, `DOCUMENT_MISSING`, `DOCUMENT_READ_FAILED` |
| `create_document` | `path`, `text` | a snapshot. It never writes over a file: a hard link gives the file its name | `DOCUMENT_DENIED`, `DOCUMENT_EXISTS`, `DOCUMENT_WRITE_FAILED` |
| `rename_document` | `from`, `to` | `path`, `revision`, `watchMoved`. It never writes over a file, and the watch moves to the new path | `DOCUMENT_DENIED`, `DOCUMENT_MISSING`, `DOCUMENT_EXISTS`, `DOCUMENT_RENAME_FAILED` |
| `list_documents` | none | the watch descriptors | none |
| `diagrams_folder` | none | `path`: the folder for new diagrams. `existing`: the names in it and in its `Recovered/` folder | `DIAGRAMS_FOLDER_INVALID`, `DOCUMENT_DENIED` |
| `hash_text` | `text` | the hex SHA-256 of the UTF-8 bytes of `text` | none |

The folder for new diagrams is `$GX_DIAGRAMS_DIR`, or `~/Documents/Graph Explorer` when it is not set. `GX_HOME` does not move it. `fixtures/diagrams-dir.json` holds the rule, and the Scala tests and the Rust tests both read it.

### The library

The library is `$GX_HOME/library/metadata/`: one JSON entry for each diagram.

| Command | Arguments | Result | Errors |
|---|---|---|---|
| `library_list` | none | each entry: `name`, and `json` or `error` | `LIBRARY_UNAVAILABLE` |
| `library_read` | `name` | the JSON text | `LIBRARY_UNAVAILABLE`, `INVALID_LIBRARY_NAME`, `LIBRARY_ENTRY_MISSING`, `LIBRARY_READ_FAILED` |
| `library_write` | `name`, `json`, `base` (optional) | none. With a `base`, it writes only if the entry on the disk is the `base` | `LIBRARY_UNAVAILABLE`, `INVALID_LIBRARY_NAME`, `LIBRARY_NOT_MIGRATED`, `LIBRARY_CONFLICT`, `LIBRARY_WRITE_FAILED` |
| `library_delete` | `name` | `true`, or `false` when the entry was not there. The diagram file stays | `LIBRARY_UNAVAILABLE`, `INVALID_LIBRARY_NAME`, `LIBRARY_DELETE_FAILED` |
| `library_legacy_list` | none | `migrated`, and `records`: the old records of `library/diagrams/`. `records` is empty when `migrated` is true | `LIBRARY_UNAVAILABLE` |
| `library_commit_migration` | `entries`: `[{name, json}]` | `committed`: the number of entries | `LIBRARY_UNAVAILABLE`, `LIBRARY_ALREADY_MIGRATED`, `INVALID_LIBRARY_NAME`, `LIBRARY_MIGRATION_FAILED` |

Rules:

- The name of an entry is `<64 lowercase hex characters>.json`: the SHA-256 of the canonical `file:` URI of the diagram (`LibraryEntry.keySource`). The page makes the URI with the shared Scala function and hashes it with `hash_text`. The shell checks only the form of the name, so that a name cannot point out of the directory.
- The shell moves the JSON text and does not parse it. The schema is in gx-core, and `gx` and the page share it.
- `library_write` does not make `metadata/`. Only the migration makes it, and the migration runs only while `metadata/` does not exist.
- `library_commit_migration` writes the entries into a temporary directory, and renames that directory to `metadata/` last. Thus a crash leaves no part of a store.
- The shell only reads `library/diagrams/`. It never writes there, so an older desktop still shows its old library.
- The `base` of `library_write` is what the page saw of the entry: `{"state": "absent"}`, or `{"state": "present", "json": "..."}` with the exact text. `gx` writes the same directory, so the entry can change after the page read it. On `LIBRARY_CONFLICT` the page reads the entry with `library_read`, merges its change again, and writes again. It tries 3 times in all.

### The window and the live view

| Command | Arguments | What it does |
|---|---|---|
| `viewer_ready` | none | The page listens now. Each open request that waits for the page continues |
| `complete_open` | `requestId`, `status` (`displayed` or `rejected`), `code`, `message`, `view` | The answer of the page to `ge:open.requested`. The shell passes on `DOCUMENT_NOT_FOUND`, `OPEN_DENIED`, `PARSE_FAILED`, `ACTIVATION_REFUSED` and `OPEN_SUPERSEDED`. Each other code becomes `VIEW_REJECTED` |
| `live_view_reply` | `id`, `ok`, `result`, `code`, `message` | The answer of the page to `ge:live-view.command`. The shell drops an answer with an `id` that no request waits for |
| `set_unsaved` | `unsaved` | The page tells whether it has an edit that is not in the file. `status` reports it as `unsavedInPage` |
| `confirm_close` | none | The person answered Save or Discard. The app exits |
| `health` | none | It gives `"ok"` |

## Events

The shell sends each event to the page as a DOM `CustomEvent` on `window`, through `webview.eval`. No event carries a credential.

| Event | `detail` | When |
|---|---|---|
| `ge:document.changed` | `text`, `path`, `revision` | A watch starts. A watched file changes on disk (poll 15 ms, debounce 50 ms). `save_document` or `put-document` writes the file. `show` names a file that has a watch |
| `ge:open.requested` | `requestId`, `target`: `{kind: "file", path}` | `show` |
| `ge:live-view.command` | `id`, `command`, `params` | `live-view` and `push-text` |
| `ge:library.changed` | none | `library/metadata/` changed, and the page did not make the change (poll 400 ms). The page then reads the list again |
| `ge:close.requested` | none | A window close or Quit while the page has an unsaved edit |

The watch loop ignores a change that the shell wrote itself. The library watcher ignores an entry that the page wrote or deleted.

The shell also sends each document event a second time, with the old name `document.changed`. The page of this version does not listen to it.

## Error codes

This is the complete list of codes that the shell can return.

| Code | Where | Meaning |
|---|---|---|
| `INVALID_REQUEST` | socket | The frame is not JSON or not UTF-8, the params do not match the method, the `show` target is not `file`, or the page cannot decode a live-view command |
| `UNKNOWN_METHOD` | socket | No method has this name |
| `RATE_LIMITED` | socket | Too many requests in the window of the rate limit |
| `PAYLOAD_TOO_LARGE` | socket | The frame is longer than `GX_MAX_REQUEST_BODY_BYTES`. The connection closes |
| `STARTING` | socket | The window does not exist yet (`show`, `watch`, `put-document`, `live-view`, `push-text`) |
| `NO_WINDOW` | socket | `show` found no window, or the window refused the script |
| `WATCH_FAILED` | socket, IPC | The file does not exist, is not a regular file, or the policy refuses it (`watch`, `show`, `open_document`) |
| `UNWATCH_FAILED` | socket | `unwatch` cannot resolve the path |
| `DOCUMENT_READ_FAILED` | socket, IPC | The file has no watch (`get-document`), or the shell cannot read it |
| `DOCUMENT_WRITE_FAILED` | socket, IPC | The file has no watch (`put-document`), or the shell cannot write it |
| `DOCUMENT_NOT_WATCHED` | IPC | `save_document`: the file has no watch. The page starts a watch (`open_document`) and tries the save again one time, with the same `baseRevision` |
| `DOCUMENT_CONFLICT` | socket, IPC | The file does not have the revision `baseRevision`. The error has `currentRevision` and `attemptedBaseRevision` |
| `DOCUMENT_NOT_FOUND` | socket | `show`: the page has no document session for the path |
| `OPEN_DENIED` | socket | `show`: the shell passes it on from the page. The page of this version does not send it |
| `PARSE_FAILED` | socket | `show`: the page cannot parse the file |
| `ACTIVATION_REFUSED` | socket | `show`: the page did not go to the file |
| `OPEN_SUPERSEDED` | socket | `show`: the window went to a different view before it showed the file |
| `VIEW_REJECTED` | socket | `show`: the page cannot route the target. `live-view`: the window shows a different diagram or session |
| `OPEN_TIMEOUT` | socket | `show`: the page did not come up, or did not answer, in 45 seconds |
| `NO_LIVE_VIEW` | socket | `live-view`: there is no window, or no diagram on the screen |
| `LIVE_VIEW_FAILED` | socket | `live-view`: the shell cannot reach the page, or the page gave a code that the shell does not pass on |
| `LIVE_VIEW_TIMEOUT` | socket | `live-view`: the page did not answer in 5 seconds |
| `INTERNAL` | socket | A lock or a serialization failed |
| `DOCUMENT_DENIED` | IPC | The access policy refuses the path |
| `DOCUMENT_MISSING` | IPC | The file does not exist (`read_document`, `rename_document`) |
| `DOCUMENT_EXISTS` | IPC | A file with that name exists (`create_document`, `rename_document`) |
| `DOCUMENT_RENAME_FAILED` | IPC | The rename failed, or the watch did not move |
| `DIAGRAMS_FOLDER_INVALID` | IPC | `GX_DIAGRAMS_DIR` is relative, or the folder cannot be resolved |
| `INVALID_LIBRARY_NAME` | IPC | The entry name does not have the form `<64 hex>.json`, or the migration gives one name two times |
| `LIBRARY_UNAVAILABLE` | IPC | `GX_HOME` is relative, or the shell cannot find the home directory |
| `LIBRARY_ENTRY_MISSING` | IPC | `library_read`: the entry does not exist |
| `LIBRARY_READ_FAILED` | IPC | The shell cannot read the entry |
| `LIBRARY_WRITE_FAILED` | IPC | The shell cannot write the entry |
| `LIBRARY_CONFLICT` | IPC | `library_write`: the entry on the disk is not the `base`. The disk does not change |
| `LIBRARY_DELETE_FAILED` | IPC | The shell cannot delete the entry |
| `LIBRARY_NOT_MIGRATED` | IPC | `library_write` before `metadata/` exists |
| `LIBRARY_ALREADY_MIGRATED` | IPC | `metadata/` exists, so the migration is done |
| `LIBRARY_MIGRATION_FAILED` | IPC | The shell cannot write or rename the temporary store |

`DIAGRAM_NOT_FOUND` does not exist now. It went with the `library` target of `show`.

`gx` changes these codes into exit codes. `NO_WINDOW` and `NO_LIVE_VIEW` give exit 2, like a desktop that does not run. Each other code gives exit 4. `skills/gx/commands.md` has the table.

## Implementations

There are three independent clients. A protocol with one client hides its assumptions.

| | Where | Notes |
|---|---|---|
| server | `desktop/src-tauri/src/main.rs` | std `UnixListener`; `uds_windows` on Windows |
| `gx` | `gx-core/jvm/…/rpc/ControlChannel.scala` | JDK `SocketChannel` and `UnixDomainSocketAddress` |
| gates | `scripts/lib/control-client.py`, and .NET in `scripts/local-capabilities-release-smoke.ps1` | the gates test the contract, not the view of one client |

The fixtures in `fixtures/` hold the rules that the Scala tests and the Rust tests both read: `canonicalization.json` (V-13: one spelling for each file), `content-hashes.json` (the hash), `diagrams-dir.json` (the folder for new diagrams), and `library-record.json` (an old record, for the migration).
