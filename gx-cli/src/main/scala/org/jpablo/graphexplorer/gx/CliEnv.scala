package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
import org.jpablo.graphexplorer.gxcore.store.LibraryStore

import java.nio.file.Path

/** Everything the commands touch that is not pure.
  *
  * Injected rather than reached for, so the whole CLI can be exercised without
  * spawning a process, capturing a pipe, or sleeping. v1's `gx` was only
  * testable end-to-end through shell smoke scripts, which is why the brief could
  * say its invariants were never asserted by name.
  */
final case class CliEnv(
    store:  LibraryStore,
    policy: AccessPolicy,
    audit:  Audit,
    cwd:    Path,
    out:    String => Unit,
    err:    String => Unit,
    stdin:  () => String,
    now:    () => Long,

    /** Whether a desktop is up. Only the session-tier commands care (D7.2), and
      * they are the only ones that can fail for want of one.
      *
      * Since P5 this is answered by CONNECTING rather than by reading a pid out
      * of the runtime file. A crashed desktop leaves both the file and its
      * socket behind, so neither one's existence means anything; a refused
      * connection does.
      */
    desktopRunning: () => Boolean,

    /** One request/response exchange with a running desktop (D4).
      *
      * Injected as a function rather than a channel so the CLI's tests can
      * answer it without a socket — and so `Cli` never has to know that a socket
      * is what is on the other end.
      */
    rpc: (String, ujson.Obj) => Either[ChannelError, ujson.Value] =
      (_, _) => Left(ChannelError.NoDesktop("no control channel configured")),

    /** Bounded so `watch` can be tested without real time. Returns false when
      * the loop should stop; the real one runs forever.
      */
    keepWatching: () => Boolean = () => true,
    sleep:        Long => Unit  = ms => Thread.sleep(ms)
)

/** Exit codes, carried over from v1 §10 so existing scripts keep working.
  *
  * `3` (auth failure) is gone and not reused: v2 has no token to fail against —
  * the credential was deleted along with the loopback HTTP server (D3, D4).
  */
object ExitCode:
  val Ok                  = 0
  val Usage               = 1

  /** The command needs a window. Under v1 this meant "nothing works"; now it is
    * only ever the session tier, and every other command has already run.
    */
  val NeedsDesktop        = 2
  val InvalidPathOrPolicy = 4
  val Conflict            = 5
  val Unknown             = 6
