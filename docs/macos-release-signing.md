# macOS release signing and notarization

Why this exists: the `gx` / desktop assets published for `v0.7.0` carry no
Apple Developer ID signature and no notarization ticket. Gatekeeper's
"could not be opened because Apple cannot check it for malicious software"
wall is what users hit on a downloaded, quarantined copy — and the
`xattr -dr com.apple.quarantine` workaround people use is a real one, but
it is a workaround, and it is exactly the thing that should not be needed.

This page is the one-time setup that turns the release workflow from
"build and attach" into "build, sign, notarize, staple, verify, attach".
Until the secrets in §4 exist, the workflow behaves exactly as it always
has (ad-hoc signature, no notarization) — the signing steps are all
gated on the secrets, so there is nothing to switch on.

## 1. What the workflow does when the secrets are present

`release-binaries.yml`, macOS leg:

1. **Set app version from the release tag** — writes `vX.Y.Z` (minus the `v`)
   into `desktop/src-tauri/tauri.conf.json` and `desktop/src-tauri/Cargo.toml`
   so the binary, the `.app`'s `CFBundleShortVersionString`, and the DMG
   filename agree. (`v0.7.0` shipped with the DMG named `v0.7.0` and the
   app inside reporting `0.1.0`.)
2. **Import Apple Developer certificate** — decodes the base64 `.p12`
   secret into a `build.keychain` and remembers the identity name in
   `CERT_ID`. Fails the job if no `Developer ID Application` identity
   materializes after import.
3. **Build desktop bundle** — exports `APPLE_SIGNING_IDENTITY` (the cert,
   or `-` for ad-hoc when the secret is absent). The Tauri bundler signs
   the `.app` and the `.dmg` with it, and because `APPLE_ID` /
   `APPLE_PASSWORD` / `APPLE_TEAM_ID` are also set, it **notarizes the
   `.app` and staples its ticket**.
4. *(the smoke gates run here — they launch the raw
   `target/release` binary, not the re-signed `.app`, so re-signing below
   cannot disturb them)*
5. **Notarize and staple DMG and gx** — `scripts/macos-notarize-staple.sh`
   closes the two gaps Tauri leaves:
   - **`.dmg`**: Tauri codesigns it but does *not* notarize it
     ([tauri-apps/tauri#7533](https://github.com/tauri-apps/tauri/issues/7533)).
     The DMG is what users download, so it gets submitted to the notary
     service and the ticket stapled.
   - **`gx`**: a standalone native binary, not a Tauri sidecar (no
     `externalBin` in `tauri.conf.json`), so Tauri never touches it. It is
     signed with the same identity and submitted for notarization. (Stapling
     is *not supported* for raw Mach-O binaries — a signed + notarized
     binary is validated online at first launch, which is the standard flow
     for standalone CLI tools.)
6. **Verify signature and notarization** (publish gate) —
   `codesign --verify --deep --strict` on the `.app` and `gx`, asserts the
   authority is `Developer ID Application`, and `xcrun stapler validate`
   on the `.dmg` and the `.app`. Failing here **withholds the macOS assets**
   rather than publish them half-signed.

`publish-checksums` (all platforms) then downloads the attached assets and
publishes a `SHA256SUMS` for them — `v0.7.0` shipped with no published
checksum at all.

## 2. Prerequisites (one-time, ~30 minutes)

1. **A paid Apple Developer account** ($99/yr). Free accounts do not get a
   *Developer ID* certificate and cannot submit to the notary service —
   the `Apple Development` certificates already on both machines are free
   / Xcode development certs and will not work for this.
2. **A macOS machine with the latest Xcode + command line tools** (this
   Mac is fine). `Developer ID` certs are only issued to macOS; there is
   no Linux path.
3. **The Team ID** — the 10-character ID shown at
   <https://developer.apple.com/account> → Membership.

## 3. Create the certificate

Open the **Apple Developer app** (or the Certificates page at
<https://developer.apple.com/account/resources/certificates/list>) →
*Certificates* → **+** → **Developer ID Application** → generate and
*Download*. You get a `.p12` (`Developer ID Application - <name>.p12`) and
a password. Keep both; the cert expires in ~1 year, and a re-issue is a
10-minute job.

## 4. Create the GitHub secrets

Repo → *Settings* → *Secrets and variables* → *Actions*:

| Secret                       | Value                                                                 |
|------------------------------|-----------------------------------------------------------------------|
| `APPLE_CERTIFICATE`          | `base64 -i DeveloperID.p12` (the whole base64 blob, one line)         |
| `APPLE_CERTIFICATE_PASSWORD` | the `.p12` export password from §3                                    |
| `APPLE_ID`                   | your Apple ID email (the one on the paid account)                      |
| `APPLE_PASSWORD`             | an **app-specific password** (appleid.apple.com → Sign-In and Security → App-Specific Passwords) — not the account password |
| `APPLE_TEAM_ID`              | the 10-character Team ID from §2                                      |
| `KEYCHAIN_PASSWORD` *(optional)* | any strong string; defaults to `graph-explorer-ci` if unset       |

Alternative to `APPLE_ID`/`APPLE_PASSWORD`: an App Store Connect API key
(`APPLE_API_KEY` = the Key ID, `APPLE_API_ISSUER`, and the downloaded
`.p8` — for the standalone script its path goes in `APPLE_API_KEY_PATH`)
also works for notarization, but the Apple ID pair is the least moving
parts for a personal repo.

After the secrets exist, the next tag-triggered release (or a manual
dispatch of `Release binaries` against an existing tag) will sign and
notarize. The verify step will withhold the macOS assets if any of it
fails — a red build beats a half-signed release.

## 5. Proving it actually works

The point of the pipeline is the *user* experience, not the CI log. After
a signed release:

```sh
curl -LO https://github.com/jpablo/graph-explorer/releases/download/vX.Y.Z/graph-explorer-desktop-vX.Y.Z-macos.dmg
shasum -a 256 graph-explorer-desktop-vX.Y.Z-macos.dmg   # match the SHA256SUMS asset
# download leaves the quarantine xattr in place; that is the test.
open graph-explorer-desktop-vX.Y.Z-macos.dmg
# ... double-click the .app. It should launch with no warning and no
# Privacy & Security visit. If a warning appears:
spctl -a -vvv -t install /Applications/Graph\ Explorer.app   # after moving it
xcrun stapler verify /path/to/Graph\ Explorer.app
```

And the standalone `gx` binary:

```sh
curl -LO .../gx-vX.Y.Z-macos
chmod +x gx-vX.Y.Z-macos
codesign -dv gx-vX.Y.Z-macos        # Authority = Developer ID Application ...
./gx-vX.Y.Z-macos --version         # first launch does the online
                                    # notarization check (the binary cannot
                                    # carry a stapled ticket); afterwards it
                                    # opens with no warning
```

## 6. Current state (v0.7.0-era assets)

Until a signed release exists, the honest guidance for the published
assets is what it always has been:

```sh
xattr -dr com.apple.quarantine Graph\ Explorer.app   # then launch
```

...plus the SHA256SUMS check once it is published. That guidance stays
valid until the secrets above are in place; it is not a bug to patch
around, it is the consequence of the cert being absent.
