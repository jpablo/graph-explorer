// Entry point for the native-image build.
//
// scala-cli packages *sources*, so this one-liner gives it something to compile
// while the actual CLI arrives on the classpath from `sbt gxCli/...`. Keeping it
// here rather than generating it in the build script means the thing being
// compiled is in the repository and reviewable.
@main def gx(args: String*): Unit =
  org.jpablo.graphexplorer.gx.Main.main(args.toArray)
