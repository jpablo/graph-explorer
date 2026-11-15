package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.{MermaidDiagramAttr, StateDiagramHeader}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowHead, ArrowTail}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

/** The Mermaid renderer draws the Mermaid text of a graph. This map tells it
  * which element of the graph each drawn element is (`MermaidDrawnIds`).
  *
  * `RendererChoiceSpec` (viewer) checks the map against the SVG of real Mermaid.
  */
class MermaidDrawnIdsSpec extends FunSuite:

  private def graphOf(nodes: Seq[String], arrows: Seq[Arrow], groups: Map[String, Seq[String]] = Map.empty): ViewerGraph =
    ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap.from(nodes.map(n => NodeId(n) -> ViewerNode.nodeWithDefaults(NodeId(n)))),
        arrows = arrows.map(a => a.id -> a).toMap,
        groups = groups.keys.map(g => GroupId(g) -> ViewerGroup.group(GroupId(g), Attributes.empty)).toMap,
        memberships = groups.flatMap((g, members) => members.map(m => (NodeId(m): GroupMemberId) -> GroupId(g)))
      )
    )

  private def arrow(source: String, target: String, seq: Int): Arrow =
    Arrow(NodeId(source), NodeId(target), seq = seq)

  test("an arrow has the name of its path, also when the graph numbers its arrows as a DOT source does"):
    // A graph from a DOT source numbers each arrow of the graph: 0, 1, 2.
    val ab    = arrow("a", "b", 0)
    val bc    = arrow("b", "c", 1)
    val drawn = mermaidDrawnIds(graphOf(Seq("a", "b", "c"), Seq(ab, bc)))
    assertEquals(drawn.arrows, Map("L_a_b_0" -> ab.id, "L_b_c_0" -> bc.id))

  test("the arrows between two ends have the numbers 0, 2, 3, as Mermaid gives them"):
    val arrows = Seq(arrow("a", "b", 1), arrow("a", "b", 2), arrow("a", "b", 3))
    val drawn  = mermaidDrawnIds(graphOf(Seq("a", "b"), arrows))
    assertEquals(
      drawn.arrows,
      Map("L_a_b_0" -> arrows(0).id, "L_a_b_2" -> arrows(1).id, "L_a_b_3" -> arrows(2).id)
    )

  test("a node with an id that Mermaid cannot use has its new id as its name"):
    val nodes = Seq("com.foo.Bar", "my node", "end", "plain")
    val drawn = mermaidDrawnIds(graphOf(nodes, Nil))
    assertEquals(drawn.nodes.values.toSet, nodes.map(NodeId(_)).toSet)
    assertEquals(drawn.nodes.get("plain"), Some(NodeId("plain")))
    val text = viewerGraphToMermaidText(graphOf(nodes, Nil))
    // Each name is an id of the text, and no name is an id that Mermaid cannot use.
    drawn.nodes.keys.foreach(name => assert(text.contains(name), s"the text does not have the id $name:\n$text"))
    assert(!drawn.nodes.contains("com.foo.Bar") && !drawn.nodes.contains("my node") && !drawn.nodes.contains("end"), drawn.nodes)

  test("an arrow between nodes with new ids has the new ids in its name"):
    val a     = arrow("com.foo.Bar", "my node", 0)
    val drawn = mermaidDrawnIds(graphOf(Seq("com.foo.Bar", "my node"), Seq(a)))
    val start = drawn.nodes.collectFirst { case (name, id) if id == NodeId("com.foo.Bar") => name }.get
    val end   = drawn.nodes.collectFirst { case (name, id) if id == NodeId("my node") => name }.get
    assertEquals(drawn.arrows, Map(s"L_${start}_${end}_0" -> a.id))

  test("a group has its Mermaid id as its name"):
    val drawn = mermaidDrawnIds(graphOf(Seq("a"), Nil, groups = Map("cluster my.svc" -> Seq("a"), "svc" -> Nil)))
    assertEquals(drawn.groups.values.toSet, Set(GroupId("cluster my.svc"), GroupId("svc")))
    assertEquals(drawn.groups.get("svc"), Some(GroupId("svc")))
    assert(!drawn.groups.contains("cluster my.svc"), drawn.groups)

  test("a self-loop has the names of the three paths that Mermaid draws for its node"):
    val first  = arrow("a", "a", 1)
    val second = arrow("a", "a", 2)
    val drawn  = mermaidDrawnIds(graphOf(Seq("a"), Seq(first, second)))
    assertEquals(
      drawn.arrows,
      Map("a-cyclic-special-1" -> first.id, "a-cyclic-special-mid" -> first.id, "a-cyclic-special-2" -> first.id)
    )

  test("an arrow with a mark only at its tail has its ends in the other order, as the text has them"):
    val tailOnly = arrow("a", "b", 1).copy(attributes =
      Attributes.of(ArrowHead.attrId.value -> "none", ArrowTail.attrId.value -> "normal")
    )
    val graph = graphOf(Seq("a", "b"), Seq(tailOnly))
    val text  = viewerGraphToMermaidText(graph)
    assert(text.contains("b --> a"), s"precondition: the text has the ends in the other order:\n$text")
    assertEquals(mermaidDrawnIds(graph).arrows, Map("L_b_a_0" -> tailOnly.id))

  test("a state diagram gives the empty map"):
    val graph = graphOf(Seq("Idle"), Nil)
    val state = graph.copy(elements =
      graph.elements.copy(graphAttributes = Attributes.fromOrdered(Seq(MermaidDiagramAttr -> AttrValue(StateDiagramHeader))))
    )
    assertEquals(mermaidDrawnIds(state), MermaidDrawnIds.empty)
