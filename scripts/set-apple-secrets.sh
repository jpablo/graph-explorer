#!/usr/bin/env bash
#
# One-time setup for the six macOS release-signing secrets that
# .github/workflows/release-binaries.yml reads. docs/macos-release-signing.md
# is the surrounding story; this script is step 4 of it.
#
# WHY A SCRIPT RATHER THAN SIX PASTES INTO THE GitHub UI: a GitHub secret is
# write-only once stored. Nothing can read it back to check it, so a typo in
# any of the six stays invisible until a tag build reaches the signing path --
# and that path is near the END of a ~20 minute macOS job, so the feedback loop
# on a one-character mistake is a whole release cycle that ends with the macOS
# assets withheld. Every check below is therefore done LOCALLY first:
#
#   - the password actually opens the .p12
#   - the .p12 actually contains the PRIVATE KEY (exporting the certificate
#     alone from Keychain Access is the single most common mistake here, and it
#     produces a file that imports "successfully" in CI while yielding no
#     signing identity at all)
#   - the certificate is a *Developer ID Application* one -- not the "Apple
#     Development" certificate Xcode installs, which cannot sign a release
#   - the certificate has not expired
#   - the Team ID is READ OUT OF the certificate rather than retyped, which
#     removes the mismatch class entirely
#   - the Apple ID + app-specific password authenticate against the real notary
#     service
#
# Nothing is sent to GitHub until every one of those passes. Run --dry-run
# first: it does the whole validation and stops before writing anything.
#
# SECRET HYGIENE. Passwords are read with `read -rs` (never echoed) and handed
# to openssl and gh over stdin, so they stay out of argv and out of your shell
# history. The one exception is `xcrun notarytool`, which accepts a password
# only as an argument -- for the few seconds that call runs, the password is
# visible in `ps` to other processes on this machine (verified: macOS does show
# other users' argv). On a personal Mac that is a small price for catching a
# bad credential before release day, but --no-notary-check skips it and prints
# the equivalent command using notarytool's own secure prompt instead.
#
# Nothing is written to disk except the certificate -- which is public -- into
# a mode-700 temp dir that is removed on exit.
#
# Usage:
#   scripts/set-apple-secrets.sh [--dry-run] [--no-notary-check]
#                                [--repo OWNER/REPO] <DeveloperID.p12>

set -euo pipefail

DRY_RUN=0
NOTARY_CHECK=1
REPO=""
P12=""

usage() {
  sed -n '/^# Usage:/,/p12>/p' "$0" | sed 's/^# \{0,1\}//'
}

fail() { printf '\n  FAIL: %s\n' "$*" >&2; exit 1; }
ok()   { printf '  ok    %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; }
step() { printf '\n%s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)         DRY_RUN=1; shift ;;
    --no-notary-check) NOTARY_CHECK=0; shift ;;
    --repo)            REPO="${2:-}"; shift 2 ;;
    -h|--help)         usage; exit 0 ;;
    -*)                echo "unknown flag: $1" >&2; usage >&2; exit 2 ;;
    *)                 P12="$1"; shift ;;
  esac
done

[[ -n "${P12}" ]] || { usage >&2; exit 2; }
[[ -f "${P12}" ]] || fail "no such file: ${P12}"

command -v gh >/dev/null 2>&1 || fail "the gh CLI is not installed"
gh auth status >/dev/null 2>&1 || fail "gh is not authenticated -- run: gh auth login"
command -v xcrun >/dev/null 2>&1 || fail "xcrun not found -- install the Xcode command line tools"

WORK="$(mktemp -d)"
chmod 700 "${WORK}"
trap 'rm -rf "${WORK}"' EXIT

# ---------------------------------------------------------------- certificate

step "Certificate (${P12})"

printf '  .p12 export password: '
read -rs P12_PASSWORD
printf '\n'
[[ -n "${P12_PASSWORD}" ]] || fail "empty password"

# Keychain Access exports .p12 files with legacy ciphers (RC2-40-CBC). The
# LibreSSL at /usr/bin/openssl reads those natively; OpenSSL 3 -- frequently
# first on PATH via Homebrew -- refuses them unless -legacy is passed, and the
# error it gives ("unsupported ... RC2-40-CBC") looks nothing like the wrong
# password it is not. Probe for a combination that works before concluding
# anything about the password.
OPENSSL_BIN=""
USE_LEGACY=0
probe() {
  if [[ "$2" -eq 1 ]]; then
    printf '%s' "${P12_PASSWORD}" | "$1" pkcs12 -in "${P12}" -passin stdin -noout -legacy >/dev/null 2>&1
  else
    printf '%s' "${P12_PASSWORD}" | "$1" pkcs12 -in "${P12}" -passin stdin -noout >/dev/null 2>&1
  fi
}
for cand in /usr/bin/openssl openssl; do
  command -v "${cand}" >/dev/null 2>&1 || continue
  if probe "${cand}" 0; then OPENSSL_BIN="${cand}"; USE_LEGACY=0; break; fi
  if probe "${cand}" 1; then OPENSSL_BIN="${cand}"; USE_LEGACY=1; break; fi
