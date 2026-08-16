# P0 — native-image gate

Temporary. This directory exists to answer one question, and should be deleted
once it is answered either way.

**The question:** `docs/desktop-gx-v2-architecture.md` D2 decides that `gx` is
rewritten in Scala and shipped as a GraalVM native-image binary. That decision
rests on measurements taken on a single macOS/ARM machine. D2.1 records what
those measurements could not cover:

1. do Linux and Windows build at all
2. does peak build RSS fit a standard CI runner — macOS came in at **~5.7 GB
   against 7 GB**, the tightest number in the decision
3. does `java.nio` file I/O survive native-image: `fsync`, `ATOMIC_MOVE`, POSIX
   permissions, `toRealPath`
4. does `MessageDigest` work — security providers register reflectively, which
   is a classic native-image failure

## Running it

```bash
./scripts/native-image-spike.sh
```

Requires `sbt`, `scala-cli` (which fetches GraalVM itself), and `python3`.
CI runs the same script on all three platforms via
`.github/workflows/native-image-spike.yml`.

## What it asserts

Seven checks, each tied to a numbered invariant in the v2 doc, plus a
`--bench-parse` cold-start measurement against the **V-14** budget. It exits
non-zero on any failure, so it is a gate rather than a smoke test.

Three platform facts are *observed* rather than asserted, because the correct
answer differs by filesystem: case sensitivity, symlink support, and path
separator/charset. The case-sensitivity line is the one worth reading — on macOS
it reports `INSENSITIVE`, which is the trap
`sources-and-library-architecture.md` §4.2 exists to handle.

## Outcome

- **macOS/ARM:** all checks pass, 7.0ms parse-only cold start, peak build RSS
  5.76 GB, 26 MB binary, 28s build.
- **Linux, Windows:** pending the first CI run.

## Removing it

If P0 passes, this becomes redundant the moment `gx-core` exists as a real sbt
module (v2 P1) — delete this directory, `scripts/native-image-spike.sh`, the
workflow, and the `nativeImageClasspath` task in `build.sbt`, which exists only
to feed it.

If P0 fails, the fallback recorded in D2.1 is a `gx serve` daemon with a thin
client — which D7.4 wants eventually anyway.
