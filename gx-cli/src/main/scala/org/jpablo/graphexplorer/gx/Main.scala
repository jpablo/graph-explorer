package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, GxHome}
import org.jpablo.graphexplorer.gxcore.rpc.{ChannelError, ControlChannel}
import org.jpablo.graphexplorer.gxcore.store.LibraryStore

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}

/** The real process. Everything decidable lives in [[Cli]], which takes its
  * world as a parameter; this only supplies it.
  */
object Main:

  /** Dump every frame to stderr.
    *
    * D4 gave up `curl` when it gave up HTTP, and that was a real loss: being
    * able to see the traffic is how a local protocol stays debuggable. This is
    * the replacement, and it is deliberately the whole frame in both directions
    * — there is nothing in one to redact any more.
    */
  private val DebugFlag = "--debug-protocol"

  def main(argv: Array[String]): Unit =
    // Resolved once, here, and threaded everywhere else. `$GX_HOME` moves the
    // library, the control file and the audit log together — they only work as
    // a set, since the runtime file is what names the socket for the library it
    // belongs to.
    // A refusal here is reported and exits; it must not become a stack trace.
    // The variable is usually set by a script or a test harness, and the useful
    // answer is which value was rejected and why.
    val gxHome = GxHome.resolve() match
      case Right(path) => path
      case Left(why) =>
        System.err.println(s"gx: $why")
        System.exit(ExitCode.Usage)
        return

    val runtime = GxHome.runtimeDir(gxHome)
    val control = runtime.resolve("control.json")

    val args  = argv.toVector
    val debug = args.contains(DebugFlag)
    val rest  = args.filterNot(_ == DebugFlag)

    val trace: String => Unit =
      if debug then message => System.err.println(s"gx[protocol] $message")
      else _ => ()

    // One clock for the whole process: the audit log stamps its lines from the
    // same source as every `updatedAt` and `lastSyncAt`, so a record and the
    // line describing it cannot disagree about when it happened.
    val clock: () => Long = () => System.currentTimeMillis()

    val env = CliEnv(
      store = LibraryStore.default(gxHome),
      policy = AccessPolicy.fromEnv(),
      audit = Audit(runtime.resolve("audit.log.jsonl"), clock),
      // The user's shell, not the process's idea of it. v1 learned this the hard
      // way: the desktop's working directory is an artifact of how it was
      // launched, so paths must be resolved where the human typed them.
      cwd = Paths.get("").toAbsolutePath,
      out = println,
      err = System.err.println,
      stdin = () => String(System.in.readAllBytes(), StandardCharsets.UTF_8), // V-16
      now = clock,
      desktopRunning = () => Main.desktopRunning(control, trace),
      rpc = (method, params) => Main.call(control, trace, method, params)
    )

    System.exit(Cli.run(rest, env))

  /** One connection per call.
    *
    * `gx` is a short-lived process that issues one or two requests, so a
    * connection pool would be machinery for nothing — and P0 measured the cost
    * of being wrong about this: a whole process start is ~3.5ms, and the spike
    * put a socket round-trip at 0.5ms.
    */
  private[gx] def call(
      controlFile: Path,
      trace:       String => Unit,
      method:      String,
      params:      ujson.Obj
  ): Either[ChannelError, ujson.Value] =
    ControlChannel.use(controlFile, trace)(_.call(method, params))

  /** Is a desktop actually up?
    *
    * v1 read a pid out of the runtime file, which was already better than its
    * predecessor (which had to discover it by failing a request). Connecting is
    * better still: a crashed desktop leaves BOTH the runtime file and its socket
    * behind, so only the connection distinguishes a live desktop from its
    * remains.
    */
  private[gx] def desktopRunning(controlFile: Path, trace: String => Unit = _ => ()): Boolean =
    ControlChannel.use(controlFile, trace)(_ => Right(())).isRight
