

# UI
# Compile ScalaJs code to JS
sbt "viewer/fullLinkJS"

pwd # /home/runner/work/type-explorer/type-explorer

ls -l /home/runner/work/graph-explorer/graph-explorer/viewer/target/scala-3.5.0/ui-opt

# Bundle JS code with Vite
npm run build

# Server

sbt "backend/Universal/packageBin"

# https://www.scala-sbt.org/sbt-native-packager/gettingstarted.html
# sbt "backend/stage"
# backend/target/universal/stage/bin/type-explorer-backend
