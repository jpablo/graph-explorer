package org.jpablo.graphexplorer.gxcore.layout

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint, NodePlace}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidFlowchartParser, toViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

/** The text that Graphviz draws for a diagram with stored positions (ADR 0004).
  *
  * The positions go only into the text for the render. The source never gets
  * them.
  */
class FreeLayoutSpec extends FunSuite:

  private val ab   = NodeId("a b")
  private val q    = NodeId("x\"y")
  private val c    = NodeId("c")
  private val dotGraph: ViewerGraph =
    ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          ab -> ViewerNode.nodeNoDefaults(ab, Attributes.of("label" -> "A B")),
          q  -> ViewerNode.nodeNoDefaults(q, Attributes.empty),
          c  -> ViewerNode.nodeNoDefaults(c, Attributes.empty)
        ),
        arrows = Map(
          Arrow(ab, q).id          -> Arrow(ab, q),
          Arrow(q, c, seq = 2).id  -> Arrow(q, c, seq = 2),
          Arrow(q, c, seq = 5).id  -> Arrow(q, c, seq = 5)
        ),
        graphAttributes = Attributes.of("rankdir" -> "LR", "layout" -> "dot")
      )
    )

  private def layoutText(graph: ViewerGraph): String = viewerGraphToText(graph, omitInternal = false)

  /** The statement of the text that declares the node with the quoted id `quotedId`. A statement with many
    * attributes has one line for each attribute, and it ends with `];`.
    */
  private def lineOf(text: String, quotedId: String): String =
    val lines = text.linesIterator.toVector
    val start = lines.indexWhere(_.trim.startsWith(quotedId + " ["))
    if start < 0 then fail(s"no line for $quotedId in\n$text")
    val end = lines.indexWhere(_.trim.endsWith("];"), start)
    lines.slice(start, end + 1).mkString("\n")

  test("a graph with no positions gives the same render input"):
    val same = FreeLayout.renderGraph(dotGraph, DiagramLayout.empty)
    assert(same eq dotGraph)
    assertEquals(layoutText(same), layoutText(dotGraph))

  test("a stored position becomes a pinned pos, in points, on the node with a quoted id"):
    val layout = DiagramLayout(nodes = Map("a b" -> NodePlace(10, 20.5), "x\"y" -> NodePlace(-30.25, 0)))
    val text   = layoutText(FreeLayout.renderGraph(dotGraph, layout))
    assert(lineOf(text, "\"a b\"").contains("pos=\"10,20.5!\""), text)
    assert(lineOf(text, "\"x\\\"y\"").contains("pos=\"-30.25,0!\""), text)
    // A node with no stored position gets no pos: the engine places it.
    assert(!lineOf(text, "\"c\"").contains("pos="), text)

  test("the free layout uses neato, with positions in points, arrows around the nodes, and pins kept exactly"):
    val layout = DiagramLayout(nodes = Map("a b" -> NodePlace(10, 20)))
    val graph  = FreeLayout.renderGraph(dotGraph, layout)
    val attrs  = graph.elements.graphAttributes.values.map((k, v) => k.value -> v.toString)
    assertEquals(attrs.get("layout"), Some("neato"))
    assertEquals(attrs.get("inputscale"), Some("72"))
    assertEquals(attrs.get("splines"), Some("true"))
    // Overlap removal moves a pinned node. The default keeps it.
    assertEquals(attrs.get("overlap"), Some("true"))
    // The other attributes of the source stay.
    assertEquals(attrs.get("rankdir"), Some("LR"))

  test("a stored id that the graph does not have adds no node"):
    val layout = DiagramLayout(nodes = Map("a b" -> NodePlace(1, 2), "gone" -> NodePlace(3, 4)))
    val graph  = FreeLayout.renderGraph(dotGraph, layout)
    assertEquals(graph.nodeIds, dotGraph.nodeIds)
    assert(!layoutText(graph).contains("gone"))

  test("the source of the graph is not changed"):
    val layout = DiagramLayout(nodes = Map("a b" -> NodePlace(1, 2)))
    FreeLayout.renderGraph(dotGraph, layout)
    assert(!layoutText(dotGraph).contains("pos="))
    assert(!viewerGraphToText(dotGraph, omitInternal = true).contains("pos="))

  test("a graph from a Mermaid flowchart gets the pins on its node ids"):
    val mermaid = MermaidFlowchartParser.parse("flowchart LR\n  A[Start] --> B[End]\n  B --> C\n").fold(fail(_), identity)
    val graph   = toViewerGraph(mermaid)
    val layout  = DiagramLayout(nodes = Map("A" -> NodePlace(0, 0), "B" -> NodePlace(144, 72)))
    val text    = layoutText(FreeLayout.renderGraph(graph, layout))
    assert(lineOf(text, "\"A\"").contains("pos=\"0,0!\""), text)
    assert(lineOf(text, "\"B\"").contains("pos=\"144,72!\""), text)
    assert(!lineOf(text, "\"C\"").contains("pos="), text)
    assert(text.contains("layout=\"neato\""), text)

  test("a large or a small number has no exponent"):
    assertEquals(FreeLayout.posValue(org.jpablo.graphexplorer.gxcore.model.LayoutPoint(12345678.5, 0.01)), "12345678.5,0.01!")

  test("the key of an arrow counts the arrows of the same ends in the order of the source"):
    val keys = FreeLayout.arrowKeys(dotGraph)
    assertEquals(keys(Arrow(ab, q).id), "a b->x\"y#1")
    assertEquals(keys(Arrow(q, c, seq = 2).id), "x\"y->c#1")
    assertEquals(keys(Arrow(q, c, seq = 5).id), "x\"y->c#2")

  test("the key of an arrow has its ports"):
    val withPorts = Arrow(ab, c, sourcePort = Some("e"), targetPort = Some("w"))
    val graph     = dotGraph.copy(elements = dotGraph.elements.copy(arrows = Map(withPorts.id -> withPorts)))
    assertEquals(FreeLayout.arrowKeys(graph)(withPorts.id), "a b:e->c:w#1")

  test("the bends of the render go to the arrows by their keys, and only in a free layout"):
    val first  = Arrow(q, c, seq = 2)
    val second = Arrow(q, c, seq = 5)
    val bends  = Vector(LayoutPoint(9, 18))
    val pinned = DiagramLayout(nodes = Map("c" -> NodePlace(0, 0)))
      .withBends("x\"y->c#2", bends)
      .withBends("gone->c#1", Vector(LayoutPoint(1, 1)))
    assertEquals(FreeLayout.bendsByArrow(dotGraph, pinned), Map(second.id -> bends))
    assert(!FreeLayout.bendsByArrow(dotGraph, pinned).contains(first.id))
    // With no position, the diagram is not a free layout, and Graphviz routes each arrow.
    assertEquals(FreeLayout.bendsByArrow(dotGraph, pinned.copy(nodes = Map.empty)), Map.empty)

  test("a manual layout with no position uses neato, and gives no pin"):
    val graph = FreeLayout.renderGraph(dotGraph, DiagramLayout(manual = true))
    val attrs = graph.elements.graphAttributes.values.map((k, v) => k.value -> v.toString)
    assertEquals(attrs.get("layout"), Some("neato"))
    assertEquals(attrs.get("inputscale"), Some("72"))
    assert(!layoutText(graph).contains("pos="), layoutText(graph))

  test("an automatic layout with unknown keys gives the same render input"):
    val layout = DiagramLayout(other = Vector("grid" -> "8"))
    assert(FreeLayout.renderGraph(dotGraph, layout) eq dotGraph)

  test("the bends apply in a manual layout with no position"):
    val second = Arrow(q, c, seq = 5)
    val bends  = Vector(LayoutPoint(9, 18))
    val manual = DiagramLayout(manual = true).withBends("x\"y->c#2", bends)
    assertEquals(FreeLayout.bendsByArrow(dotGraph, manual), Map(second.id -> bends))
    assertEquals(FreeLayout.bendsByArrow(dotGraph, manual.arranged), Map.empty)

  test("the keys of the arrows with both ends in a set of nodes"):
    assertEquals(FreeLayout.arrowKeysWithin(dotGraph, Set(q, c)), Set("x\"y->c#1", "x\"y->c#2"))
    assertEquals(FreeLayout.arrowKeysWithin(dotGraph, Set(ab, c)), Set.empty[String])

