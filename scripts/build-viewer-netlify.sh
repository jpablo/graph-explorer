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

sdk install sbt 1.9.7
sdk use sbt 1.9.7

curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
chmod +x cs

./cs install stc
export PATH="$PATH:/opt/buildhome/.local/share/coursier/bin"
stc --ignoredLibs node @codemirror/view @codemirror/lang-javascript @viz-js/viz @codemirror/commands jsdom @viz-js/lang-dot codemirror


git fetch --force --unshallow --tags || git fetch --force --depth=10000 --tags

# -- build viewer

sbt "viewer/fullLinkJS"
npm run build
