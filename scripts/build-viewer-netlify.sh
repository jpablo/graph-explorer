# --- dependencies
curl -s "https://get.sdkman.io" | bash

source "$HOME/.sdkman/bin/sdkman-init.sh"

# JDK 17, matching every GitHub workflow (dev / release / release-binaries /
# smoke all pin java-version 17) — the site should be built the same way as
# the binaries we ship. NOTE sdkman PRUNES old patch releases: the previous
# pin, 11.0.20-tem, had stopped being offered, and since this script has no
# `set -e` both the install and the `use` failed silently and the build ran on
# whatever JDK the Netlify image happened to provide. Hence the explicit check
# below — a toolchain that did not install should fail loudly, here, rather
# than surface later as a mystery compiler crash.
sdk install java 17.0.20-tem
sdk use java 17.0.20-tem

java -version
if ! java -version 2>&1 | grep -q '"17\.'; then
  echo "ERROR: expected JDK 17, got the above. Has sdkman pruned 17.0.20-tem?" >&2
  echo "       Check: curl -s https://api.sdkman.io/2/candidates/java/linuxx64/versions/list?installed=" >&2
  exit 1
fi

# This installs the sbt *launcher* only; the launcher reads
# project/build.properties and downloads the sbt the build actually asks for
# (2.0.6). A 1.11.x launcher is new enough to bootstrap sbt 2.x — the previous
# 1.9.7 pin is not, and would die on the version handshake. Same pruning
# hazard as the JDK above, so check just as loudly.
sdk install sbt 1.11.4
sdk use sbt 1.11.4

# NOTE the subshell: `sbt --script-version` inside a project directory starts
# the thin client and blocks forever instead of printing a version. Ask from a
# scratch dir, where the launcher just answers and exits.
launcher_version="$(cd /tmp && sbt --script-version 2>&1)"
echo "sbt launcher: $launcher_version"
if ! echo "$launcher_version" | grep -q '^1\.11\.'; then
  echo "ERROR: expected the sbt 1.11.x launcher, got the above." >&2
  echo "       Has sdkman pruned 1.11.4? Any 1.11+ launcher will do; it only" >&2
  echo "       needs to be able to bootstrap the sbt 2.x in build.properties." >&2
  exit 1
fi

git fetch --force --unshallow --tags || git fetch --force --depth=10000 --tags

# -- build viewer

sbt "viewer/fullLinkJS"
npm run build