done
[[ -n "${OPENSSL_BIN}" ]] || fail \
  "could not open ${P12} with that password.
         If the password is definitely right, the file may be corrupt or in a
         format neither LibreSSL nor OpenSSL here can read."
ok "password opens the .p12  (via ${OPENSSL_BIN}$([[ ${USE_LEGACY} -eq 1 ]] && echo ' -legacy'))"

ossl_p12() {
  if [[ "${USE_LEGACY}" -eq 1 ]]; then
    "${OPENSSL_BIN}" pkcs12 -legacy "$@"
  else
    "${OPENSSL_BIN}" pkcs12 "$@"
  fi
}

# The private key stays in memory -- captured into a variable, never written to
# disk. Captured rather than piped into `grep -q` on purpose: grep -q exits at
# the first match, which SIGPIPEs openssl, and `set -o pipefail` would then
# report 141 for the pipeline -- turning a perfectly good .p12 into a spurious
# "no private key". It only ever passed testing because a 2048-bit key fits in
# the pipe buffer, so openssl finished before grep could exit.
KEY_PROBE="$(printf '%s' "${P12_PASSWORD}" \
  | ossl_p12 -in "${P12}" -passin stdin -nocerts -nodes 2>/dev/null || true)"
case "${KEY_PROBE}" in
  *"PRIVATE KEY"*) : ;;
  *)
  fail "this .p12 has NO PRIVATE KEY in it.
         In Keychain Access you exported the certificate on its own. Expand the
         certificate's disclosure triangle, select the certificate AND the
         private key underneath it, then File > Export Items again.
         (CI would import this file without complaint and then find no signing
         identity at all.)" ;;
esac
unset KEY_PROBE
ok "contains a private key"

CERT_PEM="${WORK}/cert.pem"
printf '%s' "${P12_PASSWORD}" \
  | ossl_p12 -in "${P12}" -passin stdin -clcerts -nokeys 2>/dev/null > "${CERT_PEM}"
[[ -s "${CERT_PEM}" ]] || fail "could not extract a certificate from ${P12}"

# LibreSSL prints long RDN names (commonName), OpenSSL 3 prints short ones (CN),
# so match either. -E is mandatory here: BSD sed has no \| alternation in a
# basic regex and silently matches nothing rather than erroring.
SUBJECT_LINES="$("${OPENSSL_BIN}" x509 -in "${CERT_PEM}" -noout -subject \
  -nameopt multiline 2>/dev/null || true)"
subject_field() {
  # First match only, taken with parameter expansion rather than `| head -n1`:
  # head exits early and SIGPIPEs sed, which pipefail would surface as failure.
  local out
  out="$(printf '%s\n' "${SUBJECT_LINES}" \
    | sed -n -E "s/^[[:space:]]*($1|$2)[[:space:]]*=[[:space:]]*//p")"
  printf '%s' "${out%%$'\n'*}"
}
CN="$(subject_field commonName CN)"
OU="$(subject_field organizationalUnitName OU)"

case "${CN}" in
  "Developer ID Application: "*) ok "is a Developer ID Application certificate" ;;
  "") fail "could not read the certificate's common name" ;;
  *)  fail "this is NOT a Developer ID Application certificate.
         common name: ${CN}
         Only a 'Developer ID Application' certificate can sign software for
         distribution outside the App Store. An 'Apple Development' or 'Apple
         Distribution' certificate cannot, and needs a paid Developer account
         to create -- see docs/macos-release-signing.md sections 2 and 3." ;;
esac

TEAM_ID="${OU}"
if [[ -z "${TEAM_ID}" ]]; then
  TEAM_ID="$(printf '%s' "${CN}" | sed -n 's/.*(\([A-Z0-9]\{10\}\))$/\1/p')"
fi
[[ "${TEAM_ID}" =~ ^[A-Z0-9]{10}$ ]] || fail \
  "could not read a 10-character Team ID out of the certificate (got: '${TEAM_ID}')"
ok "Team ID read from the certificate: ${TEAM_ID}"

if ! "${OPENSSL_BIN}" x509 -in "${CERT_PEM}" -noout -checkend 0 >/dev/null 2>&1; then
  fail "this certificate has EXPIRED ($("${OPENSSL_BIN}" x509 -in "${CERT_PEM}" -noout -enddate | sed 's/^notAfter=//'))"
fi
NOT_AFTER="$("${OPENSSL_BIN}" x509 -in "${CERT_PEM}" -noout -enddate | sed 's/^notAfter=//')"
if ! "${OPENSSL_BIN}" x509 -in "${CERT_PEM}" -noout -checkend 2592000 >/dev/null 2>&1; then
  warn "expires within 30 days (${NOT_AFTER}) -- reissue before the next release"
