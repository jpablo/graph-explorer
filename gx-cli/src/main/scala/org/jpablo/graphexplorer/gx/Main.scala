package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit}
import org.jpablo.graphexplorer.gxcore.store.LibraryStore

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** The real process. Everything decidable lives in [[Cli]], which takes its
  * world as a parameter; this only supplies it.
  */
object Main:

  def main(argv: Array[String]): Unit =
    val home    = Paths.get(sys.props.getOrElse("user.home", "."))
    val runtime = home.resolve(".graph-explorer").resolve("runtime")

    val env = CliEnv(
      store = LibraryStore.default(home),
      policy = AccessPolicy.fromEnv(),
      audit = Audit(runtime.resolve("audit.log.jsonl")),
      // The user's shell, not the process's idea of it. v1 learned this the hard
      // way: the desktop's working directory is an artifact of how it was
      // launched, so paths must be resolved where the human typed them.
      cwd = Paths.get("").toAbsolutePath,
      out = println,
      err = System.err.println,
      stdin = () => String(System.in.readAllBytes(), StandardCharsets.UTF_8), // V-16
      now = () => System.currentTimeMillis(),
      desktopRunning = () => Main.desktopRunning(runtime.resolve("control.json"))
    )

    System.exit(Cli.run(argv.toVector, env))

  /** Is a desktop actually up?
    *
    * The runtime file outlives a crash, so its presence proves nothing — v1's
    * `gx status` reported "runtime file exists but the control API is not
    * reachable" precisely because it had to discover this by failing a request.
    * Checking the recorded pid answers it without one.
    */
  private[gx] def desktopRunning(controlFile: Path): Boolean =
    try
      if !Files.isRegularFile(controlFile) then false
      else
        val json = ujson.read(Files.readString(controlFile, StandardCharsets.UTF_8))
        json.obj.get("pid").flatMap(_.numOpt).map(_.toLong).exists(pidAlive)
    catch case NonFatal(_) => false

  private def pidAlive(pid: Long): Boolean =
    try ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)
    catch case NonFatal(_) => false
