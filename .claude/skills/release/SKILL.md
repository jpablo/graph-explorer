---
name: release
description: Cut a Graph Explorer release — push all commits to origin/viewer, then tag and push the next patch version, which publishes desktop/gx binaries to a public GitHub Release; then verify the running build reports the new version. "Release" always means the whole chain (push AND tag), never one half. Use when the user asks to cut a release, bump the version, tag a version, publish binaries, ship, or "new release".
---

# Cutting a Graph Explorer release

## What "release" means here — settled, do not re-ask

When the user says **release**, they mean the **whole chain**:

> push every local commit to `origin/viewer`, **then** tag the next patch version and push the
> tag, which publishes a public GitHub Release with desktop + `gx` binaries for three platforms.

This is a standing decision. **Do not offer "push only", "hold the tag", or "just rebuild
locally" as alternatives** — those were one-off choices in the session that produced this
skill, and presenting them again turns a settled workflow back into a menu. Run the chain.

**The version is not a question either.** It is the latest `v*.*.*` tag with the patch
incremented — exactly what `bump-patch-version.sh` computes. Derive it, state it in passing
("cutting v0.6.22"), and keep going. Asking the user to confirm a number they cannot know
without running the same command is a fake choice; it looks like diligence and delivers none.

So: **`/release` has no gates.** Run preflight, push, tag, watch, verify, report.

Stop only if preflight actually fails — a dirty tree, a red suite, or a moved graphviz corpus.
Those are facts that change what should happen, not menu options. Likewise, raise a *minor* or
*major* bump only if the user brings it up; never ask "or should this be a minor?".

## The shape of it

```
commits on `viewer`  ──push──>  origin/viewer     (ci.yml runs; Netlify deploys graph-explorer.net)
        │
        └── git tag vX.Y.Z ──push──> release-binaries.yml
                                        ├── build-frontend (ubuntu, JDK 17): Scala.js fullLinkJS + vite build
                                        └── release (macos / ubuntu / windows, fail-fast: false)
                                              ├── cargo build --release --locked  (desktop + gx)
                                              ├── per-platform runtime smoke  ← publish gate
                                              └── softprops/action-gh-release  → attaches assets
```

Both halves matter, and they reach different audiences: the **push** updates the live site
`graph-explorer.net`, the **tag** updates the downloadable binaries. Doing only the first is
how the site and the artifacts drift apart — which is exactly what happened around v0.6.21,
where the tag ended up four commits behind the branch.

## Preflight

Run these and stop if any fails.

1. **Clean tree.** `git status --short` must be empty. A dirty tree makes `dynver` stamp the
   build `…+YYYYMMDD-HHMM`, which is how a "release" build ends up self-identifying as a dev build.
2. **Full suite green — locally.** `sbt --client test`
   Expect **1677** tests: `377 + 100 + 810 + 390`, `Failed 0` on each line.
   Pipe to `grep -E "Passed: Total|\[error\]"` rather than `tail` — `tail` keeps only the last
   module's tally, which looks exactly like a suite that ran one module and passed.
   `sbt --client` attaches to whatever project the server has current, so confirm it is `root`
   (`sbt --client projects`, the starred entry) or the aggregate never runs.
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

This half of the release is what updates the **live site**. `ci.yml` fires (suite + optimized
frontend build), and Netlify builds the web app via `scripts/build-viewer-netlify.sh` — there is
**no `netlify.toml` in the repo**, so the exact branch and trigger live in the Netlify UI; say
"this deploys `graph-explorer.net`" rather than promising a specific mechanism.

Push **everything**, not a subset. A tag can only point at a commit origin already has, and a
release whose tag trails the branch is the drift this skill exists to prevent.

### 2. Pick the version

`scripts/bump-patch-version.sh` is the project's own tool: it fetches tags, takes the highest
`v*.*.*`, increments the patch, then creates and pushes the tag.

**It ends in an interactive `read -p` confirm, which cannot be driven from a tool call.** Either
have the user run it themselves, or do the equivalent explicitly — the result is identical:

```bash
git fetch --tags --quiet && git tag -l "v*.*.*" --sort=-v:refname | head -n 1
```

Increment the patch. Do not ask — state the number and continue:

```bash
git tag vX.Y.Z
```

The script creates a **lightweight** tag (`git tag "$NEW_TAG"`), not annotated. Match that.

### 3. Push the tag

No confirmation. The scope and the number are both settled above.

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

Note the Release publishes **incrementally** — `action-gh-release` attaches per platform as each
matrix job finishes, and with `fail-fast: false` a slow platform never blocks the others. "The
Release page exists" and "the Release is complete" are different states. Check the asset count.

### 6. Confirm all three levels agree

"Published" is three separate states in this repo, and they drift. Report them explicitly rather
than saying "released":

```bash
git status -sb | head -1 && git log --oneline @{u}.. && git log --oneline vX.Y.Z..HEAD
```

A finished release means: branch **not ahead**, `vX.Y.Z..HEAD` **empty** (the tag is at HEAD),
and six assets attached. If any differs, say which — "the site has it, the binaries do not" is a
real and confusing state, and the user should never have to ask which one they are looking at.

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

- Latest tag as of this writing: `v0.6.22`.
- Version flows: `sbt-dynver` → `sbt-buildinfo` (`buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion)`) → console banner.
- `Makefile`: `make test` (`sbt test`), `make build` (`sbt "viewer/fullLinkJS"` + `npm run build`).
- All GitHub workflows pin **JDK 17**; the Netlify script matches deliberately so the site is built like the binaries.
- `ci.yml` (added v0.6.21) is the default-branch gate: suite + optimized frontend build on push/PR to `viewer`, plus `workflow_dispatch`. It replaced `dev.yml`, which watched a non-existent `dev` branch and had therefore never run.
- `release.yml` **was deleted** in v0.6.22. If you find a reference to it, it is stale. It was broken four ways at once — watched a `release` branch that does not exist, called `scripts/build-package.sh` (deleted 2025-04-28), uploaded `backend/target/universal/*.zip` from a `backend` module removed when the zio-http server was dropped, and used the retired `actions/upload-artifact@v2`. `release-binaries.yml` is what cuts binaries.
- `release-binaries.yml` runs **no unit tests** — it compiles, bundles, builds Rust and runs runtime smokes. The suite is `ci.yml`'s job.
- Run every sbt command from the repo root, and prefer `sbt --client`.
