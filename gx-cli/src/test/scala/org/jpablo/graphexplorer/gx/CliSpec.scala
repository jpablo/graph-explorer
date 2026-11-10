package org.jpablo.graphexplorer.gx

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{AccessPolicy, Audit, Documents, FileOrigins, GxHome}
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
import org.jpablo.graphexplorer.gxcore.store.MetadataStore

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

/** The `gx` command surface of plan §2: the file comes first, and each
  * command is a verb.
  *
  * Each test runs the CLI in a temporary directory. `GX_HOME` and
  * `GX_DIAGRAMS_DIR` both point into that directory, so no test can reach the
  * real `~/.graph-explorer` or `~/Documents/Graph Explorer`.
  */
class CliSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-cli").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  // ------------------------------------------------------------ sandbox

  /** The real defaults, with no environment variable. */
  private lazy val realHome: Path =
    GxHome.resolve(env = _ => None).fold(e => throw IllegalStateException(e), identity)
  private lazy val realDiagrams: Path =
    GxHome.diagramsDir(env = _ => None).fold(e => throw IllegalStateException(e), identity)

  /** Refuse an environment that can reach the real home directory. Each [[Run]]
    * calls this. `GX_HOME` does not move the folder for new diagrams, so both
    * folders need the check.
    */
  private def requireSandboxed(env: CliEnv, sandbox: Path): Unit =
    for (variable, dir, real) <- Seq(
        ("GX_HOME", env.gxHome, realHome),
        ("GX_DIAGRAMS_DIR", env.diagramsDir, realDiagrams)
      )
    do
      if dir.equals(real) || !dir.startsWith(sandbox) then
        throw IllegalStateException(s"the test environment uses $dir for $variable, outside the sandbox $sandbox")

  private final class Run(
      dir:       Path,
      stdinText: String = "",
      /** Runs between watch polls, so a test can change a file while `watch` is
        * actually watching.
        */
      duringWatch: () => Unit = () => (),
      /** Runs after a change command reads the file, before it writes it. */
      beforeWrite: Path => Unit = _ => (),
      /** The answer of the desktop. The default is no desktop. */
      answer: (String, ujson.Obj) => Either[ChannelError, ujson.Value] =
        (_, _) => Left(ChannelError.NoDesktop("no desktop in this test"))
  ):
    val out                = StringBuilder()
    val err                = StringBuilder()
    var clock              = 1000L
    private var watchTicks = 0

    /** Each (method, params) pair that the CLI sent, in order. */
    val sent = ArrayBuffer.empty[(String, ujson.Obj)]

    val env: CliEnv = CliEnv(
      gxHome = dir.resolve("gx-home"),
      diagramsDir = dir.resolve("diagrams"),
      policy = AccessPolicy(Nil, Nil),
      audit = Audit(dir.resolve("audit.jsonl"), () => clock),
      cwd = dir,
      out = s => out.append(s).append('\n'),
      err = s => err.append(s).append('\n'),
      stdin = () => stdinText,
      now = () => { clock += 1; clock },
      rpc = (method, params) =>
        sent += ((method, params))
        answer(method, params),
      keepWatching = () => { watchTicks += 1; watchTicks <= 4 },
      sleep = _ => duringWatch(),
      beforeWrite = beforeWrite
    )
    requireSandboxed(env, dir)

    def apply(args: String*): Int = Cli.run(args.toVector, env)
    def stdout: String            = out.toString
    def stderr: String            = err.toString
    def json: ujson.Value         = ujson.read(stdout)
    def lines: Vector[String]     = stdout.linesIterator.toVector

    def library: MetadataStore                   = env.library
    def entry(file: Path): Option[LibraryEntry] = library.get(file.toRealPath().toString).toOption

  private def write(dir: Path, name: String, text: String): Path =
    val f = dir.resolve(name)
    Files.writeString(f, text)
    f

  /** A diagram with a group, two labels and no comment. */
  private val Arch =
    """digraph G {
      |  subgraph cluster_svc { label="services"; api [label="API"]; db }
      |  web -> api
      |  api -> db [label="reads"]
      |}
      |""".stripMargin

  private def arch(dir: Path): Path = write(dir, "arch.dot", Arch)

  private def firstColumn(r: Run): Vector[String] = r.lines.map(_.takeWhile(_ != ' '))

  private def metadataDir(dir: Path): Path = GxHome.metadataDir(dir.resolve("gx-home"))

  // ------------------------------------------------------------ sandbox

  test("a CLI test environment that keeps a real default directory is refused"):
    val sandbox = Files.createTempDirectory("gx-cli-sandbox").toRealPath()
    try
      val ok = CliEnv(
        gxHome = sandbox.resolve("home"),
        diagramsDir = sandbox.resolve("diagrams"),
        policy = AccessPolicy(Nil, Nil),
        audit = Audit(sandbox.resolve("audit.jsonl")),
        cwd = sandbox,
        out = _ => (),
        err = _ => (),
        stdin = () => "",
        now = () => 0L
      )
      requireSandboxed(ok, sandbox)
      intercept[IllegalStateException](requireSandboxed(ok.copy(gxHome = realHome), sandbox))
      intercept[IllegalStateException](requireSandboxed(ok.copy(diagramsDir = realDiagrams), sandbox))
    finally Files.walk(sandbox).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  // --------------------------------------------------------------- read

  tmp.test("nodes prints each node with its name and its label, in the order of the source") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("nodes", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(firstColumn(r), Vector("api", "db", "web"))
    assert(r.lines.head.contains("API"), r.stdout)
  }

  tmp.test("nodes --json gives name, ref and label") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("nodes", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    val nodes = r.json.arr
    assertEquals(nodes.map(_("name").str).toVector, Vector("api", "db", "web"))
    assertEquals(nodes.head("ref").str, "node:api")
    assertEquals(nodes.head("label").str, "API")
    assertEquals(nodes(1)("label"), ujson.Null)
  }

  tmp.test("arrows prints the name that a command takes, and the two nodes") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("arrows", "arch.dot"), ExitCode.Ok, r.stderr)
    // The order of `Arrow.seq`: cgraph numbers the arrows by their tail node.
    assertEquals(firstColumn(r), Vector("api..db", "web..api"))
    assert(r.lines.head.contains("api -> db"), r.stdout)
    assert(r.lines.head.contains("reads"), r.stdout)
    assert(r.lines(1).contains("web -> api"), r.stdout)
  }

  tmp.test("arrows --json gives name, ref, source, target and label") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("arrows", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    val reads = r.json.arr.find(_("name").str == "api..db").getOrElse(fail(r.stdout))
    assertEquals(reads("name").str, "api..db")
    assertEquals(reads("source").str, "api")
    assertEquals(reads("target").str, "db")
    assertEquals(reads("label").str, "reads")
    assert(reads("ref").str.startsWith("arrow:"), reads.render())
  }

  tmp.test("two arrows between the same nodes get #1 and #2") { dir =>
    write(dir, "two.dot", "digraph G { a -> b; a -> b }")
    val r = Run(dir)
    assertEquals(r("arrows", "two.dot"), ExitCode.Ok, r.stderr)
    assertEquals(firstColumn(r), Vector("a..b#1", "a..b#2"))
  }

  tmp.test("groups prints the name that the source gives each group") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("groups", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(firstColumn(r), Vector("cluster_svc"))
    assert(r.stdout.contains("services"), r.stdout)

    val j = Run(dir)
    assertEquals(j("groups", "arch.dot", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json.arr.head("name").str, "cluster_svc")
    assertEquals(j.json.arr.head("ref").str, "group:svc")
    assertEquals(j.json.arr.head("label").str, "services")
  }

  tmp.test("attrs prints the attributes of each element, without the internal ones") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("attrs", "arch.dot", "api", "api..db"), ExitCode.Ok, r.stderr)
    assert(r.lines.contains("api"), r.stdout)
    assert(r.lines.contains("  label=API"), r.stdout)
    assert(r.lines.contains("api..db"), r.stdout)
    assert(r.lines.contains("  label=reads"), r.stdout)
    assert(!r.stdout.contains("_gvid"), r.stdout)
  }

  tmp.test("attrs --json gives an array of objects with name and attributes") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("attrs", "arch.dot", "api", "cluster_svc", "--json"), ExitCode.Ok, r.stderr)
    val items = r.json.arr
    assertEquals(items.map(_("name").str).toVector, Vector("api", "cluster_svc"))
    assertEquals(items.head("attributes")("label").str, "API")
    assertEquals(items(1)("attributes")("label").str, "services")
    assert(!items(1)("attributes").obj.contains("name"), items(1).render())
    assert(!items.exists(_("attributes").obj.keys.exists(_.startsWith("_"))), r.stdout)
  }

  tmp.test("attrs shows a fill as DOT spells it, not as the internal attributes") { dir =>
    write(dir, "f.dot", """digraph G { a [style="filled", fillcolor="red"] }""")
    val r = Run(dir)
    assertEquals(r("attrs", "f.dot", "a", "--json"), ExitCode.Ok, r.stderr)
    val attributes = r.json.arr.head("attributes").obj
    assertEquals(attributes.get("style").map(_.str), Some("filled"))
    assert(!attributes.contains("fillstyle"), r.stdout)
  }

  tmp.test("attrs with no element is a usage error") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("attrs", "arch.dot"), ExitCode.Usage)
    assert(r.stderr.contains("element"), r.stderr)
  }

  tmp.test("a read command does not change the file, and does not touch the library") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    for command <- Seq("nodes", "arrows", "groups") do assertEquals(r(command, "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(Files.readString(f), Arch)
    assert(!Files.exists(metadataDir(dir)), "a read of the file ran the migration")
  }

  tmp.test("a file that does not exist is exit 4, and the message names the file") { dir =>
    val r = Run(dir)
    assertEquals(r("nodes", "nope.dot"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("nope.dot"), r.stderr)
    assert(r.stderr.contains("does not exist"), r.stderr)
  }

  tmp.test("a file that does not parse is exit 4") { dir =>
    write(dir, "bad.dot", "digraph G { a -> }")
    val r = Run(dir)
    assertEquals(r("nodes", "bad.dot"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("could not parse"), r.stderr)
  }

  tmp.test("a command with no file is a usage error") { dir =>
    val r = Run(dir)
    assertEquals(r("nodes"), ExitCode.Usage)
    assert(r.stderr.contains("<file>"), r.stderr)
  }

  // ------------------------------------------------------ element names

  tmp.test("an element name that names nothing is exit 4, and the file stays") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "nope", "color=red"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("'nope'"), r.stderr)
    assertEquals(Files.readString(f), Arch)
  }

  tmp.test("a malformed element name is a usage error") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "node:", "color=red"), ExitCode.Usage)
    assert(r.stderr.contains("node:"), r.stderr)
    assertEquals(Files.readString(f), Arch)

    val a = Run(dir)
    assertEquals(a("attrs", "arch.dot", ""), ExitCode.Usage)
  }

  tmp.test("an arrow written with -> says to write it with ..") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("attrs", "arch.dot", "api->db"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("api..db"), r.stderr)
  }

  // ------------------------------------------------------------- change

  tmp.test("set changes an attribute, writes the file, and prints the path and the new hash") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api", "color=red"), ExitCode.Ok, r.stderr)
    val hash = Documents.hashOf(f).getOrElse(fail("the file is gone")).hex
    assertEquals(r.stdout.trim, s"${f.toRealPath()}  $hash")

    val a = Run(dir)
    assertEquals(a("attrs", "arch.dot", "api", "--json"), ExitCode.Ok, a.stderr)
    assertEquals(a.json.arr.head("attributes")("color").str, "red")
  }

  tmp.test("set --json gives the path and the hash") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "db", "shape=box", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("path").str, f.toRealPath().toString)
    assertEquals(r.json("hash").str, Documents.hashOf(f).map(_.hex).getOrElse(fail("gone")))
  }

  tmp.test("a write gives no empty label to an arrow that has no label") { dir =>
    // The reader gives each unlabeled arrow `label=""` when a different arrow
    // has a label. Before the fix, each write printed that value, so a change
    // to one node also changed the text of each arrow with no label.
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api", "color=red"), ExitCode.Ok, r.stderr)
    val text = Files.readString(f)
    assert(text.contains("label=\"reads\""), s"precondition: the labeled arrow keeps its label:\n$text")
    assert(!text.contains("label=\"\""), s"the write added an empty label:\n$text")
  }

  tmp.test("a write keeps how the file ends: with a final newline, or with none") { dir =>
    // The same rule as for the line endings (V-04): a change to an attribute
    // must not change the last byte of the file.
    val withNewline = write(dir, "with.dot", Arch)
    val noNewline   = write(dir, "without.dot", Arch.stripSuffix("\n"))
    assert(Arch.endsWith("\n"), "precondition: the fixture ends with a newline")
    for f <- Seq(withNewline, noNewline) do
      val before = Files.readString(f)
      val r      = Run(dir)
      assertEquals(r("set", f.getFileName.toString, "api", "color=red"), ExitCode.Ok, r.stderr)
      val after = Files.readString(f)
      assert(after.contains("color"), s"precondition: the write happened in $f")
      assertEquals(after.endsWith("\n"), before.endsWith("\n"), s"the write changed the end of $f")
  }

  tmp.test("one set call can set some attributes and remove others") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api..db", "color=blue", "label="), ExitCode.Ok, r.stderr)
    val a = Run(dir)
    assertEquals(a("attrs", "arch.dot", "api..db", "--json"), ExitCode.Ok, a.stderr)
    val attributes = a.json.arr.head("attributes").obj
    assertEquals(attributes.get("color").map(_.str), Some("blue"))
    assert(!attributes.contains("label"), a.stdout)
  }

  tmp.test("set takes several elements, and a value can contain =") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api", "db", "tooltip=a=b"), ExitCode.Ok, r.stderr)
    val a = Run(dir)
    a("attrs", "arch.dot", "api", "db", "--json")
    assertEquals(a.json.arr.map(_("attributes")("tooltip").str).toVector, Vector("a=b", "a=b"))
  }

  tmp.test("set with no attribute, or with no element, is a usage error, and the file stays") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api"), ExitCode.Usage)
    assert(r.stderr.contains("<name>=<value>"), r.stderr)
    val n = Run(dir)
    assertEquals(n("set", "arch.dot", "color=red"), ExitCode.Usage)
    assert(n.stderr.contains("element"), n.stderr)
    assertEquals(Files.readString(f), Arch)
  }

  tmp.test("reset removes each attribute except the label") { dir =>
    arch(dir)
    assertEquals(Run(dir)("set", "arch.dot", "api", "color=red", "shape=box"), ExitCode.Ok)
    val r = Run(dir)
    assertEquals(r("reset", "arch.dot", "api"), ExitCode.Ok, r.stderr)
    val a = Run(dir)
    a("attrs", "arch.dot", "api", "--json")
    assertEquals(a.json.arr.head("attributes").obj.keySet.toSet, Set("label"))
  }

  tmp.test("group makes a new group, and the output names it") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("group", "arch.dot", "web", "--label", "edge", "--json"), ExitCode.Ok, r.stderr)
    val name = r.json("group").str

    val g = Run(dir)
    assertEquals(g("groups", "arch.dot", "--json"), ExitCode.Ok, g.stderr)
    val made = g.json.arr.find(_("name").str == name).getOrElse(fail(s"no group $name in ${g.stdout}"))
    assertEquals(made("label").str, "edge")
  }

  tmp.test("group refuses an arrow") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("group", "arch.dot", "api..db"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("arrow"), r.stderr)
    assertEquals(Files.readString(f), Arch)
  }

  tmp.test("ungroup removes the group, and its nodes stay") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("ungroup", "arch.dot", "cluster_svc"), ExitCode.Ok, r.stderr)
    val g = Run(dir)
    g("groups", "arch.dot", "--json")
    assertEquals(g.json.arr.size, 0, g.stdout)
    val n = Run(dir)
    n("nodes", "arch.dot")
    assertEquals(firstColumn(n).toSet, Set("api", "db", "web"))
    val a = Run(dir)
    a("arrows", "arch.dot")
    assertEquals(firstColumn(a).toSet, Set("api..db", "web..api"))
  }

  tmp.test("ungroup refuses a node") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("ungroup", "arch.dot", "api"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("group"), r.stderr)
  }

  tmp.test("combine-record, transpose-record and split-record change the record nodes") { dir =>
    write(dir, "r.dot", "digraph G { a; b; c; a -> c }")
    val c = Run(dir)
    assertEquals(c("combine-record", "r.dot", "a", "b"), ExitCode.Ok, c.stderr)

    val n = Run(dir)
    n("nodes", "r.dot")
    val record = firstColumn(n).filterNot(_ == "c") match
      case Vector(one) => one
      case other       => fail(s"expected one record node, got $other")

    val t = Run(dir)
    assertEquals(t("transpose-record", "r.dot", record), ExitCode.Ok, t.stderr)
    val s = Run(dir)
    assertEquals(s("split-record", "r.dot", record, "--json"), ExitCode.Ok, s.stderr)
    assert(s.json.obj.contains("hash"), s.stdout)
  }

  tmp.test("split-record refuses a node that is not a record, and takes exactly one node") { dir =>
    write(dir, "r.dot", "digraph G { a; b }")
    val r = Run(dir)
    assertEquals(r("split-record", "r.dot", "a"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("record"), r.stderr)
    val two = Run(dir)
    assertEquals(two("split-record", "r.dot", "a", "b"), ExitCode.Usage)
    val combine = Run(dir)
    assertEquals(combine("combine-record", "r.dot", "a"), ExitCode.InvalidPathOrPolicy)
  }

  tmp.test("a Mermaid file stays Mermaid after a change") { dir =>
    val f = write(dir, "flow.mmd", "flowchart LR\n  A --> B\n")
    val r = Run(dir)
    assertEquals(r("set", "flow.mmd", "A", "label=Start"), ExitCode.Ok, r.stderr)
    assert(Files.readString(f).startsWith("flowchart"), Files.readString(f))
    val n = Run(dir)
    n("nodes", "flow.mmd", "--json")
    assertEquals(n.json.arr.head("label").str, "Start")
  }

  // --------------------------------------------------- content and safety

  tmp.test("a change to a file with comments is refused, and the message names what it removes") { dir =>
    val text = "// generated\ndigraph G { a -> b }\n"
    val f    = write(dir, "c.dot", text)
    val r    = Run(dir)
    assertEquals(r("set", "c.dot", "a", "color=red"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("the comments"), r.stderr)
    assert(r.stderr.contains("--reformat"), r.stderr)
    assertEquals(Files.readString(f), text, "a refused change wrote the file")
  }

  tmp.test("--reformat accepts the rewrite") { dir =>
    val f = write(dir, "c.dot", "// generated\ndigraph G { a -> b }\n")
    val r = Run(dir)
    assertEquals(r("set", "c.dot", "a", "color=red", "--reformat"), ExitCode.Ok, r.stderr)
    assert(!Files.readString(f).contains("generated"), Files.readString(f))
    assert(Files.readString(f).contains("red"), Files.readString(f))
  }

  tmp.test("the refusal names the strict keyword too") { dir =>
    write(dir, "s.dot", "strict digraph G { a -> b }\n")
    val r = Run(dir)
    assertEquals(r("reset", "s.dot", "a"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("`strict`"), r.stderr)
  }

  tmp.test("a file that changes after gx reads it is a conflict: exit 5, and the file stays as the other writer left it") { dir =>
    val f     = arch(dir)
    val other = "digraph G { someone; else }\n"
    val r     = Run(dir, beforeWrite = p => Files.writeString(p, other))
    assertEquals(r("set", "arch.dot", "api", "color=red"), ExitCode.Conflict)
    assertEquals(Files.readString(f), other)
    assert(r.stderr.contains("changed"), r.stderr)
    assert(r.stderr.contains(f.toRealPath().toString), r.stderr)
  }

  tmp.test("a group keeps its source name after a write, so the next command finds it") { dir =>
    write(dir, "b.dot", "digraph G {\n  subgraph clusterB { a; b }\n  a -> b\n}\n")
    val r = Run(dir)
    assertEquals(r("set", "b.dot", "a", "color=red"), ExitCode.Ok, r.stderr)
    val g = Run(dir)
    g("groups", "b.dot")
    assertEquals(firstColumn(g), Vector("clusterB"))
    val c = Run(dir)
    assertEquals(c("collapse", "b.dot", "clusterB"), ExitCode.Ok, c.stderr)
    val s = Run(dir)
    assertEquals(s("set", "b.dot", "clusterB", "label=B"), ExitCode.Ok, s.stderr)
  }

  tmp.test("two subgraphs whose names give one id keep their members after a write") { dir =>
    // `clusterB` and `cluster_B` both had the id `B`. A write kept one
    // subgraph, and `a` or `b` left its group, with no message.
    val f = write(dir, "two.dot", "digraph G {\n  subgraph clusterB { a }\n  subgraph cluster_B { b }\n  c\n}\n")
    val r = Run(dir)
    assertEquals(r("set", "two.dot", "c", "color=red"), ExitCode.Ok, r.stderr)
    val text = Files.readString(f)
    assert("""(?s)subgraph "clusterB" \{[^}]*"a"[^}]*\}""".r.findFirstIn(text).isDefined, s"a left clusterB:\n$text")
    assert("""(?s)subgraph "cluster_B" \{[^}]*"b"[^}]*\}""".r.findFirstIn(text).isDefined, s"b left cluster_B:\n$text")
    val g = Run(dir)
    g("groups", "two.dot")
    assertEquals(firstColumn(g), Vector("clusterB", "cluster_B"))
  }

  tmp.test("an arrow to a subgraph goes to the nodes of its inner subgraphs, and a write keeps them") { dir =>
    // gx made only `x -> b`. Thus `gx arrows` did not show `x -> a`, and a
    // change to a different element removed it from the file, with no message.
    val f = write(dir, "in.dot", "digraph G {\n  x -> subgraph s { subgraph t { a } b }\n}\n")
    val r = Run(dir)
    assertEquals(r("arrows", "in.dot"), ExitCode.Ok, r.stderr)
    assertEquals(firstColumn(r), Vector("x..a", "x..b"))
    val s = Run(dir)
    assertEquals(s("set", "in.dot", "b", "color=red"), ExitCode.Ok, s.stderr)
    val text = Files.readString(f)
    for target <- Vector("a", "b") do
      assert(text.contains(s""""x" -> "$target""""), s"the write lost x -> $target:\n$text")
  }

  tmp.test("a write keeps an id attribute, and gx set can write one") { dir =>
    // The DOT writer left out each `id`. Thus a change to one node removed the
    // `id` of the others, and gx refused `set … id=…`.
    val f = write(dir, "i.dot", "digraph G {\n  a [id=\"keep\"]\n  a -> b\n}\n")
    val r = Run(dir)
    assertEquals(r("set", "i.dot", "b", "color=red"), ExitCode.Ok, r.stderr)
    assert(Files.readString(f).contains("id=\"keep\""), s"the write dropped the id of a:\n${Files.readString(f)}")
    val s = Run(dir)
    assertEquals(s("set", "i.dot", "b", "id=new"), ExitCode.Ok, s.stderr)
    val text = Files.readString(f)
    assert(text.contains("id=\"new\""), s"gx set did not write the id of b:\n$text")
    assert(text.contains("id=\"keep\""), s"gx set dropped the id of a:\n$text")
  }

  tmp.test("a write is audited with the injected clock") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("set", "arch.dot", "api", "color=red"), ExitCode.Ok, r.stderr)
    val line  = r.env.audit.entries.lastOption.getOrElse(fail("nothing was audited"))
    val event = ujson.read(line)
    assertEquals(event("event").str, "written")
    val stamp = event("timestampMs").num.toLong
    assert(stamp >= 1000L && stamp < 2000L, s"audit used a clock the test does not control: $stamp")
  }

  tmp.test("a denied path is refused with exit 4 and recorded") { dir =>
    val secret = Files.createDirectories(dir.resolve("secrets"))
    Files.writeString(secret.resolve("a.dot"), "digraph { a }")
    val r   = Run(dir)
    val env = r.env.copy(policy = AccessPolicy(Nil, List(secret)))
    assertEquals(Cli.run(Vector("nodes", "secrets/a.dot"), env), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("denied root"), r.stderr)
    assert(r.env.audit.entries.exists(_.contains("\"denied\"")), r.env.audit.entries.mkString("\n"))
  }

  // ----------------------------------------------------------- metadata

  tmp.test("hide on a file that is not in the library puts it there, and does not change the file") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("hide", "arch.dot", "api", "api..db"), ExitCode.Ok, r.stderr)
    val e = r.entry(f).getOrElse(fail("hide did not make an entry"))
    assertEquals(e.metadata.hiddenElements.size, 2)
    assert(e.metadata.hiddenElements.contains("node:api"), e.metadata.hiddenElements.toString)
    assertEquals(Files.readString(f), Arch, "a metadata command changed the file")
    assert(r.stdout.contains("api..db"), r.stdout)
  }

  tmp.test("hide --json gives the metadata of the diagram") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("hide", "arch.dot", "api", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("hidden").arr.map(_.str).toVector, Vector("api"))
    assertEquals(r.json("inLibrary").bool, true)
  }

  tmp.test("unhide on a file with no entry does nothing and exits 0") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("unhide", "arch.dot", "api"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f), None, "a removal made an entry")
    assert(r.stdout.contains("not in the library"), r.stdout)
  }

  tmp.test("unhide removes what it names, and --all removes each one") { dir =>
    val f = arch(dir)
    Run(dir)("hide", "arch.dot", "api", "db", "web")
    val r = Run(dir)
    assertEquals(r("unhide", "arch.dot", "db"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.hiddenElements), Some(Set("node:api", "node:web")))
    val a = Run(dir)
    assertEquals(a("unhide", "arch.dot", "--all", "--json"), ExitCode.Ok, a.stderr)
    assertEquals(a.json("hidden").arr.size, 0)
  }

  tmp.test("unhide takes elements or --all, not both and not neither") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("unhide", "arch.dot"), ExitCode.Usage)
    val b = Run(dir)
    assertEquals(b("unhide", "arch.dot", "api", "--all"), ExitCode.Usage)
  }

  tmp.test("unhide takes the ref spelling of an element that the file no longer has") { dir =>
    val f = arch(dir)
    Run(dir)("hide", "arch.dot", "web")
    Files.writeString(f, "digraph G { api; db }")
    val r = Run(dir)
    assertEquals(r("unhide", "arch.dot", "node:web"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.hiddenElements), Some(Set.empty[String]))
  }

  tmp.test("collapse and expand take groups") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("collapse", "arch.dot", "cluster_svc"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.collapsedGroups), Some(Set("group:svc")))
    val n = Run(dir)
    assertEquals(n("collapse", "arch.dot", "api"), ExitCode.InvalidPathOrPolicy)
    assert(n.stderr.contains("group"), n.stderr)
    val e = Run(dir)
    assertEquals(e("expand", "arch.dot", "cluster_svc", "--json"), ExitCode.Ok, e.stderr)
    assertEquals(e.json("collapsed").arr.size, 0)
    Run(dir)("collapse", "arch.dot", "cluster_svc")
    val all = Run(dir)
    assertEquals(all("expand", "arch.dot", "--all"), ExitCode.Ok, all.stderr)
    assertEquals(all.entry(f).map(_.metadata.collapsedGroups), Some(Set.empty[String]))
  }

  tmp.test("tag and untag change the tags, in the order given") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("tag", "arch.dot", "infra", "draft"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.tags), Some(List("infra", "draft")))
    val u = Run(dir)
    assertEquals(u("untag", "arch.dot", "infra", "--json"), ExitCode.Ok, u.stderr)
    assertEquals(u.json("tags").arr.map(_.str).toVector, Vector("draft"))
  }

  tmp.test("tag of a file that does not exist is exit 4, and no entry is made") { dir =>
    val r = Run(dir)
    assertEquals(r("tag", "nope.dot", "x"), ExitCode.InvalidPathOrPolicy)
    assertEquals(r.library.list(), Vector.empty)
  }

  tmp.test("untag on a file with no entry does nothing and exits 0") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("untag", "arch.dot", "x"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f), None)
  }

  tmp.test("note sets the notes from an argument or from stdin, and --clear removes them") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("note", "arch.dot", "the services"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.notes), Some("the services"))
    val s = Run(dir, stdinText = "line one\nline two\n")
    assertEquals(s("note", "arch.dot", "--stdin"), ExitCode.Ok, s.stderr)
    assertEquals(s.entry(f).map(_.metadata.notes), Some("line one\nline two"))
    val c = Run(dir)
    assertEquals(c("note", "arch.dot", "--clear", "--json"), ExitCode.Ok, c.stderr)
    assertEquals(c.json("notes").str, "")
  }

  tmp.test("note needs exactly one of a text, --stdin and --clear") { dir =>
    arch(dir)
    for args <- Seq(Seq("note", "arch.dot"), Seq("note", "arch.dot", "a", "--clear"), Seq("note", "arch.dot", "a", "b")) do
      val r = Run(dir)
      assertEquals(r(args*), ExitCode.Usage, args.mkString(" "))
  }

  tmp.test("note --clear on a file with no entry does nothing and exits 0") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("note", "arch.dot", "--clear"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f), None)
  }

  tmp.test("-- ends the flags, so a note can start with --") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("note", "arch.dot", "--", "--draft"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f).map(_.metadata.notes), Some("--draft"))
  }

  tmp.test("info prints the metadata, with element names") { dir =>
    val f = arch(dir)
    Run(dir)("hide", "arch.dot", "api..db")
    Run(dir)("collapse", "arch.dot", "cluster_svc")
    Run(dir)("tag", "arch.dot", "infra")
    val r = Run(dir)
    assertEquals(r("info", "arch.dot"), ExitCode.Ok, r.stderr)
    val fields = r.lines.map(l => l.takeWhile(_ != ':') -> l.dropWhile(_ != ':').drop(1).trim).toMap
    assertEquals(fields.get("path"), Some(f.toRealPath().toString))
    assertEquals(fields.get("inLibrary"), Some("true"))
    assertEquals(fields.get("tags"), Some("infra"))
    assertEquals(fields.get("hidden"), Some("api..db"))
    assertEquals(fields.get("collapsed"), Some("cluster_svc"))
    assert(fields.contains("notes") && fields.contains("autoDetectFormat"), r.stdout)
  }

  tmp.test("info --json has exactly the fields of the plan") { dir =>
    arch(dir)
    Run(dir)("hide", "arch.dot", "api")
    val r = Run(dir)
    assertEquals(r("info", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(
      r.json.obj.keySet.toSet,
      Set("path", "inLibrary", "tags", "notes", "hidden", "collapsed", "autoDetectFormat")
    )
    assertEquals(r.json("hidden").arr.map(_.str).toVector, Vector("api"))
    assertEquals(r.json("autoDetectFormat"), ujson.Null)
  }

  tmp.test("info on a file that is not in the library says so, and makes no entry") { dir =>
    val f = arch(dir)
    val r = Run(dir)
    assertEquals(r("info", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("inLibrary").bool, false)
    assertEquals(r.entry(f), None)
  }

  tmp.test("info prints the ref spelling when the file does not parse") { dir =>
    val f = arch(dir)
    Run(dir)("hide", "arch.dot", "api")
    Files.writeString(f, "digraph G { a -> }")
    val r = Run(dir)
    assertEquals(r("info", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("hidden").arr.map(_.str).toVector, Vector("node:api"))
  }

  tmp.test("info on a file that does not exist and has no entry is exit 4") { dir =>
    val r = Run(dir)
    assertEquals(r("info", "nope.dot"), ExitCode.InvalidPathOrPolicy)
  }

  tmp.test("ls lists each entry with its tags") { dir =>
    val a = arch(dir)
    val b = write(dir, "b.dot", "digraph { x }")
    Run(dir)("tag", "arch.dot", "infra")
    Run(dir)("add", "b.dot")
    val r = Run(dir)
    assertEquals(r("ls"), ExitCode.Ok, r.stderr)
    assertEquals(r.lines.size, 2, r.stdout)
    assert(r.lines.exists(l => l.startsWith(a.toRealPath().toString) && l.contains("infra")), r.stdout)
    assert(r.lines.exists(_.startsWith(b.toRealPath().toString)), r.stdout)

    val j = Run(dir)
    assertEquals(j("ls", "--json"), ExitCode.Ok, j.stderr)
    val items = j.json.arr
    assertEquals(items.map(_("path").str).toSet, Set(a.toRealPath().toString, b.toRealPath().toString))
    assert(items.forall(i => i.obj.contains("tags") && !i("missing").bool), j.stdout)
  }

  tmp.test("ls --tag keeps the entries with that tag") { dir =>
    val a = arch(dir)
    write(dir, "b.dot", "digraph { x }")
    Run(dir)("tag", "arch.dot", "infra")
    Run(dir)("tag", "b.dot", "other")
    val r = Run(dir)
    assertEquals(r("ls", "--tag", "infra", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json.arr.map(_("path").str).toVector, Vector(a.toRealPath().toString))
  }

  tmp.test("ls marks an entry whose file is missing") { dir =>
    val f    = arch(dir)
    val path = f.toRealPath().toString
    Run(dir)("add", "arch.dot")
    Files.delete(f)
    val r = Run(dir)
    assertEquals(r("ls"), ExitCode.Ok, r.stderr)
    assert(r.lines.exists(l => l.startsWith(path) && l.endsWith("missing")), r.stdout)
    val j = Run(dir)
    j("ls", "--json")
    assertEquals(j.json.arr.head("missing").bool, true)
  }

  tmp.test("ls of an empty library says so") { dir =>
    val r = Run(dir)
    assertEquals(r("ls"), ExitCode.Ok, r.stderr)
    assertEquals(r.stdout.trim, "(no diagrams)")
    val j = Run(dir)
    j("ls", "--json")
    assertEquals(j.json, ujson.Arr())
  }

  tmp.test("add puts files in the library, and a second add changes nothing") { dir =>
    val a = arch(dir)
    write(dir, "b.dot", "digraph { x }")
    val r = Run(dir)
    assertEquals(r("add", "arch.dot", "b.dot", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json.arr.map(_("added").bool).toVector, Vector(true, true))
    // V-08: each path that gx lets into the library is in the audit log.
    assert(r.env.audit.entries.count(_.contains("\"allowed\"")) == 2, r.env.audit.entries.mkString("\n"))
    val before = r.entry(a)
    val again  = Run(dir)
    assertEquals(again("add", "arch.dot"), ExitCode.Ok, again.stderr)
    assert(again.stdout.contains("already"), again.stdout)
    assertEquals(again.entry(a), before)
  }

  tmp.test("add of a file that does not exist is exit 4, and adds nothing") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("add", "arch.dot", "nope.dot"), ExitCode.InvalidPathOrPolicy)
    assert(r.stderr.contains("nope.dot"), r.stderr)
    assertEquals(r.library.list(), Vector.empty, "add wrote some entries before it failed")
  }

  tmp.test("remove takes a diagram out of the library, and the file stays") { dir =>
    val f = arch(dir)
    Run(dir)("tag", "arch.dot", "x")
    val r = Run(dir)
    assertEquals(r("remove", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.entry(f), None)
    assertEquals(Files.readString(f), Arch)
    val again = Run(dir)
    assertEquals(again("remove", "arch.dot", "--json"), ExitCode.Ok, again.stderr)
    assertEquals(again.json.arr.head("removed").bool, false)
  }

  tmp.test("remove works on an entry whose file is missing") { dir =>
    val f    = arch(dir)
    val path = f.toRealPath().toString
    Run(dir)("add", "arch.dot")
    Files.delete(f)
    val r = Run(dir)
    assertEquals(r("remove", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.library.get(path).isLeft, true)
  }

  tmp.test("a metadata command names the file by its canonical path") { dir =>
    val f = arch(dir)
    Files.createDirectories(dir.resolve("sub"))
    val r = Run(dir)
    assertEquals(r("tag", "sub/../arch.dot", "x"), ExitCode.Ok, r.stderr)
    assertEquals(r.library.list().map(_.path), Vector(f.toRealPath().toString))
  }

  // ---------------------------------------------------------- migration

  /** Write an old record into `diagrams/`, in the shape of the old writer
    * (gx-core `LegacyRecordSpec` pins it). `origin` is a `file:` URI.
    */
  private def oldRecord(dir: Path, id: String, text: String, origin: Option[String] = None,
                        hidden: Set[String] = Set.empty): Unit =
    val diagrams = GxHome.libraryDir(dir.resolve("gx-home")).resolve("diagrams")
    Files.createDirectories(diagrams)
    val json = ujson.Obj(
      "id"     -> ujson.Obj("value" -> id),
      "name"   -> id,
      "folder" -> ujson.Obj("segments" -> ujson.Arr()),
      "format" -> "DOT",
      "text"   -> text,
      "binding" -> origin.fold(ujson.Null)(o => ujson.Obj("origin" -> o, "mode" -> "Pull", "baseHash" -> "00", "lastSyncAt" -> 1)),
      "metadata"  -> (if hidden.isEmpty then ujson.Obj() else ujson.Obj("hiddenElements" -> ujson.Arr.from(hidden.toSeq.sorted))),
      "createdAt" -> 1,
      "updatedAt" -> 1
    )
    Files.writeString(diagrams.resolve(s"$id.json"), ujson.write(json, indent = 2))

  tmp.test("the migration runs once, before the first library command, and reports to stderr") { dir =>
    oldRecord(dir, "Notes", "digraph Notes { x }")
    val r = Run(dir)
    assertEquals(r("ls", "--json"), ExitCode.Ok, r.stderr)
    assert(Files.isDirectory(metadataDir(dir)), "ls did not run the migration")
    assert(r.stderr.contains("Notes"), r.stderr)
    val made = dir.resolve("diagrams").resolve("Notes.dot")
    assertEquals(Files.readString(made), "digraph Notes { x }")
    assertEquals(r.json.arr.map(_("path").str).toVector, Vector(made.toRealPath().toString))

    val again = Run(dir)
    assertEquals(again("ls"), ExitCode.Ok, again.stderr)
    assertEquals(again.stderr, "", "the migration ran a second time")
  }

  tmp.test("the metadata of a record with a file moves to that file") { dir =>
    val f = arch(dir)
    oldRecord(
      dir,
      "arch",
      Arch,
      origin = Some(FileOrigins.originOf(f, dir).value),
      hidden = Set("node:api")
    )
    val r = Run(dir)
    assertEquals(r("info", "arch.dot", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("inLibrary").bool, true)
    assertEquals(r.json("hidden").arr.map(_.str).toVector, Vector("api"))
  }

  tmp.test("only a library command runs the migration") { dir =>
    oldRecord(dir, "Notes", "digraph Notes { x }")
    arch(dir)
    for args <- Seq(
        Seq("help"),
        Seq("--version"),
        Seq("skill"),
        Seq("nodes", "arch.dot"),
        Seq("arrows", "arch.dot"),
        Seq("groups", "arch.dot"),
        Seq("attrs", "arch.dot", "api"),
        Seq("set", "arch.dot", "api", "color=red"),
        Seq("run", "arch.dot", "list-nodes"),
        Seq("frobnicate")
      )
    do
      Run(dir)(args*)
      assert(!Files.exists(metadataDir(dir)), s"'${args.mkString(" ")}' ran the migration")
    assert(!Files.exists(dir.resolve("diagrams")), "a command that touches only the file wrote a new diagram")

    for args <- Seq(
        Seq("status"),
        Seq("ls"),
        Seq("info", "arch.dot"),
        Seq("add", "arch.dot"),
        Seq("remove", "arch.dot"),
        Seq("hide", "arch.dot", "api"),
        Seq("unhide", "arch.dot", "api"),
        Seq("collapse", "arch.dot", "cluster_svc"),
        Seq("expand", "arch.dot", "--all"),
        Seq("tag", "arch.dot", "x"),
        Seq("untag", "arch.dot", "x"),
        Seq("note", "arch.dot", "n")
      )
    do
      // Each library command starts with the old records and no metadata/.
      if Files.exists(metadataDir(dir)) then
        Files.walk(metadataDir(dir)).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      val c = Run(dir)
      assertEquals(c(args*), ExitCode.Ok, s"${args.mkString(" ")}: ${c.stderr}")
      assert(Files.isDirectory(metadataDir(dir)), s"'${args.mkString(" ")}' did not run the migration")
  }

  // ------------------------------------------------------------- desktop

  tmp.test("open sends `show` with the canonical path of the file") { dir =>
    val f = arch(dir)
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> true)))
    assertEquals(r("open", "./arch.dot"), ExitCode.Ok, r.stderr)
    val (method, params) = r.sent.headOption.getOrElse(fail("nothing was sent"))
    assertEquals(method, "show")
    assertEquals(params("target")("kind").str, "file")
    assertEquals(params("target")("path").str, f.toRealPath().toString)
    assert(r.stdout.contains("showing"), r.stdout)

    val j = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> true)))
    assertEquals(j("open", "arch.dot", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json("path").str, f.toRealPath().toString)
    assertEquals(j.json("focused").bool, true)
  }

  tmp.test("open with no desktop is exit 2, and says that each other command works") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("open", "arch.dot"), ExitCode.NeedsDesktop)
    assert(r.stderr.contains("no desktop is running"), r.stderr)
    assert(r.stderr.contains("every other gx command works without it"), r.stderr)
  }

  tmp.test("open says so when there was no window to raise") { dir =>
    arch(dir)
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> false)))
    assertEquals(r("open", "arch.dot"), ExitCode.Ok, r.stderr)
    assert(r.stderr.contains("no window to raise"), r.stderr)
  }

  tmp.test("a desktop with no window is exit 2, and a refusal of the desktop is exit 4") { dir =>
    arch(dir)
    val w = Run(dir, answer = (_, _) => Left(ChannelError.Rpc("NO_WINDOW", "no window", ujson.Obj())))
    assertEquals(w("open", "arch.dot"), ExitCode.NeedsDesktop)
    assert(!w.stderr.contains("refused"), w.stderr)
    val d = Run(dir, answer = (_, _) => Left(ChannelError.Rpc("WATCH_FAILED", "blocked by denylist", ujson.Obj())))
    assertEquals(d("open", "arch.dot"), ExitCode.InvalidPathOrPolicy)
    assert(d.stderr.contains("blocked by denylist"), d.stderr)
  }

  tmp.test("open of a file that does not exist is exit 4, and nothing goes to the desktop") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("focused" -> true)))
    assertEquals(r("open", "nope.dot"), ExitCode.InvalidPathOrPolicy)
    assert(r.sent.isEmpty)
  }

  tmp.test("select resolves the names against the file, and sends the refs and the path") { dir =>
    val f = arch(dir)
    val r = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(r("select", "arch.dot", "api", "api..db"), ExitCode.Ok, r.stderr)
    val (method, params) = r.sent.headOption.getOrElse(fail("nothing was sent"))
    assertEquals(method, "live-view")
    assertEquals(params("command").str, "select")
    val targets = params("params")("targets").arr.map(_.str).toVector
    assert(targets.contains("node:api") && targets.exists(_.startsWith("arrow:")), targets.toString)
    assertEquals(params("params")("path").str, f.toRealPath().toString)

    val j = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(j("select", "arch.dot", "api", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json("selected").arr.map(_.str).toVector, Vector("node:api"))
    assertEquals(j.json("path").str, f.toRealPath().toString)
  }

  tmp.test("select of a name that names nothing is exit 4, and nothing goes to the desktop") { dir =>
    arch(dir)
    val r = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(r("select", "arch.dot", "nope"), ExitCode.InvalidPathOrPolicy)
    assert(r.sent.isEmpty)
  }

  tmp.test("select with no desktop, or with nothing on the screen, is exit 2") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("select", "arch.dot", "api"), ExitCode.NeedsDesktop)
    assert(r.stderr.contains("no desktop is running"), r.stderr)
    val n = Run(dir, answer = (_, _) => Left(ChannelError.Rpc("NO_LIVE_VIEW", "the desktop has none open", ujson.Obj())))
    assertEquals(n("select", "arch.dot", "api"), ExitCode.NeedsDesktop)
    assert(n.stderr.contains("none open"), n.stderr)
  }

  /** A desktop that refuses a live view command for a different diagram, as
    * the page does (`LiveViewCommands`).
    */
  private def showing(onScreen: String): (String, ujson.Obj) => Either[ChannelError, ujson.Value] =
    (_, frame) =>
      val path = frame("params").obj.get("path").flatMap(_.strOpt).getOrElse("")
      if path == onScreen then Right(ujson.Null)
      else Left(ChannelError.Rpc("VIEW_REJECTED", s"the desktop shows $onScreen, not $path", ujson.Obj()))

  tmp.test("select for a diagram that is not on the screen is exit 4, and says what is on the screen and what to do") {
    dir =>
      val f = arch(dir).toRealPath().toString
      val r = Run(dir, answer = showing("/w/other.dot"))
      assertEquals(r("select", "arch.dot", "api"), ExitCode.InvalidPathOrPolicy, r.stderr)
      assert(r.stderr.contains("the desktop shows /w/other.dot, not " + f), r.stderr)
      assert(r.stderr.contains("gx open " + f), r.stderr)
      assertEquals(r.stdout, "")
      val ok = Run(dir, answer = showing(f))
      assertEquals(ok("select", "arch.dot", "api"), ExitCode.Ok, ok.stderr)
  }

  tmp.test("fit for a diagram that is not on the screen is exit 4, and says what is on the screen") { dir =>
    val f = arch(dir).toRealPath().toString
    val r = Run(dir, answer = (_, _) => Left(ChannelError.Rpc("VIEW_REJECTED", "the desktop shows an example (\"Logo\"), not " + f, ujson.Obj())))
    assertEquals(r("fit", "arch.dot"), ExitCode.InvalidPathOrPolicy, r.stderr)
    assert(r.stderr.contains("an example (\"Logo\")"), r.stderr)
    assert(r.stderr.contains("gx open " + f), r.stderr)
  }

  /** The answer of the page to `what-is-selected`. */
  private def selectionAnswer(path: Option[String], shows: String, refs: String*): ujson.Value =
    ujson.Obj("path" -> path.fold(ujson.Null)(ujson.Str(_)), "shows" -> shows, "selection" -> ujson.Arr.from(refs))

  tmp.test("selection prints the names of the selected elements and the path of the file") { dir =>
    val f        = arch(dir).toRealPath().toString
    val arrowRef = Run(dir).pipe(a => { a("arrows", "arch.dot", "--json"); a.json.arr.find(_("name").str == "api..db").get("ref").str })
    val answer   = selectionAnswer(Some(f), f, "node:api", arrowRef)
    val r        = Run(dir, answer = (_, _) => Right(answer))
    assertEquals(r("selection"), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.head._1, "live-view")
    assertEquals(r.sent.head._2("command").str, "what-is-selected")
    assertEquals(r.lines, Vector(s"$f  selected: api, api..db"))
    val j = Run(dir, answer = (_, _) => Right(answer))
    assertEquals(j("selection", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json, ujson.Obj("path" -> f, "selection" -> ujson.Arr("api", "api..db")))
    val e = Run(dir, answer = (_, _) => Right(selectionAnswer(Some(f), f)))
    assertEquals(e("selection"), ExitCode.Ok, e.stderr)
    assertEquals(e.lines, Vector(s"$f  nothing selected"))
  }

  tmp.test("selection says so when the screen shows no file, and gives the refs") { dir =>
    val answer = selectionAnswer(None, "an example (\"Logo\")", "node:a")
    val r      = Run(dir, answer = (_, _) => Right(answer))
    assertEquals(r("selection"), ExitCode.Ok, r.stderr)
    assert(r.stderr.contains("the desktop shows an example (\"Logo\")"), r.stderr)
    assert(r.stderr.contains("not a file"), r.stderr)
    assertEquals(r.lines, Vector("selected: node:a"))
    val j = Run(dir, answer = (_, _) => Right(answer))
    assertEquals(j("selection", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json, ujson.Obj("path" -> ujson.Null, "selection" -> ujson.Arr("node:a")))
  }

  tmp.test("selection gives the refs when gx cannot read the file on the screen") { dir =>
    val gone = dir.resolve("gone.dot").toString
    val r    = Run(dir, answer = (_, _) => Right(selectionAnswer(Some(gone), gone, "node:a")))
    assertEquals(r("selection", "--json"), ExitCode.Ok, r.stderr)
    assert(r.stderr.contains(gone), r.stderr)
    assertEquals(r.json, ujson.Obj("path" -> gone, "selection" -> ujson.Arr("node:a")))
  }

  tmp.test("selection with nothing on the screen is exit 2, as before") { dir =>
    val n = Run(dir, answer = (_, _) => Left(ChannelError.Rpc("NO_LIVE_VIEW", "no diagram is open", ujson.Obj())))
    assertEquals(n("selection"), ExitCode.NeedsDesktop)
    assert(n.stderr.contains("no diagram is open"), n.stderr)
  }

  tmp.test("selection refuses an answer that it cannot read, and says to use one version of the desktop and gx") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Arr("node:a")))
    assertEquals(r("selection"), ExitCode.Unknown, r.stderr)
    assert(r.stderr.contains("same version"), r.stderr)
  }

  tmp.test("fit sends reset-view with the path") { dir =>
    val f = arch(dir)
    val r = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(r("fit", "arch.dot"), ExitCode.Ok, r.stderr)
    assertEquals(r.sent.head._2("command").str, "reset-view")
    assertEquals(r.sent.head._2("params")("path").str, f.toRealPath().toString)
    val j = Run(dir, answer = (_, _) => Right(ujson.Null))
    assertEquals(j("fit", "arch.dot", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json("path").str, f.toRealPath().toString)
  }

  // -------------------------------------------------------------- status

  tmp.test("status reports the library and a missing desktop, and exits 0") { dir =>
    arch(dir)
    Run(dir)("add", "arch.dot")
    val r = Run(dir)
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains(metadataDir(dir).toString), r.stdout)
    assert(r.stdout.contains("diagrams: 1"), r.stdout)
    assert(r.stdout.contains("not running"), r.stdout)
    val j = Run(dir)
    assertEquals(j("status", "--json"), ExitCode.Ok, j.stderr)
    assertEquals(j.json("diagrams").num.toInt, 1)
    assertEquals(j.json("desktopRunning").bool, false)
  }

  tmp.test("status says what the desktop is watching, with a plural that is a plural") { dir =>
    def withWatches(n: Int) =
      Right(ujson.Obj("running" -> true, "watches" -> ujson.Arr.from((1 to n).map(i => ujson.Obj("path" -> s"/tmp/$i.dot")))))
    val none = Run(dir, answer = (_, _) => withWatches(0))
    none("status")
    assert(none.stdout.contains("running (watching nothing)"), none.stdout)
    val one = Run(dir, answer = (_, _) => withWatches(1))
    one("status")
    assert(one.stdout.contains("running (watching 1 file)"), one.stdout)
    val two = Run(dir, answer = (_, _) => withWatches(2))
    two("status")
    assert(two.stdout.contains("running (watching 2 files)"), two.stdout)
  }

  tmp.test("a starting desktop is starting, not missing") { dir =>
    val r = Run(dir, answer = (_, _) => Right(ujson.Obj("running" -> false, "state" -> "starting", "watches" -> ujson.Arr())))
    assertEquals(r("status"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains("starting"), r.stdout)
    assert(!r.stdout.contains("not running"), r.stdout)
    val j = Run(dir, answer = (_, _) => Right(ujson.Obj("running" -> false, "state" -> "starting", "watches" -> ujson.Arr())))
    j("status", "--json")
    assertEquals(j.json("desktopStarting").bool, true)
    assertEquals(j.json("desktopRunning").bool, false)
  }

  // --------------------------------------------------------------- watch

  tmp.test("watch streams a change to stdout with no desktop") { dir =>
    val f = write(dir, "gen.dot", "digraph { v1 }")
    val w = Run(dir, duringWatch = () => Files.writeString(f, "digraph { v2 }"))
    assertEquals(w("watch", "gen.dot", "--interval", "0"), ExitCode.Ok, w.stderr)
    assert(w.stdout.contains("changed"), s"stdout=${w.stdout} stderr=${w.stderr}")
    assert(w.stdout.contains(f.toRealPath().toString), w.stdout)
  }

  tmp.test("watch --json prints one JSON object per line") { dir =>
    val f = write(dir, "gen.dot", "digraph { v1 }")
    val w = Run(dir, duringWatch = () => Files.writeString(f, "digraph { v2 }"))
    w("watch", "gen.dot", "--json", "--interval", "0")
    assert(w.stdout.contains("\"event\":\"changed\""), w.stdout)
    ujson.read(w.stdout.linesIterator.next())
  }

  tmp.test("watch reports a deleted file") { dir =>
    val f = write(dir, "gen.dot", "digraph { v1 }")
    val w = Run(dir, duringWatch = () => Files.deleteIfExists(f))
    assertEquals(w("watch", "gen.dot", "--interval", "0"), ExitCode.Ok, w.stderr)
    assert(w.stdout.contains("deleted"), w.stdout)
  }

  tmp.test("watch with no file is a usage error, and watch refuses a denied path") { dir =>
    val r = Run(dir)
    assertEquals(r("watch"), ExitCode.Usage)
    val secret = Files.createDirectories(dir.resolve("secrets"))
    Files.writeString(secret.resolve("a.dot"), "x")
    val d   = Run(dir)
    val env = d.env.copy(policy = AccessPolicy(Nil, List(secret)))
    assertEquals(Cli.run(Vector("watch", "secrets/a.dot"), env), ExitCode.InvalidPathOrPolicy)
    assert(!d.stderr.contains("watching "), d.stderr)
  }

  // ------------------------------------------------------ removed commands

  for name <- CliSpec.GoneCommands do
    tmp.test(s"'$name' is gone: exit 1, and one line names the replacement") { dir =>
      arch(dir)
      val r = Run(dir, answer = (_, _) => Right(ujson.Null))
      assertEquals(r(name, "arch.dot", "x"), ExitCode.Usage)
      val lines = r.stderr.linesIterator.toVector
      assertEquals(lines.size, 1, r.stderr)
      assert(lines.head.startsWith(s"gx: '$name' is gone; "), lines.head)
      assert(lines.head.contains("gx help"), lines.head)
      assert(r.sent.isEmpty, "a removed command reached the desktop")
      assert(!Files.exists(metadataDir(dir)), "a removed command ran the migration")
    }

  tmp.test("'run' names a verb that replaces it") { dir =>
    val r = Run(dir)
    r("run", "arch.dot", "list-nodes")
    assertEquals(r.stderr.trim, "gx: 'run' is gone; use a verb such as 'gx set <file> …' (see 'gx help')")
  }

  tmp.test("a removed flag is a usage error with one line that names what to do") { dir =>
    val f = arch(dir)
    for args <- Seq(
        Seq("set", "arch.dot", "--stdin"),
        Seq("set", "arch.dot", "--text", "digraph {}"),
        Seq("ls", "--folder", "/x"),
        Seq("open", "arch.dot", "--loose"),
        Seq("nodes", "arch.dot", "--mode", "pull"),
        Seq("set", "arch.dot", "api", "color=red", "--base", "00"),
        Seq("tag", "arch.dot", "x", "--params", "{}")
      )
    do
      val r = Run(dir)
      assertEquals(r(args*), ExitCode.Usage, args.mkString(" "))
      val lines = r.stderr.linesIterator.toVector
      assertEquals(lines.size, 1, r.stderr)
      assert(lines.head.contains("is gone"), lines.head)
    assertEquals(Files.readString(f), Arch)
  }

  tmp.test("an unknown flag is refused, and the message names the flags that the command takes") { dir =>
    arch(dir)
    val r = Run(dir)
    assertEquals(r("nodes", "arch.dot", "--frob"), ExitCode.Usage)
    assert(r.stderr.contains("--frob"), r.stderr)
    assert(r.stderr.contains("--json"), r.stderr)
  }

  // -------------------------------------------------------------- basics

  tmp.test("no arguments prints the usage and exits 1, help exits 0") { dir =>
    val r = Run(dir)
    assertEquals(r(), ExitCode.Usage)
    assert(r.stdout.contains("gx set     <file> <element>... <name>=<value>..."), r.stdout)
    val h = Run(dir)
    assertEquals(h("help"), ExitCode.Ok)
    assertEquals(Run(dir)("--help"), ExitCode.Ok)
  }

  tmp.test("an unknown command is refused, not ignored") { dir =>
    val r = Run(dir)
    assertEquals(r("frobnicate"), ExitCode.Usage)
    assert(r.stderr.contains("unknown command 'frobnicate'"), r.stderr)
    assert(r.stderr.contains("gx help"), r.stderr)
  }

  tmp.test("the usage names each command of the plan, and no removed one") { dir =>
    val r = Run(dir)
    r("help")
    for command <- Seq(
        "nodes", "arrows", "groups", "attrs", "set", "reset", "group", "ungroup", "combine-record",
        "split-record", "transpose-record", "hide", "unhide", "collapse", "expand", "tag", "untag", "note",
        "info", "ls", "add", "remove", "open", "select", "selection", "fit", "status", "watch", "skill"
      )
    do assert(r.stdout.contains(s"gx $command"), s"the usage does not name $command")
    for gone <- Seq("gx import", "gx get", "gx bind", "gx sync", "gx run", "gx view", "--mode") do
      assert(!r.stdout.contains(gone), s"the usage still names $gone")
  }

  // --------------------------------------------------------------- skill

  tmp.test("skill prints a location and an instruction, and writes nothing") { dir =>
    val r = Run(dir)
    assertEquals(r("skill"), ExitCode.Ok, r.stderr)
    assert(r.stdout.contains(SkillLocation.File), r.stdout)
    assert(r.stdout.contains("Tell your coding agent"), r.stdout)
    assert(!Files.exists(dir.resolve(SkillLocation.File)), "skill must not install anything")
  }

  tmp.test("skill --json is machine-readable and says whether it is pinned") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("skill").str, SkillLocation.Name)
    assert(r.json("raw").str.endsWith(SkillLocation.File), r.json("raw").str)
    r.json("pinned").bool
  }

  tmp.test("skill takes an explicit version and pins to that tag") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "0.9.4", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("ref").str, "v0.9.4")
    assertEquals(r.json("pinned").bool, true)
  }

  tmp.test("skill refuses something that is not a version, and will not guess between --latest and a version") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "yesterday"), ExitCode.Usage)
    assert(r.stderr.contains("not a version"), r.stderr)
    val b = Run(dir)
    assertEquals(b("skill", "0.9.4", "--latest"), ExitCode.Usage)
    assert(b.stderr.contains("pick one"), b.stderr)
  }

  tmp.test("skill --latest is the branch tip, and not pinned") { dir =>
    val r = Run(dir)
    assertEquals(r("skill", "--latest", "--json"), ExitCode.Ok, r.stderr)
    assertEquals(r.json("ref").str, SkillLocation.DefaultBranch)
    assertEquals(r.json("pinned").bool, false)
  }

  test("a released version resolves to its tag; a dev build falls back to the tip") {
    val release = SkillLocation.resolve(None, latest = false, running = "0.9.4")
    assertEquals(release.map(_.ref), Right("v0.9.4"))
    assertEquals(release.map(_.pinned), Right(true))

    val dev = SkillLocation.resolve(None, latest = false, running = "0.9.3+13-2b8d0a46+20260730-2334")
    assertEquals(dev.map(_.ref), Right(SkillLocation.DefaultBranch))
    assertEquals(dev.map(_.pinned), Right(false))
    assertEquals(SkillLocation.baseRelease("0.9.3+13-2b8d0a46+20260730-2334"), Some("0.9.3"))
  }

  test("a version given with the tag's own 'v' is accepted") {
    val found = SkillLocation.resolve(Some("v1.2.3"), latest = false, running = "0.9.4")
    assertEquals(found.map(_.ref), Right("v1.2.3"))
  }

  /** The path `gx skill` advertises has to be the path the skill is actually
    * at, or every URL the command prints is a 404.
    */
  test("the advertised location exists in this repository") {
    val root  = repoRoot()
    val skill = root.resolve(SkillLocation.File)
    assert(Files.exists(skill), s"gx skill points at ${SkillLocation.File}, which does not exist")
    for name <- SkillLocation.SupportingFiles do
      val f = root.resolve(SkillLocation.Directory).resolve(name)
      assert(Files.exists(f), s"SKILL.md links $name, which does not exist")
    val linked = """\]\(([a-z-]+\.md)\)""".r.findAllMatchIn(Files.readString(skill)).map(_.group(1)).toSet
    assertEquals(linked, SkillLocation.SupportingFiles.toSet, "SKILL.md links a file that SupportingFiles does not name")
  }

  /** Only the six fields the Agent Skills spec allows. */
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
    assert(keys.contains("name"), "SKILL.md needs a name")
    assert(
      body.take(end).contains(s"name: ${SkillLocation.Name}"),
      s"the skill's name must match its directory (${SkillLocation.Name})"
    )
  }

  /** The skill teaches the commands of this binary. A removed command in it
    * sends an agent to a dead end. Only the table of removed commands can
    * name them.
    */
  test("the skill files name no removed command") {
    val dir = repoRoot().resolve(SkillLocation.Directory)
    for file <- SkillLocation.File.split('/').lastOption.toSeq ++ SkillLocation.SupportingFiles do
      val text = Files.readString(dir.resolve(file)).split("## Removed commands").head
      for gone <- Seq("gx import", "gx get", "gx bind", "gx unbind", "gx sync", "gx run", "gx view", "--mode", "--params") do
        assert(!text.contains(gone), s"$file still names '$gone'")
  }

  private def repoRoot(): Path =
    Iterator
      .iterate(Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(fail("could not find the repository root"))

object CliSpec:
  /** The commands that plan §2 removes. */
  val GoneCommands: Seq[String] = Seq(
    "import", "get", "bind", "unbind", "sync", "run", "view", "session", "push-text", "unset",
    "rename-diagram", "move-to-folder"
  )
