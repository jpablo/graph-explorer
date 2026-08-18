#!/usr/bin/env bash
#
# Notarize (and staple) a code-signed macOS artifact that Tauri's bundler does
# NOT notarize for us.
#
# What Tauri does on its own (crates/tauri-bundler/.../macos/app.rs):
#   - signs the .app and its frameworks,
#   - notarizes the .app and staples the ticket into the .app.
# What Tauri does NOT do:
#   - it does not notarize or staple the .dmg (it only codesigns it --
#     tauri-apps/tauri#7533). The DMG is the artifact a user actually
#     downloads, so it must be notarized + stapled itself, or Gatekeeper
#     treats the mounted app as unverified.
#   - it does not touch `gx` at all: `gx` is a standalone native binary, not a
#     Tauri sidecar (no bundle.externalBin in tauri.conf.json), so Tauri's
#     signing path never sees it.
#
# This script closes both gaps. It zips the artifact, submits the zip to the
# Apple notary service, waits, and staples the resulting ticket back onto the
# artifact. Two flags cover the two call sites:
#   --no-codesign  the .dmg is already codesigned by Tauri; skip re-signing.
#   --no-staple    raw Mach-O binaries (gx): stapling is NOT supported for them
#                  ("Stapling is not supported for mach-O binaries", Apple),
#                  so submit + wait and stop. Gatekeeper validates a signed,
#                  notarized binary online at first launch, which is the
#                  standard flow for standalone CLI tools.
#
# Requires the same environment the Tauri build uses for notarization, which
# release-binaries.yml already exports from secrets:
#   APPLE_ID / APPLE_PASSWORD / APPLE_TEAM_ID        (Apple ID + app password)
# or
#   APPLE_API_KEY (Key ID) / APPLE_API_ISSUER / APPLE_API_KEY_PATH (path to .p8)
#
# Usage:
#   scripts/macos-notarize-staple.sh [--no-codesign] [--no-staple] <identity> <path>
#     <identity>  "Developer ID Application: Name (TEAMID)"
#     <path>      a Mach-O binary, an .app, or a .dmg
#
# Notarization is all-or-nothing: any failure exits nonzero, so a CI job runs
# red rather than publish a half-notarized asset.

set -euo pipefail

NO_CODESIGN=0
NO_STAPLE=0
for flag in "$@"; do
  case "${flag}" in
    --no-codesign) NO_CODESIGN=1 ;;
    --no-staple) NO_STAPLE=1 ;;
  esac
done
ARGS=()
for a in "$@"; do
  case "${a}" in --no-codesign|--no-staple) ;; *) ARGS+=("${a}") ;; esac
done
[[ ${#ARGS[@]} -eq 2 ]] || { echo "usage: $0 [--no-codesign] [--no-staple] <identity> <path>" >&2; exit 2; }
IDENTITY="${ARGS[0]}"
TARGET="${ARGS[1]}"
[[ -e "${TARGET}" ]] || { echo "no such file: ${TARGET}" >&2; exit 1; }

# notarytool auth, mirroring tauri-macos-sign's NotarytoolCmdExt:
# Apple ID triple, or an App Store Connect API key (path to the .p8).
if [[ -n "${APPLE_API_KEY:-}" && -n "${APPLE_API_ISSUER:-}" ]]; then
  [[ -n "${APPLE_API_KEY_PATH:-}" && -f "${APPLE_API_KEY_PATH}" ]] || {
    echo "APPLE_API_KEY set but APPLE_API_KEY_PATH is missing or not a file" >&2
    exit 1
  }
  NOTARY_AUTH=(--key-id "${APPLE_API_KEY}" --key "${APPLE_API_KEY_PATH}" --issuer "${APPLE_API_ISSUER}")
elif [[ -n "${APPLE_ID:-}" && -n "${APPLE_PASSWORD:-}" && -n "${APPLE_TEAM_ID:-}" ]]; then
  # The password is an app-specific password from the secret; passing it as an
  # argument is what tauri-macos-sign does. CI jobs are ephemeral and the
  # process list is not world-readable, so this is acceptable here.
  NOTARY_AUTH=(--apple-id "${APPLE_ID}" --password "${APPLE_PASSWORD}" --team-id "${APPLE_TEAM_ID}")
else
  echo "no notarization credentials (need APPLE_ID/APPLE_PASSWORD/APPLE_TEAM_ID or APPLE_API_KEY/APPLE_API_ISSUER/APPLE_API_KEY_PATH)" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
ZIP="${WORK}/$(basename "${TARGET}").zip"

# 1. Sign the artifact (unless it is already signed, e.g. the .dmg from Tauri).
# --options runtime (hardened runtime) is required for notarization.
if [[ "${NO_CODESIGN}" -eq 0 ]]; then
  echo "--- codesigning ${TARGET} as ${IDENTITY}"
  codesign --force --options runtime --sign "${IDENTITY}" "${TARGET}"
fi

# 2. Zip it for submission. ditto with --keepParent --sequesterRsrc is the
#    layout the notary service expects (same flags tauri-macos-sign uses); a
#    plain `zip` loses the resource-fork handling Apple's check relies on.
echo "--- zipping $(basename "${TARGET}")"
ditto -c -k --keepParent --sequesterRsrc "${TARGET}" "${ZIP}"

# 3. Sign the zip container with the same identity. No --options runtime here:
#    hardened runtime only applies to the executables inside (which carry it
#    already -- Tauri signs the .app's binaries with it), not to the zip
#    container. This matches tauri-macos-sign, which signs its submission zip
#    with hardened_runtime=false.
codesign --force --sign "${IDENTITY}" "${ZIP}"

# 4. Submit and wait. --wait polls until Accepted/Rejected; a Rejected
#    submission makes notarytool exit nonzero, which trips set -e.
echo "--- submitting to the notary service"
xcrun notarytool submit "${ZIP}" "${NOTARY_AUTH[@]}" \
  --output-format json --wait
echo "--- notarization accepted"

# 5. Staple the ticket so Gatekeeper can validate offline (no network call at
#    open time). stapler works on .app bundles and .dmg images, but NOT on raw
#    Mach-O binaries -- for those (pass --no-staple) the notarization record
#    is enough: a signed, notarized binary is validated online at first
#    launch, which is the standard flow for standalone CLI tools.
if [[ "${NO_STAPLE}" -eq 0 ]]; then
  xcrun stapler staple "${TARGET}"
  xcrun stapler validate "${TARGET}"
  echo "--- done: $(basename "${TARGET}") is signed, notarized, and stapled"
else
  echo "--- done: $(basename "${TARGET}") is signed and notarized (no staple: not supported for this format)"
fi
