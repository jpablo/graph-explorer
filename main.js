import './style.css'
import './style.scss'
import './dotParser.js'
// @public is defined in UserConfig.resolve.alias.replacement: "@public -> replacementForPublic"
// replacementForPublic is defined in build.sbt
// It will be replaced by something like:
// $HOME/code/graph-explorer/viewer/target/scala-3.5.0/viewer-opt
import '@public/main.js'
