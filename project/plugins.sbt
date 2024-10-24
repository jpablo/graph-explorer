addSbtPlugin("io.spray"                    % "sbt-revolver"             % "0.9.1")
addSbtPlugin("com.thesamet"                % "sbt-protoc"               % "1.0.6")
addSbtPlugin("org.portable-scala"          % "sbt-scalajs-crossproject" % "1.2.0")
addSbtPlugin("org.scala-js"                % "sbt-scalajs"              % "1.17.0")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter"            % "1.0.0-beta44")
addSbtPlugin("com.github.sbt"              % "sbt-native-packager"      % "1.9.9")
addSbtPlugin("org.scalameta"               % "sbt-scalafmt"             % "2.4.6")
addSbtPlugin("com.eed3si9n"                % "sbt-buildinfo"            % "0.11.0")
addSbtPlugin("com.github.sbt"              % "sbt-dynver"               % "5.1.0")

libraryDependencies += "com.thesamet.scalapb"  %% "compilerplugin"           % "0.11.17"
libraryDependencies += "org.scala-js"          %% "scalajs-env-jsdom-nodejs" % "1.1.0"
libraryDependencies += "io.github.gmkumar2005" %% "scala-js-env-playwright"  % "0.1.11"

addDependencyTreePlugin

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
