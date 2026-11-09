package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, GxHome}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
import org.jpablo.graphexplorer.gxcore.store.MetadataStore

import java.nio.file.Path

/** Everything the commands touch that is not pure.
  *
  * Injected rather than reached for, so the whole CLI can be exercised without
  * spawning a process, capturing a pipe, or sleeping. v1's `gx` was only
  * testable end-to-end through shell smoke scripts, which is why the brief could
  * say its invariants were never asserted by name.
  */
final case class CliEnv(
    /** `$GX_HOME`: the library and the runtime files. */
    gxHome: Path,

    /** `$GX_DIAGRAMS_DIR`, or `~/Documents/Graph Explorer`. The migration
      * writes the old records that have no file into this folder.
      * `$GX_HOME` does not move it, so a test must give both.
      */
    diagramsDir: Path,
    policy:      AccessPolicy,
    audit:       Audit,
    cwd:         Path,
    out:         String => Unit,
    err:         String => Unit,
    stdin:       () => String,
    now:         () => Long,

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
    sleep:        Long => Unit  = ms => Thread.sleep(ms),

    /** Runs after a change command reads the file and before it writes the
      * file. A test changes the file here to make a conflict. The real one does
      * nothing.
      */
    beforeWrite: Path => Unit = _ => ()
):
  /** The library: one metadata file for each diagram (plan §3). */
  def library: MetadataStore = MetadataStore(GxHome.metadataDir(gxHome))

/** Exit codes, carried over from v1 §10 so existing scripts keep working.
  *
  * `3` (auth failure) is gone and not reused: v2 has no token to fail against —
  * the credential was deleted along with the loopback HTTP server (D3, D4).
  */
object ExitCode:
  val Ok    = 0
  val Usage = 1

  /** The command needs a window. Only a desktop command (`open`, `select`,
    * `selection`, `fit`) returns it, and each other command works with no
    * desktop.
    */
  val NeedsDesktop = 2

  /** A path, an element, or the content of a file is not usable: the file
    * does not exist or does not parse, the policy refuses the path, an
    * element name names nothing, or a change would remove content.
    */
  val InvalidPathOrPolicy = 4

  /** The file changed after gx read it. gx did not write it. */
  val Conflict = 5
  val Unknown  = 6
