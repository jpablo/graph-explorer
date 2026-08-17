#!/usr/bin/env bash
#
# Build the `gx` binary: Scala, compiled ahead of time by GraalVM native-image.
#
# Replaces `cargo build` in gx/. Every non-obvious line below was paid for during
# the P0 gate (docs/desktop-gx-v2-architecture.md D2.1b) — see the comments.
#
# Usage: scripts/build-gx.sh [output-path]
# Requires: sbt, scala-cli (which fetches GraalVM itself)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT_DIR}/gx-cli/target/gx}"
CP_FILE="${ROOT_DIR}/target/gx-cli-classpath.txt"
BUILD_LOG="${ROOT_DIR}/target/gx-native-build.log"
LAUNCHER_DIR="${ROOT_DIR}/gx-cli/native"

# Pinned. scala-cli documents 22.3.1 as its default and resolved exactly that on
# a dev laptop while every CI runner resolved CE 17.0.9 — so "the same command"
# was two different compilers, and the numbers they produced were being compared
# as though it were one.
GRAALVM_VERSION="${GRAALVM_VERSION:-17.0.9}"

# Coursier installs `scala-cli.bat` on Windows, and git-bash resolves a bare
# command name to `.exe` but never to `.bat`.
SCALA_CLI=""
for candidate in scala-cli scala-cli.bat; do
  if command -v "$candidate" >/dev/null 2>&1; then SCALA_CLI="$candidate"; break; fi
done
[[ -n "${SCALA_CLI}" ]] || { echo "scala-cli not found (tried scala-cli, scala-cli.bat)" >&2; exit 1; }

echo "--- resolving gx-cli classpath"
(cd "${ROOT_DIR}" && sbt -batch "gxCli/nativeImageClasspath")
[[ -s "${CP_FILE}" ]] || { echo "empty classpath file: ${CP_FILE}" >&2; exit 1; }

# Windows paths contain ':' (C:\...), so the separator cannot be assumed.
case "$(cat "${CP_FILE}")" in
  *";"*) SEP=";" ;;
  *)     SEP=":" ;;
esac

# `|| [ -n "$entry" ]` is load-bearing: the file has no trailing newline, and a
# bare `read` discards a final unterminated line. Dropping the last jar surfaces
# as a TASTy unpickling crash inside the compiler, not as a missing class.
JAR_ARGS=()
while IFS= read -r entry || [ -n "$entry" ]; do
  [[ -n "${entry}" ]] && JAR_ARGS+=(--jar "${entry}")
done < <(tr "${SEP}" '\n' < "${CP_FILE}")

EXPECTED=$(tr "${SEP}" '\n' < "${CP_FILE}" | grep -c .)
[[ $((${#JAR_ARGS[@]} / 2)) -eq "${EXPECTED}" ]] || {
  echo "passed $((${#JAR_ARGS[@]} / 2)) jars but the file lists ${EXPECTED}" >&2; exit 1; }

echo "--- building native image (GraalVM ${GRAALVM_VERSION})"
mkdir -p "$(dirname "${OUT}")" "${ROOT_DIR}/target"

# Force the rebuild.
#
# The --jar entries are sbt's CLASS DIRECTORIES, and scala-cli's build cache
# keys on Launch.scala plus the option list — the directory PATHS, not their
# contents. So a change confined to gx-cli or gx-core leaves every input string
# identical, the cache hits, and scala-cli reports "Wrote .../gx" over a binary
# it did not rebuild. It happened during P5: `gx open` kept answering "the
# control channel lands in P5" against a desktop that was already serving the
# socket, and only the binary's mtime gave it away.
#
# This is the same failure the release script's `touch main.rs` exists for
# (cargo will not re-embed a changed dist/ because no Rust source moved), and it
# is worth the second or two: a `gx` that silently does not contain your change
# is indistinguishable from a change that did not work.
rm -rf "${LAUNCHER_DIR}/.scala-build"

# --no-fallback is not optional. Without it, native-image answers un-configured
# reflection (scala.Enumeration, reachable through the parser) by silently
# emitting a FALLBACK IMAGE: a JVM launcher wearing the output filename. It runs,
# it behaves correctly, and it starts ~25x slower. Three CI runs measured one
# before anyone noticed.
(cd "${LAUNCHER_DIR}" && "${SCALA_CLI}" --power package Launch.scala \
  --scala 3.7.1 -O -experimental \
  "${JAR_ARGS[@]}" \
  --native-image --graalvm-version "${GRAALVM_VERSION}" \
  --graalvm-args --no-fallback \
  -o "${OUT}" -f) 2>&1 | tee "${BUILD_LOG}"

if grep -qiE "Generating fallback image|Aborting stand-alone image build" "${BUILD_LOG}"; then
  echo "FAIL: native-image fell back to a JVM launcher instead of a native binary." >&2
  grep -iE "Aborting stand-alone|reflection use without config" "${BUILD_LOG}" | head -3 >&2
  exit 1
fi

BIN="${OUT}"
[[ -x "${BIN}" ]] || BIN="${OUT}.exe"
[[ -x "${BIN}" ]] || { echo "no binary produced at ${OUT}" >&2; exit 1; }

echo "--- built ${BIN} ($(ls -lh "${BIN}" | awk '{print $5}'))"
"${BIN}" --version
