package org.jpablo.graphexplorer.gx

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, Documents}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
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
      audit = Audit(dir.resolve("audit.jsonl")),
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

  tmp.test("status reports what the desktop has open") { dir =>
    val r = Run(
      dir,
      answer = (_, _) =>
        Right(ujson.Obj("running" -> true, "watches" -> ujson.Arr(ujson.Obj("path" -> "/tmp/a.dot"))))
    )
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("running (1 open)"), r.stdout)
    assertEquals(r.sent.head._1, "status")
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
