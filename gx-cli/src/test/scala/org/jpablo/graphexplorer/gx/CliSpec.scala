package org.jpablo.graphexplorer.gx

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, Documents}
import org.jpablo.graphexplorer.gxcore.store.LibraryStore

import java.nio.file.{Files, Path}

/** V-09: every command except `open` works with no desktop running.
  *
  * That is the point of the whole redesign — v1's `gx` needed a GUI for
  * everything but `status`, which made it unusable in exactly the headless and
  * agent contexts it was built for (brief §6).
  */
class CliSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-cli").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private final class Run(
      dir:       Path,
      desktop:   Boolean = false,
      stdinText: String = "",
      /** Runs between watch polls, so a test can change a file while `watch` is
        * actually watching. Injected rather than done beforehand: a change made
        * before the loop starts is part of the initial state, not an event.
        */
      duringWatch: () => Unit = () => ()
  ):
    val out                = StringBuilder()
    val err                = StringBuilder()
    var clock              = 1000L
    private var watchTicks = 0
    val store: LibraryStore = LibraryStore(dir.resolve("library"))
    store.initialize()

    val env: CliEnv = CliEnv(
      store = store,
      policy = AccessPolicy(Nil, Nil),
      audit = Audit(dir.resolve("audit.jsonl")),
      cwd = dir,
      out = s => out.append(s).append('\n'),
      err = s => err.append(s).append('\n'),
      stdin = () => stdinText,
      now = () => { clock += 1; clock },
      desktopRunning = () => desktop,
      // A few polls: one to observe the change, one for it to settle past the
      // debounce, and a margin.
      keepWatching = () => { watchTicks += 1; watchTicks <= 4 },
      sleep = _ => duringWatch()
    )

    def apply(args: String*): Int = Cli.run(args.toVector, env)
    def stdout: String            = out.toString
    def stderr: String            = err.toString

  private def dot(dir: Path, name: String, text: String = "digraph G { a -> b }"): Path =
    val f = dir.resolve(name)
    Files.writeString(f, text)
    f

  // -------------------------------------------------------- V-09 headless

  tmp.test("V-09: status reports a missing desktop without failing") { dir =>
    val r = Run(dir)
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("not running"), r.stdout)
    // v1 exited 2 here, because a missing desktop meant nothing worked.
    assert(r.stdout.contains("only `gx open` needs it"), r.stdout)
  }

  tmp.test("V-09: import, ls, get and set all work with no desktop") { dir =>
    val f = dot(dir, "arch.dot")
    val r = Run(dir)

    // --mode sync, because the default (pull) deliberately does not write back;
    // that behaviour has its own test below.
    assertEquals(r("import", "arch.dot", "--mode", "sync"), ExitCode.Ok, r.stderr)
    assertEquals(r("ls"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("arch"), r.stdout)

    val g = Run(dir)
    assertEquals(g("get", "arch"), ExitCode.Ok, g.stderr)
    assertEquals(g.stdout.trim, "digraph G { a -> b }")

    val s = Run(dir, stdinText = "digraph G { a -> c }")
    assertEquals(s("set", "arch", "--stdin"), ExitCode.Ok, s.stderr)
    assertEquals(Files.readString(f), "digraph G { a -> c }")
  }

  /** D1's most user-visible consequence: reading a file no longer requires
    * registering it first. v1 failed here with "path is not currently watched".
    */
  tmp.test("V-09/D1: get works on a file that was never imported") { dir =>
    dot(dir, "loose.dot", "digraph Loose {}")
    val r = Run(dir)
    assertEquals(r("get", "loose.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.stdout.trim, "digraph Loose {}")
  }

  tmp.test("V-09: set can create a file that does not exist yet") { dir =>
    val r = Run(dir, stdinText = "digraph New {}")
    assertEquals(r("set", "new.dot", "--stdin"), ExitCode.Ok, r.stderr)
    assertEquals(Files.readString(dir.resolve("new.dot")), "digraph New {}")
  }

  // ------------------------------------------------------------ conflicts

  tmp.test("a stale --base is refused with exit 5, and the file is untouched") { dir =>
    val f = dot(dir, "a.dot", "original")
    val r = Run(dir, stdinText = "mine")
    val code = r("set", "a.dot", "--stdin", "--base", "0000000000000000")
    assertEquals(code, ExitCode.Conflict, r.stderr)
    assertEquals(Files.readString(f), "original", "a refused write still changed the file")
    assert(r.stderr.contains("conflict"), r.stderr)
  }

  tmp.test("a current --base is accepted") { dir =>
    val f    = dot(dir, "a.dot", "original")
    val hash = Documents.hashOf(f).getOrElse(fail("gone"))
    val r    = Run(dir, stdinText = "mine")
    assertEquals(r("set", "a.dot", "--stdin", "--base", hash.hex), ExitCode.Ok, r.stderr)
    assertEquals(Files.readString(f), "mine")
  }

  // ------------------------------------------------------------- binding

  /** §5.3: a Pull binding keeps CLI edits local. The origin must not change, and
    * the user must be told so rather than left to discover it.
    */
  tmp.test("set on a Pull diagram saves locally and does NOT write the origin") { dir =>
    val f = dot(dir, "gen.dot", "generated v1")
    val i = Run(dir)
    assertEquals(i("import", "gen.dot", "--mode", "pull"), ExitCode.Ok, i.stderr)

    val s = Run(dir, stdinText = "my local edit")
    assertEquals(s("set", "gen", "--stdin"), ExitCode.Ok, s.stderr)
    assertEquals(Files.readString(f), "generated v1", "Pull wrote back to the origin")
    assert(s.stdout.contains("saved locally"), s.stdout)
    assert(s.stdout.contains("does not write back"), s.stdout)
  }

  tmp.test("set on a Sync diagram does write the origin") { dir =>
    val f = dot(dir, "shared.dot", "v1")
    val i = Run(dir)
    assertEquals(i("import", "shared.dot", "--mode", "sync"), ExitCode.Ok, i.stderr)

    val s = Run(dir, stdinText = "v2")
    assertEquals(s("set", "shared", "--stdin"), ExitCode.Ok, s.stderr)
    assertEquals(Files.readString(f), "v2")
  }

  tmp.test("a mode the scheme cannot support is rejected with a reason") { dir =>
    val r = Run(dir)
    assertEquals(r("import", "a.dot", "--mode", "nonsense"), ExitCode.Usage)
    assert(r.stderr.contains("unknown mode"), r.stderr)
  }

  tmp.test("importing the same file twice reuses the record instead of duplicating") { dir =>
    dot(dir, "a.dot")
    val r = Run(dir)
    assertEquals(r("import", "a.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r("import", "a.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.store.list().size, 1, "a second import created a rival record for one file")
    assert(r.stderr.contains("already imported"), r.stderr)
  }

  tmp.test("bind and unbind attach and detach an origin") { dir =>
    val f = dot(dir, "a.dot")
    val r = Run(dir)
    r("import", "a.dot")
    val id = r.store.list().head.id

    assertEquals(r("unbind", id.value), ExitCode.Ok, r.stderr)
    assertEquals(r.store.get(id).fold(e => fail(s"$e"), _.binding), None)

    assertEquals(r("bind", id.value, "a.dot", "--mode", "sync"), ExitCode.Ok, r.stderr)
    assertEquals(r.store.get(id).fold(e => fail(s"$e"), _.binding.map(_.mode)), Some(org.jpablo.graphexplorer.gxcore.model.SyncMode.Sync))
  }

  // ---------------------------------------------------------------- sync

  /** The motivating flow: something else rewrites the file, gx follows. */
  tmp.test("sync pulls a Pull diagram after the origin changes") { dir =>
    val f = dot(dir, "gen.dot", "generated v1")
    val i = Run(dir)
    i("import", "gen.dot", "--mode", "pull")

    Files.writeString(f, "generated v2")

    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Ok, s.stderr)
    assert(s.stdout.contains("Behind"), s.stdout)
    assertEquals(s.store.list().head.text, "generated v2")
  }

  tmp.test("sync reports Diverged with exit 5 and changes nothing") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    val i = Run(dir)
    i("import", "gen.dot", "--mode", "sync")

    // Both sides move, differently.
    val e = Run(dir, stdinText = "local edit")
    e("set", "gen", "--stdin")             // pushes, so base advances
    val id = e.store.list().head.id
    val d  = e.store.get(id).fold(x => fail(s"$x"), identity)
    e.store.save(d.copy(text = "local only")) // diverge the store without pushing
    Files.writeString(f, "theirs")

    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Conflict, s.stdout)
    assert(s.stdout.contains("Diverged"), s.stdout)
    assertEquals(Files.readString(f), "theirs", "a diverged sync wrote to the origin")
  }

  /** The row that keeps a generator from producing endless conflicts. */
  tmp.test("sync treats a byte-identical regeneration as Converged, not Diverged") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    val i = Run(dir)
    i("import", "gen.dot", "--mode", "sync")
    val id = i.store.list().head.id

    // Both sides independently arrive at the same new content.
    val d = i.store.get(id).fold(x => fail(s"$x"), identity)
    i.store.save(d.copy(text = "v2"))
    Files.writeString(f, "v2")

    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Ok, s.stdout)
    assert(s.stdout.contains("Converged"), s.stdout)
  }

  tmp.test("sync with nothing bound says so and succeeds") { dir =>
    val r = Run(dir)
    assertEquals(r("sync", "--all"), ExitCode.Ok)
    assert(r.stdout.contains("nothing bound"), r.stdout)
  }

  // --------------------------------------------------------------- watch

  /** v1 had no way to observe changes without a window. This is the primitive a
    * script or an agent can pipe.
    */
  tmp.test("watch streams a change to stdout with no desktop involved") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    Run(dir)("import", "gen.dot")

    // The write happens WHILE watching, which is the only way it is an event.
    val w = Run(dir, duringWatch = () => Files.writeString(f, "v2"))
    assertEquals(w("watch", "--all", "--interval", "0"), ExitCode.Ok, w.stderr)
    assert(w.stdout.contains("changed"), s"stdout=${w.stdout} stderr=${w.stderr}")
    assert(w.stdout.contains(f.toRealPath().toString), w.stdout)
  }

  tmp.test("watch emits JSON lines when asked") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    Run(dir)("import", "gen.dot")

    val w = Run(dir, duringWatch = () => Files.writeString(f, "v2"))
    w("watch", "--all", "--json", "--interval", "0")
    assert(w.stdout.contains("\"event\":\"changed\""), w.stdout)
    ujson.read(w.stdout.linesIterator.next()) // each line is valid JSON on its own
  }

  /** The motivating flow end to end, with no window anywhere: a generator
    * rewrites a file, and `gx watch` reports it on stdout.
    */
  tmp.test("watch reports a deleted origin rather than falling silent") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    Run(dir)("import", "gen.dot")

    val w = Run(dir, duringWatch = () => Files.deleteIfExists(f))
    assertEquals(w("watch", "--all", "--interval", "0"), ExitCode.Ok, w.stderr)
    assert(w.stdout.contains("deleted"), s"v1 emitted nothing here: ${w.stdout}")
  }

  /** v1 parsed `--open` into `_open_in_ui` and threw it away (brief §3). A flag
    * that silently does nothing is worse than one that is refused.
    */
  tmp.test("watch --open warns when there is no desktop, and watches anyway") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    Run(dir)("import", "gen.dot")

    val w = Run(dir, duringWatch = () => Files.writeString(f, "v2"))
    assertEquals(w("watch", "--all", "--open", "--interval", "0"), ExitCode.Ok)
    assert(w.stderr.contains("--open needs a running desktop"), w.stderr)
    assert(w.stdout.contains("changed"), w.stdout)
  }

  tmp.test("watch with nothing to watch is a usage error, not a silent success") { dir =>
    val r = Run(dir)
    assertEquals(r("watch", "--all"), ExitCode.Usage)
  }

  // ---------------------------------------------------------------- open

  tmp.test("open is the one command that needs a window") { dir =>
    val r = Run(dir, desktop = false)
    assertEquals(r("open", "anything"), ExitCode.NeedsDesktop)
    assert(r.stderr.contains("no desktop is running"), r.stderr)
    assert(r.stderr.contains("every other gx command works without it"), r.stderr)
  }

  // -------------------------------------------------------------- policy

  tmp.test("a denied path is refused with exit 4 and recorded") { dir =>
    val secret = Files.createDirectories(dir.resolve("secrets"))
    Files.writeString(secret.resolve("a.dot"), "x")
    val r = Run(dir)
    val env = r.env.copy(policy = AccessPolicy(Nil, List(secret)))
    assertEquals(Cli.run(Vector("import", "secrets/a.dot"), env), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("denied root"), r.stderr)
  }

  // -------------------------------------------------------------- basics

  tmp.test("no arguments prints usage and exits non-zero") { dir =>
    val r = Run(dir)
    assertEquals(r(), ExitCode.Usage)
    assert(r.stdout.contains("gx status"), r.stdout)
  }

  tmp.test("--help exits zero") { dir =>
    val r = Run(dir)
    assertEquals(r("--help"), ExitCode.Ok)
  }

  tmp.test("an unknown command is refused, not ignored") { dir =>
    val r = Run(dir)
    assertEquals(r("frobnicate"), ExitCode.Usage)
    assert(r.stderr.contains("unknown command"), r.stderr)
  }

  tmp.test("--json output parses as JSON") { dir =>
    dot(dir, "a.dot")
    val r = Run(dir)
    r("import", "a.dot", "--json")
    ujson.read(r.stdout) // throws if it is not valid JSON
    val l = Run(dir)
    l("ls", "--json")
    assertEquals(ujson.read(l.stdout).arr.size, 1)
  }
