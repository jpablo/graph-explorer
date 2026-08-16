#!/usr/bin/env bash
#
# P0 gate for docs/desktop-gx-v2-architecture.md D2 ("gx is Scala on
# native-image"). Builds spike/native-image as a native binary and runs its
# self-checks.
#
# The macOS/ARM spike passed; what this exists to answer is whether Linux and
# Windows agree, whether the build fits a standard CI runner, and whether
# java.nio file I/O survives native-image. See D2.1 for what was already
# measured and what was not.
#
# Fails loudly: a non-zero exit here means D2 does not hold as written.
#
# Usage: scripts/native-image-spike.sh
# Requires: sbt, scala-cli (which fetches GraalVM itself), python3

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPIKE="$ROOT/spike/native-image"
CP_FILE="$ROOT/target/native-image-classpath.txt"
BUILD_LOG="$ROOT/target/native-image-build.log"

# The V-14 budget: a parse-only command must stay under this, at any graph size.
STARTUP_BUDGET_MS=20

echo "=== P0: native-image gate on $(uname -s) $(uname -m) ==="

# --- 1. classpath -----------------------------------------------------------
# sbt 2's `export`/`show` emit ${OUT}/${CSR_CACHE} placeholders whose expansion
# differs per runner, so a build task writes literal paths instead.
echo "--- resolving sharedJVM classpath"
(cd "$ROOT" && sbt -batch "sharedJVM/nativeImageClasspath")
[ -s "$CP_FILE" ] || { echo "FAIL: $CP_FILE is empty"; exit 1; }

# Windows paths contain ':' (C:\...), so the separator cannot be assumed.
case "$(cat "$CP_FILE")" in
  *";"*) SEP=";" ;;
  *)     SEP=":" ;;
esac

# `|| [ -n "$entry" ]` is load-bearing: the classpath file has no trailing
# newline, and a bare `read` discards a final unterminated line. Dropping the
# last jar (upickle-core) surfaces as a TASTy unpickling crash inside the
# compiler, not as a missing class — an hour to diagnose if it recurs.
JAR_ARGS=()
while IFS= read -r entry || [ -n "$entry" ]; do
  [ -n "$entry" ] && JAR_ARGS+=(--jar "$entry")
done < <(tr "$SEP" '\n' < "$CP_FILE")
echo "    $((${#JAR_ARGS[@]} / 2)) entries (separator '$SEP')"

EXPECTED=$(tr "$SEP" '\n' < "$CP_FILE" | grep -c .)
[ $((${#JAR_ARGS[@]} / 2)) -eq "$EXPECTED" ] || {
  echo "FAIL: passed $((${#JAR_ARGS[@]} / 2)) jars but the file lists $EXPECTED"; exit 1; }

# --- 2. build ---------------------------------------------------------------
# -experimental: the project compiles with language:experimental.pureFunctions,
# which everything linking shared/ inherits (D2.1).
echo "--- building native image (scala-cli fetches GraalVM on first run)"
BUILD_START=$(python3 -c 'import time; print(time.time())')
(cd "$SPIKE" && scala-cli --power package GxSpike.scala \
  --scala 3.7.1 -O -experimental \
  "${JAR_ARGS[@]}" \
  --native-image -o gx-spike -f) 2>&1 | tee "$BUILD_LOG"
BUILD_END=$(python3 -c 'import time; print(time.time())')

BIN="$SPIKE/gx-spike"
[ -x "$BIN" ] || BIN="$SPIKE/gx-spike.exe"
[ -x "$BIN" ] || { echo "FAIL: no binary produced"; exit 1; }

# native-image prints its own peak RSS; that is the number the runner limit is
# measured against, and it is the same figure on every platform.
PEAK_RSS="$(grep -oE 'Peak RSS: [0-9.]+GB' "$BUILD_LOG" | tail -1 || true)"

# --- 3. run the checks ------------------------------------------------------
echo "--- running P0 checks"
"$BIN"
CHECKS_EXIT=$?

# --- 4. startup budget (V-14) ----------------------------------------------
# Two measurements, because an absolute number here measures the machine, not
# the binary: a dev laptop spawns this process in ~5ms, a 3-vCPU CI runner in
# ~200ms. Only the DIFFERENCE is attributable to our code, so that is what
# gets gated; the absolutes are reported for context.
echo "--- measuring cold start over 50 runs (noop baseline + parse)"
read -r NOOP_MS PARSE_MS DELTA_MS <<<"$(python3 - "$BIN" <<'PY'
import subprocess, sys, time

binary, n = sys.argv[1], 50

def once(flag):
    start = time.perf_counter()
    subprocess.run([binary, flag], capture_output=True)
    return (time.perf_counter() - start) * 1000

# Interleaved, and scored on the MINIMUM rather than the mean. Both matter on a
# shared runner: interleaving means a slow patch hits each variant equally
# instead of whichever ran during it, and the minimum approximates the true cost
# because scheduler noise can only ever make a run slower, never faster. A mean
# of 50-then-50 let host drift leak straight into the difference being gated.
for f in ("--bench-noop", "--bench-parse"):
    once(f)  # warm the page cache

noops, parses = [], []
for _ in range(n):
    noops.append(once("--bench-noop"))
    parses.append(once("--bench-parse"))

noop, parse = min(noops), min(parses)
print(f"{noop:.1f} {parse:.1f} {max(parse - noop, 0.0):.1f}")
PY
)"

# --- 5. report --------------------------------------------------------------
BUILD_S=$(python3 -c "print(f'{$BUILD_END - $BUILD_START:.0f}')")
SIZE=$(ls -lh "$BIN" | awk '{print $5}')

echo
echo "=== P0 result: $(uname -s) $(uname -m) ==="
echo "  binary          $SIZE"
echo "  build time      ${BUILD_S}s"
echo "  ${PEAK_RSS:-peak RSS  not reported}   (adaptive — native-image sizes its heap to available RAM)"
echo "  spawn baseline  ${NOOP_MS}ms   (the machine's process-spawn tax, not ours)"
echo "  parse-only      ${PARSE_MS}ms"
echo "  parse cost      ${DELTA_MS}ms (budget ${STARTUP_BUDGET_MS}ms)  <- the gated number"

# The checks program already exited non-zero on failure; the parse-cost budget
# is the one gate this script adds.
python3 -c "
import sys
ok = $DELTA_MS <= $STARTUP_BUDGET_MS
print('  verdict         ' + ('PASS' if ok else 'FAIL — parse cost over the V-14 budget'))
sys.exit(0 if ok else 1)
"
