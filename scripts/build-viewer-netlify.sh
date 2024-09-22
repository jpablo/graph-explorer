# --- dependencies
curl -s "https://get.sdkman.io" | bash

source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install java 11.0.20-tem
sdk use java 11.0.20-tem

sdk install sbt 1.9.7
sdk use sbt 1.9.7

# -- build viewer

sbt "viewer/fullLinkJS"
npm run build
