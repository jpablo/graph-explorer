---
name: release
description: Cut a Graph Explorer release — bump the patch tag, publish desktop/gx binaries to a public GitHub Release, and verify the running build reports the new version. Use when the user asks to cut a release, bump the version, tag a version, publish binaries, or "new release".
---

# Cutting a Graph Explorer release

A "release" here is **one thing: pushing a `vX.Y.Z` tag.** Everything else follows from it.
Pushing that tag builds desktop + `gx` binaries on three platforms and creates a **public
GitHub Release** on `jpablo/graph-explorer`. That is irreversible in the sense that matters —
people can see and download it.

**Always confirm with the user before pushing the tag.** Pushing commits is recoverable;
publishing a Release is not.

## The shape of it

```
commits on `viewer`  ──push──>  origin/viewer     (no GitHub CI runs; Netlify may deploy)
        │
        └── git tag vX.Y.Z ──push──> release-binaries.yml
                                        ├── build-frontend (ubuntu, JDK 17): Scala.js fullLinkJS + vite build
                                        └── release (macos / ubuntu / windows, fail-fast: false)
                                              ├── cargo build --release --locked  (desktop + gx)
                                              ├── per-platform runtime smoke  ← publish gate
                                              └── softprops/action-gh-release  → attaches assets
```

## Preflight

Run these and stop if any fails.

1. **Clean tree.** `git status --short` must be empty. A dirty tree makes `dynver` stamp the
   build `…+YYYYMMDD-HHMM`, which is how a "release" build ends up self-identifying as a dev build.
2. **Full suite green — locally.** `sbt --client test`
   Expect **1673** tests: `377 + 96 + 810 + 390`, `Failed 0` on each line.
   Since v0.6.21, `ci.yml` also runs the suite and the optimized frontend build on every push to
   `viewer`, so check that the commit you are about to tag is green:
   ```bash
   gh run list --workflow=ci.yml --branch viewer --limit 1
   ```
   Run the suite locally anyway — CI reports *after* the push, and the tag is the irreversible
   step. Before v0.6.21 there was no CI on `viewer` at all: `dev.yml` and `release.yml` watched
   `dev` and `release`, **neither of which exists**, so the tests had never once run in CI.
3. **The 810 are the graphviz byte-exact corpus.** If those moved, stop and ask — the port is an
   exact transcription of the dot engine and a corpus diff is a real regression, never a rebaseline.
4. **Confirm the branch.** Releases are cut on `viewer`, which is the default branch
   (`origin/HEAD -> origin/viewer`). `v0.6.18`, `v0.6.19`, `v0.6.20` all point at commits on it.
5. **Commits pushed first.** The tag must point at a commit origin already has.

## Steps

### 1. Push the commits

```bash
git push origin viewer
```

No GitHub CI fires. Netlify does build the web app from this repo via
`scripts/build-viewer-netlify.sh`; there is **no `netlify.toml` in the repo**, so the branch and
trigger live in the Netlify UI. Treat a push to `viewer` as *possibly* publishing the web app,
and say so to the user rather than promising it either way.

### 2. Pick the version

`scripts/bump-patch-version.sh` is the project's own tool: it fetches tags, takes the highest
`v*.*.*`, increments the patch, then creates and pushes the tag.

**It ends in an interactive `read -p` confirm, which cannot be driven from a tool call.** Either
have the user run it themselves, or do the equivalent explicitly — the result is identical:

```bash
git fetch --tags --quiet && git tag -l "v*.*.*" --sort=-v:refname | head -n 1
```

Then, after the user confirms the new number:

```bash
git tag vX.Y.Z
```

The script creates a **lightweight** tag (`git tag "$NEW_TAG"`), not annotated. Match that.

### 3. Push the tag — the point of no return

Confirm with the user first. Then:

```bash
git push origin vX.Y.Z
```

### 4. Watch the run

```bash
gh run list --workflow=release-binaries.yml --limit 3
```

```bash
gh run watch <run-id> --exit-status
```

For a long build, launch the watch as a background Bash command rather than blocking, and report
when it finishes. Past releases took this approach (logging to the scratchpad).

### 5. Verify the published Release

```bash
gh release view vX.Y.Z
```

Expect six assets, named per platform:

