package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.NodeId

/** `gx` must be able to read back what `gx` wrote.
  *
  * Nothing asserted this, and it was false. `DiagramText.render` called the DOT
  * printer directly instead of going through `ViewerGraph.viewerGraphToText`,
  * which skipped the two steps that stand in front of it — so two commands were
  * enough to break a diagram:
  *
  *   gx run g.dot set-attribute '{"targets":["node:a"],"name":"fillcolor",...}'
  *   gx run g.dot list-nodes        # gx: could not parse the diagram: assertion failed
  *
  * A round trip is the property, not any one attribute, so these go through
  * `parse -> render -> parse` rather than matching the printed text.
  */
class DiagramTextRoundTripSpec extends FunSuite:

  private def reparse(text: String) =
    DiagramText.parse(text).flatMap(g => DiagramText.parse(DiagramText.render(g)))

  private def roundTrip(text: String) =
    DiagramText.parse(text).map(DiagramText.render).flatMap(DiagramText.parse) match
      case Right(g) => g
      case Left(e)  => fail(s"could not re-read what render produced: $e")

  /** The exact break: setting a fill emits the synthetic `fillstyle`, which is
    * not DOT, and the reader rejected it.
    */
  test("a filled node survives a round trip") {
    val filled = DiagramText
      .parse("""digraph "G" { "a" -> "b" }""")
      .map(g => DocumentCommands.run(g, DocumentCommand.SetAttribute(Set(NodeId("a")), "fillcolor", "red")))
      .fold(fail(_), identity)
      .fold(e => fail(e.message), identity)

    val text = filled match
      case CommandResult.Updated(g) => DiagramText.render(g)
      case other                    => fail(s"expected an update, got $other")

    // The synthetic sub-attribute is an internal spelling of `style="filled"`
    // and must never reach a file.
    assert(!text.contains("fillstyle"), s"synthetic attribute leaked into the DOT:\n$text")
    assert(text.contains("filled"), s"the fill was lost entirely:\n$text")

    assert(DiagramText.parse(text).isRight, s"gx cannot read back what it wrote:\n$text")
  }

  /** The same bypass defaulted the graph's name and type, which is a quieter
    * failure than the crash: it round-trips fine, as a different diagram.
    */
  test("an undirected graph keeps its name and its edges") {
    val text = DiagramText.parse("graph MyNet { a -- b }").map(DiagramText.render).fold(fail(_), identity)
    assert(!text.contains("digraph"), s"an undirected graph became directed:\n$text")
    assert(!text.contains("->"), s"undirected edges became directed:\n$text")
    assert(text.contains("MyNet"), s"the graph lost its name:\n$text")
  }

  test("a named digraph keeps its name") {
    val text = DiagramText.parse("""digraph "Services" { a -> b }""").map(DiagramText.render).fold(fail(_), identity)
    assert(text.contains("Services"), s"the graph lost its name:\n$text")
  }

  /** Files written by a released `gx` already carry `fillstyle`, so reading one
    * has to work — and has to agree with `dot`, which ignores an attribute it
    * does not know rather than painting a fill for it.
    */
  test("a file already poisoned with a synthetic attribute still loads") {
    val poisoned = """digraph "G" { "a" [fillcolor="red", fillstyle="true"]; "b"; "a" -> "b"; }"""
    val graph    = DiagramText.parse(poisoned).fold(e => fail(s"could not load a poisoned file: $e"), identity)
    assertEquals(graph.nodeIds, Set(NodeId("a"), NodeId("b")))
    assert(!DiagramText.render(graph).contains("fillstyle"), "the synthetic attribute survived a read")
  }

  test("round-tripping twice is stable") {
    val once  = DiagramText.parse("""digraph "G" { "a" [style="filled", fillcolor="red"]; "a" -> "b"; }""")
      .map(DiagramText.render).fold(fail(_), identity)
    val twice = DiagramText.parse(once).map(DiagramText.render).fold(fail(_), identity)
    assertEquals(twice, once, "the second render disagreed with the first")
  }

  test("clusters and their edges survive a round trip") {
    val g = roundTrip("""digraph G { subgraph cluster_svc { label="services"; api; db } api -> db; web -> api }""")
    assertEquals(g.nodeIds, Set(NodeId("api"), NodeId("db"), NodeId("web")))
    assertEquals(g.groups.size, 1)
    assertEquals(g.arrows.size, 2)
    // Named so a failure says which direction was lost.
    assert(reparse("""digraph G { subgraph cluster_svc { api; db } api -> db }""").isRight)
  }
