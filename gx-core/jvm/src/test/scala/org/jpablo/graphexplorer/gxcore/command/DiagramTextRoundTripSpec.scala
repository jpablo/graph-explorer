package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{GroupId, NodeId}

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
      .map(g => SourceCommands.run(g, SourceCommand.SetAttribute(Set(NodeId("a")), "fillcolor", "red")))
      .fold(fail(_), identity)
      .fold(e => fail(e.message), identity)

    val text = filled match
      case SourceResult.Updated(g) => DiagramText.render(g)
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

  /** Real Mermaid reads `graph` with no direction as a flowchart from top to
    * bottom. `gx` read it as DOT, and then the DOT reader rejected it.
    */
  test("a Mermaid flowchart with a bare `graph` header is Mermaid, and gx reads back what it wrote") {
    val named = DiagramText.parseNamed("graph\n  A --> B").fold(fail(_), identity)
    assertEquals(named.format, DiagramFormat.Mermaid)
    assertEquals(named.graph.nodeIds, Set(NodeId("A"), NodeId("B")))
    val again = DiagramText.parseNamed(DiagramText.render(named.graph, DiagramFormat.Mermaid)).fold(fail(_), identity)
    assertEquals(again.graph.nodeIds, named.graph.nodeIds)
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

  // ------------------------------------------------- group names (plan §2.1)

  /** A group has the name that its source gives it. A write must keep that
    * name, because the next gx command finds the group by it. Before, the
    * writer printed `cluster_<id>`, so `clusterB` became `cluster_B`.
    */
  private def groupNamesAfterWrite(text: String): Vector[String] =
    val written = DiagramText.parseNamed(text).map(n => DiagramText.render(n.graph, n.format)).fold(fail(_), identity)
    DiagramText.parseNamed(written).fold(e => fail(s"$e\n$written"), _.names.groups.map(_._2))

  for (source, name) <- Vector(
      "digraph G { subgraph clusterB { a } }"            -> "clusterB",
      "digraph G { subgraph cluster01 { a } }"           -> "cluster01",
      "digraph G { subgraph cluster_svc { api; db } }"   -> "cluster_svc",
      "digraph G { subgraph plain { a } }"               -> "plain",
      "flowchart LR\n  subgraph api_tier\n    a --> b\n  end\n" -> "api_tier"
    )
  do
    test(s"a write keeps the group name $name"):
      assertEquals(groupNamesAfterWrite(source), Vector(name))

  test("a second write keeps the group name too"):
    val once = DiagramText.parseNamed("digraph G { subgraph clusterB { a } }")
      .map(n => DiagramText.render(n.graph, n.format)).fold(fail(_), identity)
    assertEquals(groupNamesAfterWrite(once), Vector("clusterB"))

  test("a new group gets the name cluster_<id>, and that name finds it"):
    val named = DiagramText.parseNamed("digraph G { a; b }").fold(fail(_), identity)
    val grouped = SourceCommands.run(named.graph, SourceCommand.Group(Set(NodeId("a"), NodeId("b")), "pair"))
      .fold(e => fail(e.message), _.updatedGraph.getOrElse(fail("no graph")))
    val text  = DiagramText.render(grouped, named.format)
    val again = DiagramText.parseNamed(text).fold(fail(_), identity)
    val (id, name) = again.names.groups.headOption.getOrElse(fail(s"no group in\n$text"))
    assertEquals(name, s"cluster_${id.value}")
    assertEquals(again.names.resolve(name), Right(Vector(id)))

  // ------------------------------------------------- the id attribute

  /** The DOT writer left out each `id` attribute of the source. Thus a write
    * of a different change removed `id` from the file, with no message, and
    * `RoundTripLoss` did not find the loss. The text for the layout puts its
    * own `id` on each element, because a click finds the element by it. The
    * text for a file must keep the `id` of the source.
    */
  test("a write keeps the id attribute of the graph, a node, an arrow, and a group"):
    val source = """digraph G { id="rid"; subgraph cluster_x { id="gid"; a [id="nid"] } a -> b [id="eid"] }"""
    val text   = DiagramText.parse(source).map(DiagramText.render).fold(fail(_), identity)
    for id <- Vector("rid", "nid", "eid", "gid") do
      assert(text.contains(s"id=\"$id\""), s"the write dropped id=\"$id\":\n$text")
    val twice = DiagramText.parse(text).map(DiagramText.render).fold(fail(_), identity)
    assertEquals(twice, text, "the second write disagreed with the first")

  // ------------------------------------- two subgraphs, one id (plan §2.1)

  /** `GroupId.fromDot` removes the `cluster` prefix. Thus `clusterB` and
    * `cluster_B` both gave the id `B`, and the reader made one group of the two
    * subgraphs. A write kept one subgraph, and the members of the other left
    * the group, with no message. A plain subgraph `B` and `clusterB` had the
    * same problem.
    *
    * The first subgraph keeps the id that it had before. Metadata that names
    * `group:B` (a collapsed group, for example) then still finds it.
    */
  for (source, names) <- Vector(
      "digraph G { subgraph clusterB { a } subgraph cluster_B { b } c }" -> Vector("clusterB", "cluster_B"),
      "digraph G { subgraph B { a } subgraph clusterB { b } c }"         -> Vector("B", "clusterB"),
      // The full name of the second subgraph, `B`, is also the id of the first.
      "digraph G { subgraph clusterB { a } subgraph B { b } c }"         -> Vector("clusterB", "B")
    )
  do
    test(s"two subgraphs whose names give one id stay two groups: ${names.mkString(" and ")}"):
      val named = DiagramText.parseNamed(source).fold(fail(_), identity)
      assertEquals(named.graph.groups.size, 2, "the reader made one group of two subgraphs")
      assertEquals(named.names.groups.map(_._2), names, "the names of the groups")
      assertEquals(named.names.resolve(names(0)), Right(Vector(GroupId("B"))), "the first subgraph lost its old id")
      val written = DiagramText.render(named.graph, named.format)
      val again   = DiagramText.parseNamed(written).fold(e => fail(s"$e\n$written"), identity)
      assertEquals(again.names.groups.map(_._2), names, s"a write changed the names of the groups:\n$written")
      for (member, name) <- Vector("a" -> names(0), "b" -> names(1)) do
        val group = again.names.resolve(name).fold(e => fail(s"$e\n$written"), identity)
        assertEquals(group, again.graph.memberships.get(NodeId(member)).toVector, s"$member left the subgraph $name:\n$written")

  /** In DOT, two sibling subgraphs with one name are one subgraph: Graphviz
    * merges them (checked with `dot -Tcanon`, Graphviz 14.1.5). The reader of
    * gx kept two. When each subgraph got its own group, a write then printed
    * `cluster_x` and `cluster_x_2`, and one cluster became two, with no
    * message.
    */
  test("two sibling subgraphs with one name are one group, as in Graphviz"):
    val source = "digraph G { subgraph cluster_x { a } subgraph cluster_x { b } c }"
    val named  = DiagramText.parseNamed(source).fold(fail(_), identity)
    assertEquals(named.graph.groups.size, 1, "the reader made two groups of one subgraph")
    assertEquals(named.names.groups.map(_._2), Vector("cluster_x"))
    val written = DiagramText.render(named.graph, named.format)
    val again   = DiagramText.parseNamed(written).fold(e => fail(s"$e\n$written"), identity)
    assertEquals(again.names.groups.map(_._2), Vector("cluster_x"), s"a write changed the subgraphs:\n$written")
    val group = again.names.resolve("cluster_x").fold(e => fail(s"$e\n$written"), identity)
    for member <- Vector("a", "b") do
      assertEquals(group, again.graph.memberships.get(NodeId(member)).toVector, s"$member left cluster_x:\n$written")

  // ------------------------------------------- an arrow to a subgraph

  /** In DOT, an arrow to a subgraph goes to each node of the subgraph, also to
    * the nodes of its inner subgraphs (checked with `dot -Tcanon`, Graphviz
    * 14.1.5). The reader of gx made arrows only to the nodes that the
    * subgraph names itself. Thus the reader lost `x -> a`, and a write
    * removed it from the file, with no message.
    */
  private def arrowPairs(g: ViewerGraph): Vector[String] =
    g.arrows.values.map(a => s"${a.source.value}->${a.target.value}").toVector.sorted

  for (source, expected, outer, inner) <- Vector(
      ("digraph G { x -> subgraph s { subgraph t { a } b } }", Vector("x->a", "x->b"), "s", "t"),
      ("digraph G { subgraph s { subgraph t { a } b } -> x }", Vector("a->x", "b->x"), "s", "t"),
      ("digraph G { x -> subgraph cluster_s { subgraph cluster_t { a } b } }", Vector("x->a", "x->b"), "cluster_s", "cluster_t")
    )
  do
    test(s"an arrow to a subgraph goes to the nodes of its inner subgraphs, and a write keeps them: $source"):
      val named = DiagramText.parseNamed(source).fold(fail(_), identity)
      assertEquals(arrowPairs(named.graph), expected, "the reader lost an arrow")
      val written = DiagramText.render(named.graph, named.format)
      val again   = DiagramText.parseNamed(written).fold(e => fail(s"$e\n$written"), identity)
      assertEquals(arrowPairs(again.graph), expected, s"a write lost an arrow:\n$written")
      // The arrow does not move `a` out of its inner subgraph.
      for (member, name) <- Vector("a" -> inner, "b" -> outer) do
        val group = again.names.resolve(name).fold(e => fail(s"$e\n$written"), identity)
        assertEquals(group, again.graph.memberships.get(NodeId(member)).toVector, s"$member left the subgraph $name:\n$written")