else
  ok "valid until ${NOT_AFTER}"
fi

# The identity string CI will derive from this certificate after import. The
# workflow greps `security find-identity` for 'Developer ID Application' and
# takes the quoted name, which is exactly this common name.
ok "CI will sign as: ${CN}"

# ------------------------------------------------------------ notary identity

step "Notary credentials"

printf '  Apple ID (the email on the paid Developer account): '
read -r APPLE_ID_VALUE
[[ -n "${APPLE_ID_VALUE}" ]] || fail "empty Apple ID"

printf '  App-specific password (appleid.apple.com > Sign-In and Security): '
read -rs APPLE_PASSWORD_VALUE
printf '\n'
[[ -n "${APPLE_PASSWORD_VALUE}" ]] || fail "empty app-specific password"

# An app-specific password is xxxx-xxxx-xxxx-xxxx. The account password is not,
# and is the thing people paste here by mistake.
if [[ ! "${APPLE_PASSWORD_VALUE}" =~ ^[a-z]{4}-[a-z]{4}-[a-z]{4}-[a-z]{4}$ ]]; then
  warn "that does not look like an app-specific password (xxxx-xxxx-xxxx-xxxx).
        Your normal Apple account password will NOT work for notarization."
fi

if [[ "${NOTARY_CHECK}" -eq 1 ]]; then
  if xcrun notarytool history \
       --apple-id "${APPLE_ID_VALUE}" \
       --password "${APPLE_PASSWORD_VALUE}" \
       --team-id "${TEAM_ID}" \
       --output-format json >/dev/null 2>"${WORK}/notary.err"; then
    ok "authenticated against the Apple notary service"
  else
    fail "the notary service rejected these credentials:
         $(sed 's/^/         /' "${WORK}/notary.err" | head -n 6)
         The Team ID (${TEAM_ID}) came from the certificate, so a mismatch here
         means the Apple ID or the app-specific password is wrong, or that
         Apple ID is not a member of team ${TEAM_ID}."
  fi
else
  warn "skipped the notary check. To run it yourself with a secure prompt:"
  printf '        xcrun notarytool history --apple-id %s --team-id %s\n' \
    "${APPLE_ID_VALUE}" "${TEAM_ID}"
fi

# ------------------------------------------------------------------- keychain

# Only ever used to lock/unlock the throwaway keychain inside a CI job, so a
# generated value is strictly better than a memorable one. The workflow falls
# back to a hardcoded default when this secret is absent; setting it removes
# that default.
# (openssl rand, not `tr -dc < /dev/urandom | head -c`: head exits early there
# and SIGPIPEs tr, which under `set -o pipefail` aborts the script at 141.)
KEYCHAIN_PASSWORD_VALUE="$("${OPENSSL_BIN}" rand -hex 20)"

# --------------------------------------------------------------------- upload

if [[ "${DRY_RUN}" -eq 1 ]]; then
  step "Dry run: every check passed. Nothing was sent to GitHub."
  echo "  Re-run without --dry-run to set the six secrets."
  exit 0
fi

TARGET_REPO="${REPO}"
if [[ -z "${TARGET_REPO}" ]]; then
  TARGET_REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
fi

step "Setting six secrets on ${TARGET_REPO}"

set_secret() {
  # value on stdin, so it never appears in argv or shell history
  if [[ -n "${REPO}" ]]; then
    gh secret set "$1" --repo "${REPO}" >/dev/null
  else
    gh secret set "$1" >/dev/null
  fi
  ok "$1"
}

base64 -i "${P12}"                        | set_secret APPLE_CERTIFICATE
printf '%s' "${P12_PASSWORD}"             | set_secret APPLE_CERTIFICATE_PASSWORD
printf '%s' "${APPLE_ID_VALUE}"           | set_secret APPLE_ID
printf '%s' "${APPLE_PASSWORD_VALUE}"     | set_secret APPLE_PASSWORD
printf '%s' "${TEAM_ID}"                  | set_secret APPLE_TEAM_ID
printf '%s' "${KEYCHAIN_PASSWORD_VALUE}"  | set_secret KEYCHAIN_PASSWORD

step "Done."
cat <<EOF
  The next tag build will sign and notarize. To exercise it on the current
  release without cutting a new version:

      gh workflow run release-binaries.yml -f tag=\$(git describe --tags --abbrev=0)

  The macOS job now runs "Verify signature and notarization" as a publish
  gate: if signing or notarization fails, the macOS assets are withheld
  rather than published half-signed.

  docs/macos-release-signing.md section 5 is the end-to-end proof -- download
  the DMG and open it, which is the only test of the property that actually
  matters to a user.
EOF
