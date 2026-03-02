# Local Capabilities v1 Go/No-Go Review

Last updated: 2026-05-17
Decision status: **Conditional No-Go** (one known Windows runtime defect)

> 2026-05-17 update: the original macOS evidence below was produced by a
> desktop binary built without `tauri/custom-protocol`, i.e. a dev build that
> never loaded the embedded UI (blank window). That root cause is fixed; CI now
> builds a true production desktop and validates the frontend + macOS + Linux
> runtime end to end. The one remaining gap is a concrete Windows path bug
> (below), not the broad "unvalidated packaging" the original review assumed.

## Scope summary

Implemented and validated in this branch:

- desktop local control API with token auth and runtime discovery file
- `gx` CLI watch/get/set/status/unwatch flows with revision conflict handling
- two-way sync contract (`/v1/document`) and UI save bridge wiring
- policy hardening:
  - allowlist (`GX_ALLOWED_ROOTS`)
  - default denylist + extensions (`GX_DENY_ROOTS`)
  - request body and rate limits
  - structured local audit logging (metadata only)
- regression and smoke coverage:
  - `sbt test`
  - `npm run build`
  - `scripts/local-capabilities-phase3-smoke.sh`
  - `scripts/local-capabilities-policy-smoke.sh`
  - `scripts/local-capabilities-limits-smoke.sh`

## Evidence snapshot

CI (`.github/workflows/local-capabilities-smoke.yml`), production builds
(`--features tauri/custom-protocol`, frontend built and embedded):

- frontend bundle builds (stc facades + Scala.js fullLink) and is embedded
- desktop + `gx` build as production binaries on macOS, Linux, and Windows
- prod-build guard fails CI if the desktop ever regresses to a dev build
- macOS and Linux release runtime smoke pass:
  - `status` reachable
  - `watch/get/set/unwatch` successful
  - stale `set --base-revision` returns conflict with exit code `5`
- disk -> UI update path (LC2-T5) validated on macOS/Linux: external edit
  reaches the webview-emit boundary in <=300ms median (local: ~168ms median)
- audit events verified at `~/.graph-explorer/runtime/audit.log.jsonl`
- desktop GUI render verified manually on macOS (control API alone is not
  sufficient evidence — that gap is what hid the dev-build regression)

## Residual gaps

- cross-platform packaging smoke status:
  - macOS desktop binary validated in runtime flow
  - Linux desktop binary validated in runtime flow
  - Windows desktop binary builds as production, but its runtime smoke fails
    on a known path-normalization defect (see Risks); the Windows smoke step
    is marked `continue-on-error` until that bug is fixed
- browser-driven interactive desktop automation remains limited:
  - command-path and API-path are covered
  - full GUI automation coverage is still optional hardening, not blocking correctness of protocol path

## Risks

- **Observed (Windows): path-normalization defect.** `gx watch <tmp>` succeeds
  but `gx get --file <tmp>` returns exit `4` (`EXIT_INVALID_PATH_OR_PERMISSION`)
  on `windows-latest`. The path accepted at watch time is rejected at get time
  — likely `%TEMP%` short/long name, drive-letter case, or `\` vs `/`
  canonicalization differing between watch registration and get lookup/policy.
  Blocks Windows runtime use until fixed.
- ScalablyTyped facades are pinned to stc-generated content hashes coupled to
  the stc/Scala.js + npm versions; regenerate and repin together on changes.
- Packaging/toolchain differences can produce release-time failures.
- Aggressive request limits may need tuning in real workloads.

## Recommendation

Go for macOS and Linux v1 (production desktop + runtime validated in CI).

No-go for Windows **until the path-normalization defect above is fixed** and
the Windows runtime smoke is restored to blocking (remove `continue-on-error`).

Known limitations regardless of platform:

- local-only trust boundary (no direct hosted web -> filesystem access)
- no collaborative editing / CRDT merge in v1
