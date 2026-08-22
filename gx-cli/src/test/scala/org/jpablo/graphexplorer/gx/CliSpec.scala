package org.jpablo.graphexplorer.gx

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, Documents}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
import org.jpablo.graphexplorer.gxcore.store.LibraryStore

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermissions

import scala.jdk.CollectionConverters.*

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
      duringWatch: () => Unit = () => (),

      /** The desktop's answer, if there is a desktop. Injected as a function so
        * the session tier can be tested without a socket — which is the point
        * of CliEnv taking its world as a parameter (D4's channel is an
        * implementation detail `Cli` never sees).
        */
      answer: (String, ujson.Obj) => Either[ChannelError, ujson.Value] =
        (_, _) => Left(ChannelError.NoDesktop("no desktop in this test"))
  ):
    val out                = StringBuilder()
    val err                = StringBuilder()
    var clock              = 1000L
    private var watchTicks = 0
    val store: LibraryStore = LibraryStore(dir.resolve("library"))
    store.initialize()

    /** Every (method, params) pair the CLI sent, in order. */
    val sent = scala.collection.mutable.ArrayBuffer.empty[(String, ujson.Obj)]

    val env: CliEnv = CliEnv(
      store = store,
      policy = AccessPolicy(Nil, Nil),
      // Same clock the CLI uses, so an audit line's timestamp is a fact a test
      // can assert rather than whatever the wall clock said.
      audit = Audit(dir.resolve("audit.jsonl"), () => clock),
      cwd = dir,
      out = s => out.append(s).append('\n'),
      err = s => err.append(s).append('\n'),
      stdin = () => stdinText,
      now = () => { clock += 1; clock },
      desktopRunning = () => desktop,
      rpc = (method, params) =>
        sent += ((method, params))
        answer(method, params),
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

  /** A ref that resolves to nothing is a typo, not an empty selection. This
    * used to be flat-mapped away: "(nothing bound to sync)" and exit 0, which
    * tells a script that mistyped a name that it succeeded.
    */
  tmp.test("sync on a ref that matches nothing fails, it does not report success") { dir =>
    dot(dir, "gen.dot", "v1")
    val i = Run(dir)
    i("import", "gen.dot", "--mode", "sync")

    val s = Run(dir)
    assertNotEquals(s("sync", "typo"), ExitCode.Ok, s.stdout)
    assert(s.stderr.contains("no diagram matches"), s.stderr)
    assert(!s.stdout.contains("nothing bound"), s.stdout)
  }

  /** A reconciliation that cannot be persisted is not a success. The three
    * saves inside syncOne discarded their Either, so gx printed `Behind`,
    * exited 0, and left the record on its old baseline — and the next run
    * redid the same work from the same stale state, silently.
    */
  tmp.test("a sync that cannot save the record reports it and does not exit 0") { dir =>
    val f = dot(dir, "gen.dot", "v1")
    val i = Run(dir)
    i("import", "gen.dot", "--mode", "pull")

    Files.writeString(f, "v2") // the origin moves, so Pull has something to save

    // Readable and listable, but nothing new can be created in it — so the
    // scan still finds the record and only the WRITE fails.
    val diagrams = dir.resolve("library").resolve("diagrams")
    val restore  = Files.getPosixFilePermissions(diagrams)
    Files.setPosixFilePermissions(diagrams, PosixFilePermissions.fromString("r-xr-xr-x"))
    try
      // Root ignores the mode bits; there is nothing to assert on such a box.
      assume(
        scala.util.Try(Files.createTempFile(diagrams, "probe", null)).isFailure,
        "this user can write to a read-only directory"
      )

      val s = Run(dir)
      assertNotEquals(s("sync", "--all"), ExitCode.Ok, s.stdout)
      assert(s.stderr.contains("could not be saved"), s.stderr)
    finally Files.setPosixFilePermissions(diagrams, restore)
  }

  // ------------------------------------------------------- ambiguous refs
  //
  // findInLibrary's own doc says ambiguity is reported rather than resolved by
  // picking one. It returned None for "nothing matched" AND "several matched",
  // so callers rendered both as "no diagram matches" — and the ones that fall
  // back to a PATH did so on an ambiguous name.

  /** Two records deliberately sharing a name; only their ids differ. */
  private def twoNamed(dir: Path, name: String): Run =
    dot(dir, "a.dot", "digraph { a }")
    dot(dir, "b.dot", "digraph { b }")
    val i = Run(dir)
    i("import", "a.dot", "--name", name)
    i("import", "b.dot", "--name", name)
    i

  tmp.test("an ambiguous ref is reported as ambiguous, with the ids to pick from") { dir =>
    twoNamed(dir, "Shared Thing")
    val r = Run(dir)
    assertNotEquals(r("get", "Shared Thing"), ExitCode.Ok, r.stdout)
    assert(r.stderr.contains("matches 2 diagrams"), r.stderr)
    assert(!r.stderr.contains("no diagram matches"), "ambiguity reported as absence")
  }

  /** The wrong-diagram write the doc warns about, in its real form: with no
    * ambiguity case, `set` fell through to treating the ref as a path and
    * created a FILE called `shared` instead of refusing.
    */
  tmp.test("an ambiguous ref does not fall through to being a path") { dir =>
    twoNamed(dir, "Shared Thing")
    val r = Run(dir, stdinText = "digraph { c }")
    assertNotEquals(r("set", "Shared Thing", "--stdin"), ExitCode.Ok, r.stdout)
    assert(r.stderr.contains("matches 2 diagrams"), r.stderr)
    assert(!Files.exists(dir.resolve("Shared Thing")), "set wrote a file named after an ambiguous ref")
  }

  tmp.test("an unambiguous id still resolves when its name is shared") { dir =>
    val i  = twoNamed(dir, "Shared Thing")
    val id = i.store.list().head.id
    val r  = Run(dir)
    assertEquals(r("get", id.value), ExitCode.Ok, r.stderr)
  }

  // ------------------------------------------------- sync × line endings
  //
  // `base` and `remote` are hashes of file BYTES, so `local` has to be measured
  // the same way. Hashing the record's text with a fixed LF made every one of
  // these read as a local edit that never happened.
  //
  // Note every OTHER sync test above uses text with no newline in it, where LF
  // and CRLF are the same bytes — which is exactly how this survived.

  private def crlf: String = "digraph G {\r\n  a -> b\r\n}\r\n"

  tmp.test("a CRLF origin nobody has touched is InSync, not Ahead") { dir =>
    dot(dir, "win.dot", crlf)
    val i = Run(dir)
    assertEquals(i("import", "win.dot", "--mode", "sync"), ExitCode.Ok, i.stderr)

    // Nothing at all happens here. Both sides are exactly as imported.
    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Ok, s.stderr)
    assert(s.stdout.contains("InSync"), s.stdout)
    assertEquals(Files.readString(dir.resolve("win.dot")), crlf, "sync rewrote an untouched origin")
  }

  tmp.test("a byte-identical CRLF regeneration is Converged, not Diverged") { dir =>
    val f = dot(dir, "win.dot", crlf)
    val i = Run(dir)
    i("import", "win.dot", "--mode", "sync")
    val id = i.store.list().head.id

    // The generator rewrites the same bytes; the store independently agrees.
    val d = i.store.get(id).fold(x => fail(s"$x"), identity)
    val next = "digraph G {\r\n  a -> c\r\n}\r\n"
    i.store.save(d.copy(text = next))
    Files.writeString(f, next)

    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Ok, s.stdout)
    assert(s.stdout.contains("Converged"), s.stdout)
  }

  tmp.test("a CRLF origin that moves is Behind, and pull follows it") { dir =>
    val f = dot(dir, "win.dot", crlf)
    val i = Run(dir)
    i("import", "win.dot", "--mode", "pull")

    val next = "digraph G {\r\n  a -> b\r\n  b -> c\r\n}\r\n"
    Files.writeString(f, next)

    val s = Run(dir)
    assertEquals(s("sync", "--all"), ExitCode.Ok, s.stderr)
    assert(s.stdout.contains("Behind"), s.stdout)
    assertEquals(s.store.list().head.text, next)
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

  /** P3 left `open` reporting NeedsDesktop even when one WAS running, because
    * there was no channel to ask. There is one now (D4).
    */
  tmp.test("open sends `show` with the file's path") { dir =>
    val f = dot(dir, "arch.dot")
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("path" -> f.toString, "focused" -> true)))

    assertEquals(r("open", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.size, 1)
    val (method, params) = r.sent.head
    assertEquals(method, "show")
    // The path is resolved against the user's cwd before it is sent: the
    // desktop's working directory is an artifact of how it was launched.
    assertEquals(params("path").str, f.toString)
    assert(r.stdout.contains("showing"), r.stdout)
  }

  /** A library reference is not a path. The desktop only understands files, so
    * `open my-diagram` has to resolve through the binding.
    */
  tmp.test("open resolves a library diagram to the file it is bound to") { dir =>
    val f = dot(dir, "bound.dot")
    val i = Run(dir)
    assertEquals(i("import", "bound.dot"), ExitCode.Ok, i.stderr)

    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> true)))
    assertEquals(r("open", "bound"), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.head._2("path").str, f.toString)
  }

  tmp.test("open refuses a diagram with no origin, and says how to fix it") { dir =>
    dot(dir, "detached.dot")
    val i = Run(dir)
    assertEquals(i("import", "detached.dot"), ExitCode.Ok, i.stderr)
    assertEquals(i("unbind", "detached"), ExitCode.Ok, i.stderr)

    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> true)))
    assertEquals(r("open", "detached"), ExitCode.InvalidPathOrPolicy, r.stdout)
    assert(r.stderr.contains("not bound to a file"), r.stderr)
    assert(r.stderr.contains("gx bind"), r.stderr)
    // And it never asked the desktop: there was nothing to ask about.
    assert(r.sent.isEmpty, s"should not have called the desktop: ${r.sent}")
  }

  /** A desktop can be up with no window on screen. `show` reports which
    * happened rather than claiming a success the user cannot see.
    */
  tmp.test("open says so when there was no window to raise") { dir =>
    dot(dir, "arch.dot")
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> false)))
    assertEquals(r("open", "arch.dot"), ExitCode.Ok, r.stderr)
    assert(r.stderr.contains("no window to raise"), r.stderr)
  }

  tmp.test("a desktop that refuses the path reports the desktop's reason") { dir =>
    dot(dir, "arch.dot")
    val r = Run(
      dir,
      answer = (_, _) =>
        Left(ChannelError.Rpc("WATCH_FAILED", "path is blocked by denylist", ujson.Obj()))
    )
    assertEquals(r("open", "arch.dot"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("blocked by denylist"), r.stderr)
  }

  /** The desktop may be up with no window — the socket answers from process
    * start, before the webview exists. That is not a bad path, so it must not
    * be reported as one: nothing about the request needs fixing, and the user's
    * next move is to bring the window up.
    */
  tmp.test("a desktop with no window to show it in needs a desktop, it did not refuse the path") { dir =>
    dot(dir, "arch.dot")
    val r = Run(
      dir,
      answer = (_, _) =>
        Left(ChannelError.Rpc("NO_WINDOW", "the desktop has no window to show it in", ujson.Obj()))
    )
    assertEquals(r("open", "arch.dot"), ExitCode.NeedsDesktop, r.stderr)
    assert(r.stderr.contains("no window"), r.stderr)
    assert(!r.stderr.contains("refused"), "a missing window read as a rejected path")
  }

  tmp.test("status says what the desktop is WATCHING, not what it has open") { dir =>
    // The number is the desktop's watch registry. It was labelled "open",
    // which reads as "your diagram is on screen" — and an imported diagram is
    // in neither set, so that wording turned D7.3 into an apparent bug report.
    val r = Run(
      dir,
      answer = (_, _) =>
        Right(ujson.Obj("running" -> true, "watches" -> ujson.Arr(ujson.Obj("path" -> "/tmp/a.dot"))))
    )
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("running (watching 1 file)"), r.stdout)
    assert(!r.stdout.contains("open)"), r.stdout)
    assertEquals(r.sent.head._1, "status")
  }

  tmp.test("a desktop watching nothing says so, and does not say 0") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("running" -> true, "watches" -> ujson.Arr())))
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("running (watching nothing)"), r.stdout)
  }

  tmp.test("the plural is a plural") { dir =>
    val r = Run(
      dir,
      answer = (_, _) =>
        Right(
          ujson.Obj(
            "running" -> true,
            "watches" -> ujson.Arr(ujson.Obj("path" -> "/tmp/a.dot"), ujson.Obj("path" -> "/tmp/b.dot"))
          )
        )
    )
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("running (watching 2 files)"), r.stdout)
  }

  /** The desktop binds its control socket BEFORE the webview comes up, because
    * on Windows that wait is routinely 15s and has been measured past 30. So a
    * successful call no longer proves there is a window, and the state the user
    * most needs named — "it is starting" — is exactly the one that used to be
    * reported as "not running".
    */
  tmp.test("a starting desktop is starting, not missing") { dir =>
    val r = Run(
      dir,
      answer = (_, _) =>
        Right(ujson.Obj("running" -> false, "state" -> "starting", "watches" -> ujson.Arr()))
    )
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("starting"), r.stdout)
    // The two claims it must NOT make: that there is no desktop, and that
    // there is a window to show you something.
    assert(!r.stdout.contains("not running"), r.stdout)
    assert(!r.stdout.contains("running (watching"), r.stdout)
  }

  tmp.test("a desktop that sends no state is running, not starting") { dir =>
    // Wire compatibility, pinned: a desktop from before `state` existed only
    // ever answered once it was fully up, so its silence means running. Read
    // the other way, every older desktop would report itself as starting
    // forever.
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("running" -> true, "watches" -> ujson.Arr())))
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("running (watching nothing)"), r.stdout)
    assert(!r.stdout.contains("starting"), r.stdout)
  }

  tmp.test("--json names the starting state too") { dir =>
    val r = Run(
      dir,
      answer = (_, _) =>
        Right(ujson.Obj("running" -> false, "state" -> "starting", "watches" -> ujson.Arr()))
    )
    assertEquals(r("status", "--json"), ExitCode.Ok, r.stderr)
    val parsed = ujson.read(r.stdout)
    assertEquals(parsed("desktopStarting").bool, true)
    assertEquals(parsed("desktopRunning").bool, false)
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

  /** The audit log is the only place `source` is recorded, so WHEN an event
    * happened has to come from the same clock as the record it describes.
    * Audit stamped its own `System.currentTimeMillis()` instead — the one hole
    * in a seam every other timestamp goes through.
    */
  tmp.test("an audit line is stamped from the injected clock, not the wall clock") { dir =>
    dot(dir, "a.dot")
    val r = Run(dir)
    assertEquals(r("import", "a.dot"), ExitCode.Ok, r.stderr)

    val line = r.env.audit.entries.headOption.getOrElse(fail("nothing was audited"))
    val stamp = ujson.read(line).obj("timestampMs").num.toLong
    // The fixture's clock starts at 1000 and ticks by one per read, so a real
    // wall-clock stamp is thirteen digits and this assertion is unmissable.
    assert(stamp > 1000L && stamp < 2000L, s"audit used a clock the test does not control: $stamp")
  }

  /** The guardrail has to hold on EVERY path-taking command, not most of them.
    * `watch` took a ref that was not in the library, turned it straight into an
    * origin, and started following it — the one way to point gx at a file the
    * policy forbids.
    */
  tmp.test("watch refuses a denied path too, like every other command") { dir =>
    val secret = Files.createDirectories(dir.resolve("secrets"))
    Files.writeString(secret.resolve("a.dot"), "x")
    val r   = Run(dir)
    val env = r.env.copy(policy = AccessPolicy(Nil, List(secret)))
    assertEquals(Cli.run(Vector("watch", "secrets/a.dot"), env), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("denied root"), r.stderr)
    assert(!r.stderr.contains("watching "), "watch followed a denied path anyway")
  }

  // ----------------------------------------------------------------- run

  /** The document tier, headless (D7.2). These are V-09's point restated for
    * the command vocabulary: none of them needs a desktop.
    */
  tmp.test("run answers a query without touching the file") { dir =>
    val f = dot(dir, "q.dot", "digraph G {\n  a -> b\n}\n")
    val before = Files.readString(f)
    val r = Run(dir)

    assertEquals(r("run", "q.dot", "list-nodes", "--json"), ExitCode.Ok, r.stderr)
    val answer = ujson.read(r.stdout).arr
    assertEquals(answer.map(_("ref").str).toVector, Vector("node:a", "node:b"))
    assertEquals(Files.readString(f), before, "a query must not rewrite the diagram")
    assert(r.sent.isEmpty, "the document tier must not need the desktop")
  }

  tmp.test("run applies a mutation and writes it back") { dir =>
    dot(dir, "m.dot", "digraph G {\n  a -> b\n}\n")
    val r = Run(dir)
    assertEquals(
      r("run", "m.dot", "set-attribute", "--params",
        """{"targets":["node:a"],"name":"color","value":"red"}"""),
      ExitCode.Ok,
      r.stderr
    )
    val text = Files.readString(dir.resolve("m.dot"))
    assert(text.contains("color"), text)
    assert(text.contains("red"), text)

    // And the edit survives a re-read through the same path, which is the
    // property that makes `run` composable at all.
    val q = Run(dir)
    assertEquals(q("run", "m.dot", "get-attributes", "--params", """{"targets":["node:a"]}"""), ExitCode.Ok, q.stderr)
    assertEquals(ujson.read(q.stdout)("node:a")("color").str, "red")
  }

  tmp.test("run refuses an unknown command by name, and says what it knows") { dir =>
    dot(dir, "u.dot")
    val r = Run(dir)
    assertEquals(r("run", "u.dot", "frobnicate"), ExitCode.Usage)
    assert(r.stderr.contains("frobnicate"), r.stderr)
    assert(r.stderr.contains("list-nodes"), s"should name the commands it does know: ${r.stderr}")
  }

  tmp.test("run refuses an element that does not exist, and leaves the file alone") { dir =>
    val f = dot(dir, "g.dot", "digraph G {\n  a -> b\n}\n")
    val before = Files.readString(f)
    val r = Run(dir)
    assertEquals(
      r("run", "g.dot", "set-attribute", "--params",
        """{"targets":["node:ghost"],"name":"color","value":"red"}"""),
      ExitCode.InvalidPathOrPolicy
    )
    assert(r.stderr.contains("node:ghost"), r.stderr)
    assertEquals(Files.readString(f), before)
  }

  tmp.test("run reads params from stdin, for arguments a shell would mangle") { dir =>
    dot(dir, "s.dot", "digraph G {\n  a -> b\n}\n")
    val r = Run(dir, stdinText = """{"targets":["node:a"],"name":"label","value":"a \"quoted\" label"}""")
    assertEquals(r("run", "s.dot", "set-attribute", "--stdin"), ExitCode.Ok, r.stderr)
    assert(Files.readString(dir.resolve("s.dot")).contains("quoted"), Files.readString(dir.resolve("s.dot")))
  }

  /** `--params` has to be REGISTERED as a value flag, not merely used as one.
    *
    * Args treats an unregistered `--flag` as a switch on purpose — so a typo
    * becomes an unknown switch instead of silently eating the next argument.
    * Forgetting to register `params` therefore turned the JSON into a
    * positional and produced "missing required 'targets'", which reads like a
    * caller error rather than a parser one.
    */
  tmp.test("--params carries its value rather than becoming a switch") { dir =>
    dot(dir, "p.dot", "digraph G {\n  a -> b\n}\n")
    val r = Run(dir)
    assertEquals(r("run", "p.dot", "get-attributes", "--params", """{"targets":["node:a"]}"""), ExitCode.Ok, r.stderr)
    assert(ujson.read(r.stdout).obj.contains("node:a"), r.stdout)
  }

  /** A mutation REWRITES the file in canonical form.
    *
    * Pinned rather than hidden, because it is the surprising part: `gx run`
    * goes text → graph → text, so the output is the printer's spelling —
    * quoted ids, explicit node statements — and comments and hand formatting do
    * not survive. `sources-and-library-architecture.md` §5.3's surgical text
    * edits are the fix; until then this is what a mutation costs, and a test
    * saying so beats a user discovering it on a file they cared about.
    */
  tmp.test("a mutation rewrites the file in canonical form (comments do not survive)") { dir =>
    dot(dir, "c.dot", "digraph G {\n  // a comment worth keeping\n  a -> b\n}\n")
    val r = Run(dir)
    assertEquals(
      r("run", "c.dot", "set-attribute", "--params", """{"targets":["node:a"],"name":"color","value":"red"}"""),
      ExitCode.Ok,
      r.stderr
    )
    val text = Files.readString(dir.resolve("c.dot"))
    assert(text.contains("color"), text)
    assert(!text.contains("a comment worth keeping"), s"the round trip is lossy — that is the known cost: $text")
  }

  tmp.test("a query prints columns for a person and JSON for a machine") { dir =>
    dot(dir, "o.dot", "digraph G {\n  a -> b\n}\n")
    val plain = Run(dir)
    assertEquals(plain("run", "o.dot", "list-nodes"), ExitCode.Ok, plain.stderr)
    assert(plain.stdout.contains("node:a"), plain.stdout)
    assert(!plain.stdout.contains("\"ref\""), s"the default should not be JSON: ${plain.stdout}")

    val json = Run(dir)
    assertEquals(json("run", "o.dot", "list-nodes", "--json"), ExitCode.Ok, json.stderr)
    ujson.read(json.stdout)
  }

  tmp.test("run --list enumerates the vocabulary") { dir =>
    val r = Run(dir)
    assertEquals(r("run", "--list"), ExitCode.Ok)
    assert(r.stdout.contains("set-attribute"), r.stdout)
    assert(r.stdout.contains("list-nodes"), r.stdout)
  }

  tmp.test("run on a mermaid diagram refuses rather than reading it as DOT") { dir =>
    // The mermaid converter needs a scan produced by mermaid.js, which gx has
    // no way to run. Silently treating a .mmd as DOT would be the worse answer.
    val f = dir.resolve("m.mmd")
    Files.writeString(f, "flowchart TD\n  a --> b\n")
    val r = Run(dir)
    assertEquals(r("run", "m.mmd", "list-nodes"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("mermaid"), r.stderr)
  }

  // --------------------------------------------------------- record tier

  tmp.test("run hide stores view state without touching the origin file") { dir =>
    val f = dot(dir, "arch.dot", "digraph G {\n  a -> b\n}\n")
    val before = Files.readString(f)
    val i = Run(dir)
    assertEquals(i("import", "arch.dot", "--mode", "sync"), ExitCode.Ok, i.stderr)

    val r = Run(dir)
    assertEquals(
      r("run", "arch", "hide", "--params", """{"targets":["node:a"]}"""),
      ExitCode.Ok,
      r.stderr
    )

    assertEquals(r.store.list().head.metadata.hiddenElements, Set("node:a"))
    // §5.3.1: metadata survives a pull precisely BECAUSE it never reaches the
    // origin. Sync mode pushes text; it must not push a hidden node.
    assertEquals(Files.readString(f), before, "hiding a node wrote to the origin")
  }

  tmp.test("run collapse and expand round-trip through the record") { dir =>
    dot(dir, "c.dot")
    val i = Run(dir)
    assertEquals(i("import", "c.dot"), ExitCode.Ok, i.stderr)

    val a = Run(dir)
    assertEquals(a("run", "c", "collapse", "--params", """{"groups":["group:g1"]}"""), ExitCode.Ok, a.stderr)
    assertEquals(a.store.list().head.metadata.collapsedGroups, Set("group:g1"))

    val b = Run(dir)
    assertEquals(b("run", "c", "expand-all"), ExitCode.Ok, b.stderr)
    assertEquals(b.store.list().head.metadata.collapsedGroups, Set.empty[String])
  }

  tmp.test("run tag, move-to-folder and rename-diagram change the record") { dir =>
    dot(dir, "t.dot")
    val i = Run(dir)
    assertEquals(i("import", "t.dot"), ExitCode.Ok, i.stderr)

    val r = Run(dir)
    assertEquals(r("run", "t", "tag", "--params", """{"tags":["infra","draft"]}"""), ExitCode.Ok, r.stderr)
    assertEquals(r("run", "t", "move-to-folder", "--params", """{"folder":"/systems"}"""), ExitCode.Ok, r.stderr)
    assertEquals(r("run", "t", "rename-diagram", "--params", """{"name":"Topology"}"""), ExitCode.Ok, r.stderr)

    val d = r.store.list().head
    assertEquals(d.metadata.tags, List("infra", "draft"))
    assertEquals(d.folder.render, "/systems")
    assertEquals(d.name, "Topology")
  }

  tmp.test("get-record answers with the stored metadata") { dir =>
    dot(dir, "g.dot")
    val i = Run(dir)
    assertEquals(i("import", "g.dot"), ExitCode.Ok, i.stderr)
    assertEquals(i("run", "g", "hide", "--params", """{"targets":["node:a"]}"""), ExitCode.Ok, i.stderr)

    val r = Run(dir)
    assertEquals(r("run", "g", "get-record", "--json"), ExitCode.Ok, r.stderr)
    val answer = ujson.read(r.stdout)
    assertEquals(answer("hidden").arr.map(_.str).toVector, Vector("node:a"))
  }

  /** A record tier command needs a RECORD, and a loose file does not have one.
    * The message says how to get one rather than only that there is none.
    */
  tmp.test("a record command on an unimported file says to import it first") { dir =>
    dot(dir, "loose.dot")
    val r = Run(dir)
    assertEquals(r("run", "loose.dot", "hide", "--params", """{"targets":["node:a"]}"""), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("no record"), r.stderr)
    assert(r.stderr.contains("gx import"), r.stderr)
  }

  tmp.test("a blank rename is refused and the record keeps its name") { dir =>
    dot(dir, "n.dot")
    val i = Run(dir)
    assertEquals(i("import", "n.dot"), ExitCode.Ok, i.stderr)

    val r = Run(dir)
    assertEquals(r("run", "n", "rename-diagram", "--params", """{"name":"  "}"""), ExitCode.InvalidPathOrPolicy)
    assertEquals(r.store.list().head.name, "n")
  }

  tmp.test("run --list covers both headless tiers") { dir =>
    val r = Run(dir)
    assertEquals(r("run", "--list"), ExitCode.Ok)
    assert(r.stdout.contains("set-attribute"), r.stdout) // document
    assert(r.stdout.contains("move-to-folder"), r.stdout) // record
  }

  // -------------------------------------------------------- session tier

  tmp.test("session sends the command and prints the query's answer") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Arr(ujson.Str("node:a"), ujson.Str("node:b"))))
    assertEquals(r("session", "what-is-selected"), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.head._1, "session")
    assertEquals(r.sent.head._2("command").str, "what-is-selected")
    assert(r.stdout.contains("node:a"), r.stdout)
  }

  tmp.test("session select carries its targets") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(r("session", "select", "--params", """{"targets":["node:a"]}"""), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.head._2("params")("targets").arr.map(_.str).toVector, Vector("node:a"))
  }

  tmp.test("an empty selection says so rather than printing nothing") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Arr()))
    assertEquals(r("session", "what-is-selected"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("nothing selected"), r.stdout)
  }

  tmp.test("session without a desktop exits 2, like open") { dir =>
    val r = Run(dir)
    assertEquals(r("session", "what-is-selected"), ExitCode.NeedsDesktop)
    assert(r.stderr.contains("needs one"), r.stderr)
  }

  /** A desktop with no diagram on screen is the tier's defining limit, and the
    * caller's next move is the same as for no desktop at all: open something.
    */
  tmp.test("a desktop with nothing open exits 2 as well") { dir =>
    val r = Run(dir, answer = (_, _) =>
      Left(ChannelError.Rpc("NO_SESSION", "'select' needs a window, and the desktop has none open", ujson.Obj()))
    )
    assertEquals(r("session", "select", "--params", """{"targets":["node:a"]}"""), ExitCode.NeedsDesktop)
    assert(r.stderr.contains("needs a window"), r.stderr)
  }

  tmp.test("a headless command typed at `session` points at the right verb") { dir =>
    val r = Run(dir)
    assertEquals(r("session", "list-nodes"), ExitCode.Usage)
    assert(r.stderr.contains("gx run"), s"should redirect to the headless verb: ${r.stderr}")
  }

  tmp.test("session --list enumerates the live-view commands only") { dir =>
    val r = Run(dir)
    assertEquals(r("session", "--list"), ExitCode.Ok)
    assert(r.stdout.contains("what-is-selected"), r.stdout)
    assert(!r.stdout.contains("set-attribute"), s"that is a headless command: ${r.stdout}")
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

  // --------------------------------------------------------------- skill

  /** `gx skill` points an agent at the skill; it must never install one.
    *
    * A skill is a prompt someone's agent will load and act on, so writing it
    * into their harness is a decision rather than a side effect of asking where
    * it is. These assert the printing, and — via the resolver — that the answer
    * is pinned to the binary rather than to whatever the branch says today.
    */

  tmp.test("skill prints a location and an instruction, and writes nothing") { dir =>
    val r = Run(dir)
    assertEquals(r("skill"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains(SkillLocation.File), r.stdout)
    assert(r.stdout.contains("Tell your coding agent"), r.stdout)
    // The whole point of the command: it is a pointer, not an installer.
    assert(!Files.exists(dir.resolve(SkillLocation.File)), "skill must not install anything")
  }

  tmp.test("skill --json is machine-readable and says whether it is pinned") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "--json"), ExitCode.Ok, r.stderr)
    val json = ujson.read(r.stdout)
    assertEquals(json("skill").str, SkillLocation.Name)
    assert(json("raw").str.endsWith(SkillLocation.File), json("raw").str)
    json("pinned").bool // present, and a boolean
  }

  tmp.test("skill takes an explicit version and pins to that tag") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "0.9.4", "--json"), ExitCode.Ok, r.stderr)
    val json = ujson.read(r.stdout)
    assertEquals(json("ref").str, "v0.9.4")
    assertEquals(json("pinned").bool, true)
  }

  tmp.test("skill refuses something that is not a version") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "yesterday"), ExitCode.Usage)
    assert(r.stderr.contains("not a version"), r.stderr)
  }

  tmp.test("skill --latest is the branch tip, and not pinned") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "--latest", "--json"), ExitCode.Ok, r.stderr)
    val json = ujson.read(r.stdout)
    assertEquals(json("ref").str, SkillLocation.DefaultBranch)
    assertEquals(json("pinned").bool, false)
  }

  tmp.test("skill will not guess between --latest and a named version") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "0.9.4", "--latest"), ExitCode.Usage)
    assert(r.stderr.contains("pick one"), r.stderr)
  }

  // The resolver itself, away from the printing: a dev build has no tag to
  // point at, and pretending otherwise would send an agent to a 404.
  test("a released version resolves to its tag; a dev build falls back to the tip") {
    val release = SkillLocation.resolve(None, latest = false, running = "0.9.4")
    assertEquals(release.map(_.ref), Right("v0.9.4"))
    assertEquals(release.map(_.pinned), Right(true))

    val dev = SkillLocation.resolve(None, latest = false, running = "0.9.3+13-2b8d0a46+20260730-2334")
    assertEquals(dev.map(_.ref), Right(SkillLocation.DefaultBranch))
    assertEquals(dev.map(_.pinned), Right(false))
    // The tip is not pinned, but the version still names a real tag to suggest.
    assertEquals(SkillLocation.baseRelease("0.9.3+13-2b8d0a46+20260730-2334"), Some("0.9.3"))
  }

  test("a version given with the tag's own 'v' is accepted") {
    val found = SkillLocation.resolve(Some("v1.2.3"), latest = false, running = "0.9.4")
    assertEquals(found.map(_.ref), Right("v1.2.3"))
  }

  /** The path `gx skill` advertises has to be the path the skill is actually
    * at, or every URL the command prints is a 404 — and nothing else in the
    * build would notice, because the skill is an asset no Scala code imports.
    */
  test("the advertised location exists in this repository") {
    val root = repoRoot()
    val skill = root.resolve(SkillLocation.File)
    assert(Files.exists(skill), s"gx skill points at ${SkillLocation.File}, which does not exist")
    for name <- SkillLocation.SupportingFiles do
      val f = root.resolve(SkillLocation.Directory).resolve(name)
      assert(Files.exists(f), s"SKILL.md links $name, which does not exist")
  }

  /** Only the six fields the Agent Skills spec allows.
    *
    * Not a style preference: packaging or uploading a skill with any other key
    * fails with a hard error rather than ignoring it, so a Claude Code-only
    * field here would make the skill unusable everywhere else.
    */
  test("the skill's frontmatter is portable") {
    val allowed = Set("allowed-tools", "compatibility", "description", "license", "metadata", "name")
    val lines   = Files.readAllLines(repoRoot().resolve(SkillLocation.File)).asScala.toVector
    assertEquals(lines.headOption, Some("---"), "SKILL.md must open with YAML frontmatter")

    val body = lines.drop(1)
    val end  = body.indexOf("---")
    assert(end > 0, "the frontmatter is not closed")

    val keys = body.take(end).filterNot(_.startsWith(" ")).filter(_.contains(":")).map(_.takeWhile(_ != ':'))
    val bad  = keys.filterNot(allowed.contains)
    assertEquals(bad, Vector.empty[String], s"non-portable frontmatter key(s): ${bad.mkString(", ")}")

    // The spec ties the name to the directory; a mismatch simply fails to load.
    assert(keys.contains("name"), "SKILL.md needs a name")
    assert(
      body.take(end).contains(s"name: ${SkillLocation.Name}"),
      s"the skill's name must match its directory (${SkillLocation.Name})"
    )
  }

  /** sbt runs tests from wherever it was launched, so walk up rather than
    * assuming the module directory.
    */
  private def repoRoot(): Path =
    Iterator
      .iterate(Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(fail("could not find the repository root"))
