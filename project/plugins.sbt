addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.6.2")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"            % "0.13.1")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"               % "5.1.1")

// Playwright-backed JSEnv for viewer tests: upstream scala-js-env-playwright is
// published for sbt 1.x only (Scala 2.12), so a minimal port is vendored in
// project/PWEnv.scala. These are its runtime deps on the meta-build classpath.
libraryDependencies += "com.microsoft.playwright" % "playwright"    % "1.50.0"
libraryDependencies += "com.microsoft.playwright" % "driver-bundle" % "1.50.0"