- `graph-explorer-desktop-vX.Y.Z-macos.dmg`, `gx-vX.Y.Z-macos`
- `graph-explorer-desktop-vX.Y.Z-linux`, `gx-vX.Y.Z-linux`
- `graph-explorer-desktop-vX.Y.Z-windows.exe`, `gx-vX.Y.Z-windows.exe`

`fail_on_unmatched_files: true`, so a platform that produced nothing fails its own job loudly
rather than publishing a half-empty Release.

## Verifying the app reports the new version

The app logs `version` / `builtAt` to the browser console at startup (from `BuildInfo`, fed by
`sbt-dynver`). **This is the single most misleading thing in the whole process — read this before
telling anyone what build they are running.**

`sbt-dynver` computes the version from `git describe` **once, at sbt project load**. A long-lived
sbt server keeps reporting whatever it computed when it started. In practice:

- The machine had **seven** sbt servers running at once. `sbt --client` attaches to one of them.
- A build produced through a server loaded at an older HEAD reported
  `0.6.20+13-2b8d0a46+20260730-2334` while HEAD was actually `+17-7f0f84ad` — the version string
  appeared to go *backwards* between two consecutive builds.
- The compiled output was current; only the stamped string was stale.

So after tagging, refresh the build definition — `reload` re-evaluates it and re-runs dynver:

```bash
sbt --client reload
```

```bash
sbt --client "show version"
```

Confirm it prints the new tag before rebuilding. **Prefer `reload` over `shutdown`**: it is
non-destructive, takes about five seconds, and does not kill a server that may be running the
user's `~viewer/fastLinkJS` watch. Verified on the v0.6.21 release — a server reporting
`0.6.20+13-2b8d0a46+20260730-2334` printed `0.6.21` immediately after `reload`. Fall back to
`sbt --client shutdown` only if `reload` somehow does not take.

Then rebuild (`sbt --client "viewer/fastLinkJS"`, or `make build` for production) and
**hard-reload** the browser tab. Skip this and the tag is invisible to the version string, and
you will be verifying blind.

Related: `dynver` needs tags *and* full history. `scripts/build-viewer-netlify.sh` runs
`git fetch --force --unshallow --tags` for exactly this reason — a shallow clone with no tags
produces a garbage version.

## When something fails

**A platform's smoke gate fails.** `fail-fast: false`, so the others still publish. Fix, then
re-attach assets for the *existing* tag via manual dispatch — no new tag, no version burned:

```bash
gh workflow run release-binaries.yml -f tag=vX.Y.Z
```

**Windows smoke fails.** Expected and non-blocking (`continue-on-error: true`) — a known, parked
`gx`/desktop path-normalization bug. The Windows *binary* still builds and still ships. Don't
"fix" it by reacting to that log line; it is deliberate, and the workflow comment says to remove
the flag only once the path bug is fixed.

**Netlify build dies on the JDK.** `sdk install java 17.0.20-tem` — sdkman *prunes* old patch
releases, and this script has no `set -e`, so a pruned pin used to fail silently and build on
whatever JDK the image had. There is now an explicit `java -version` check that fails loudly.
If it trips, pick a currently-offered 17.x and update the pin.

**You want to exercise CI without cutting a release.** Use a `workflow_dispatch` on an existing
tag (above), or the smoke workflows — never a throwaway tag, which would publish a real Release.

## Facts worth not re-deriving

- Latest tag as of this writing: `v0.6.20` at `28061287`.
- Version flows: `sbt-dynver` → `sbt-buildinfo` (`buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion)`) → console banner.
- `Makefile`: `make test` (`sbt test`), `make build` (`sbt "viewer/fullLinkJS"` + `npm run build`).
- All GitHub workflows pin **JDK 17**; the Netlify script matches deliberately so the site is built like the binaries.
- `ci.yml` (added v0.6.21) is the default-branch gate: suite + optimized frontend build on push/PR to `viewer`, plus `workflow_dispatch`. It replaced `dev.yml`, which watched a non-existent `dev` branch and had therefore never run.
- `release.yml` (branch `release`) is **dead**, not merely secondary: the `release` branch does not exist, *and* its "Create Artifact" step runs `scripts/build-package.sh`, which is not in the repo. Ignore it; `release-binaries.yml` is what cuts binaries.
- `release-binaries.yml` runs **no unit tests** — it compiles, bundles, builds Rust and runs runtime smokes. The suite is `ci.yml`'s job.
- Run every sbt command from the repo root, and prefer `sbt --client`.
