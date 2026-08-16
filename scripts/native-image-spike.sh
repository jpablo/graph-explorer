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
# --bench-parse, not the full suite: V-14 is about a parse-only command, and the
# suite's three fsyncs and symlink work are not part of what it bounds.
echo "--- measuring parse-only cold start over 50 runs"
STARTUP_MS=$(python3 - "$BIN" <<'PY'
import subprocess, sys, time
binary, n = sys.argv[1], 50
cmd = [binary, "--bench-parse"]
subprocess.run(cmd, capture_output=True)  # warm the page cache
start = time.time()
for _ in range(n):
    subprocess.run(cmd, capture_output=True)
print(f"{(time.time() - start) * 1000 / n:.1f}")
PY
)

# --- 5. report --------------------------------------------------------------
BUILD_S=$(python3 -c "print(f'{$BUILD_END - $BUILD_START:.0f}')")
SIZE=$(ls -lh "$BIN" | awk '{print $5}')

echo
echo "=== P0 result: $(uname -s) $(uname -m) ==="
echo "  binary          $SIZE"
echo "  build time      ${BUILD_S}s"
echo "  ${PEAK_RSS:-peak RSS  not reported}"
echo "  cold start      ${STARTUP_MS}ms (budget ${STARTUP_BUDGET_MS}ms)"

# The checks program already exited non-zero on failure; the budget is the one
# gate this script adds. Note it measures the full spike, which does more I/O
# than a real parse-only command, so it is a conservative bound.
python3 -c "
import sys
ok = $STARTUP_MS <= $STARTUP_BUDGET_MS
print('  verdict         ' + ('PASS' if ok else 'FAIL — over the V-14 budget'))
sys.exit(0 if ok else 1)
"
