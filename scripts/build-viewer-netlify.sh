# --- dependencies
curl -s "https://get.sdkman.io" | bash

source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install java 11.0.20-tem
sdk use java 11.0.20-tem

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
