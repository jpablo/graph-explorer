package org.jpablo.graphexplorer.gxcore.command

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The property of plan §2.1: `gx nodes`, `gx arrows` and `gx groups` print a
  * name that gives back exactly that element.
  *
  * The corpus sweep checks the diagrams of the repository. The generated
  * graphs check the names that a corpus does not have: ids with `..`, `#`,
  * and the prefixes.
  */
class ElementNamesRoundTripSpec extends ScalaCheckSuite:
  import ElementNamesRoundTripSpec.*


  /** The root of the repository, found from the working directory, as the
    * V-13 test finds its fixtures.
    */
  private lazy val repo: Path =
    def search(from: Path): Option[Path] =
      Option(from).flatMap: dir =>
        if Files.isDirectory(dir.resolve("graphviz/corpus")) then Some(dir) else search(dir.getParent)
    search(Paths.get("").toAbsolutePath).getOrElse(fail("could not find the repository from the working directory"))

  /** Each DOT and Mermaid file of the test corpora. */
  private lazy val corpus: Vector[Path] =
    Vector("graphviz/corpus", "viewer/src/main/resources/examples", "viewer/src/test/resources")
      .map(repo.resolve)
      .filter(Files.isDirectory(_))
      .flatMap(dir => Files.walk(dir).iterator.asScala.toVector)
      .filter(p => Files.isRegularFile(p) && List(".dot", ".gv", ".mmd").exists(p.getFileName.toString.endsWith))
      .sortBy(_.toString)

  private def elementsOf(n: ElementNames): Vector[ElementId] =
    n.nodes.map(_._1) ++ n.arrows.map(_._1) ++ n.groups.map(_._1)

  /** The elements whose name does not give back exactly that element. */
  private def mismatches(n: ElementNames): Vector[String] =
    elementsOf(n).flatMap: id =>
      val name = n.render(id)
      n.resolve(name) match
        case Right(Vector(back)) if back == id => None
        case other                             => Some(s"${ElementRef.render(id)} -> '$name' -> $other")

  test("every element of every diagram in the corpora round trips through its name"):
    var dot, mermaid, elements, prefixed = 0
    val unread = Vector.newBuilder[String]
    val bad    = Vector.newBuilder[String]

    for file <- corpus do
      DiagramText.parseNamed(Files.readString(file, StandardCharsets.UTF_8)) match
        case Left(_) => unread += repo.relativize(file).toString
        case Right(parsed) =>
          if parsed.format == DiagramFormat.Mermaid then mermaid += 1 else dot += 1
          val ids = elementsOf(parsed.names)
          elements += ids.size
          prefixed += ids.count(id => parsed.names.render(id) == parsed.names.prefixed(id))
          bad ++= mismatches(parsed.names).map(m => s"${repo.relativize(file)}: $m")

    println(s"[names] $dot DOT and $mermaid Mermaid diagrams, $elements elements, $prefixed with a prefix")
    println(s"[names] not readable: ${unread.result().size}")
    assert(dot > 150, s"only $dot DOT diagrams were read")
    assert(mermaid >= 1, "no Mermaid diagram was read")
    assert(elements > 1000, s"only $elements elements were checked")
    assertEquals(bad.result(), Vector.empty[String])

  // ------------------------------------------------ generated graphs

  property("every element of a generated DOT graph round trips through its name"):
    forAllNoShrink(genDot): text =>
      DiagramText.parseNamed(text) match
        case Left(err)     => fail(s"the generated graph does not parse: $err\n$text")
        case Right(parsed) => assertEquals(mismatches(parsed.names), Vector.empty[String], text)

  property("every element of a generated Mermaid flowchart round trips through its name"):
    // Only a few Mermaid files of the corpora are flowcharts, so these graphs
    // add the cases: several arrows between two nodes, and an arrow to a
    // subgraph, which makes a node and a group with one name.
    forAllNoShrink(genMermaid): text =>
      DiagramText.parseNamed(text) match
        case Left(err)     => fail(s"the generated flowchart does not parse: $err\n$text")
        case Right(parsed) =>
          assertEquals(parsed.format, DiagramFormat.Mermaid)
          assertEquals(mismatches(parsed.names), Vector.empty[String], text)

  property("the prefix of each element of a generated DOT graph gives back that element"):
    forAllNoShrink(genDot): text =>
      val n = DiagramText.parseNamed(text).fold(e => fail(e), _.names)
      for id <- elementsOf(n) do assertEquals(n.resolve(n.prefixed(id)), Right(Vector(id)), s"${n.prefixed(id)}\n$text")

/** The generators. They are in the companion, so that each one exists before a test uses it. */
object ElementNamesRoundTripSpec:

  /** Ids that a corpus does not have, and that the parser must not confuse. */
  val genId: Gen[String] = Gen.oneOf(
    "a", "b", "c", "x", "a..b", "a...b", "b#2", "a.", ".b", "#1", "a:b",
    "node:a", "group:x", "arrow:a->b/0", "cluster_x", "clusterx", "a..b#1", "..", "a b"
  )

  val genSubgraphName: Gen[String] =
    Gen.oneOf("cluster_x", "clusterx", "x", "cluster_a", "a", "cluster_a..b", "cluster", "g")

  def q(id: String): String = "\"" + id.replace("\"", "\\\"") + "\""

  val genDot: Gen[String] =
    for
      directed  <- Gen.oneOf(true, false)
      arrows    <- Gen.listOfN(8, Gen.zip(genId, genId))
      loose     <- Gen.listOfN(3, genId)
      subgraphs <- Gen.listOfN(3, Gen.zip(genSubgraphName, Gen.listOfN(2, genId)))
    yield
      val edge = if directed then "->" else "--"
      val body =
        loose.map(q) ++
          arrows.map((s, t) => s"${q(s)} $edge ${q(t)}") ++
          subgraphs.map((name, members) => s"subgraph ${q(name)} { ${members.map(q).mkString("; ")} }")
      s"${if directed then "digraph" else "graph"} { ${body.mkString("; ")} }"

  private val genMermaidId: Gen[String] = Gen.oneOf("a", "b", "c", "svc", "g")

  val genMermaid: Gen[String] =
    for
      arrows    <- Gen.listOfN(8, Gen.zip(genMermaidId, genMermaidId, Gen.oneOf("-->", "---", "-.->", "==>")))
      // Two subgraphs with one id are an error in Mermaid, so the ids differ.
      names     <- Gen.pick(2, List("svc", "g", "sub"))
      members   <- Gen.listOfN(2, Gen.listOfN(2, Gen.oneOf("a", "b", "c")))
    yield
      val lines =
        arrows.map((s, t, link) => s"  $s $link $t") ++
          names.toList.zip(members).flatMap((name, ms) => s"  subgraph $name" +: ms.map(m => s"    $m") :+ "  end")
      ("flowchart LR" +: lines).mkString("\n") + "\n"
