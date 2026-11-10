package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidStateGraph.{LayoutEdge, LayoutNode}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.*

/** A Mermaid state diagram as a graph, and the graph as state diagram text.
  *
  * The layout data below is what Mermaid 11.12 gives for each source (read in a browser from `db.getData()`). The real parser and renderer
  * are in the viewer tests (StateDiagramEditingSpec, StateDiagramRoundTripSpec).
  */
class MermaidStateGraphSpec extends FunSuite:

  /** stateDiagram-v2 [*] --> Active state Active { [*] --> Typing Typing --> Paused : pause Paused --> Typing : resume } Active --> Done :
    * quit Done --> [*]
    */
  private val compositeLayout = MermaidStateGraph.fromLayout(
    nodes = Seq(
      LayoutNode("root_start", "root_start", shape = "stateStart", domId = Some("state-root_start-0")),
      LayoutNode("Active", "Active", shape = "roundedWithTitle", isGroup = true, domId = Some("state-Active-4")),
      LayoutNode("Active_start", "Active_start", shape = "stateStart", parentId = Some("Active"), domId = Some("state-Active_start-1")),
      LayoutNode("Typing", "Typing", parentId = Some("Active"), domId = Some("state-Typing-3")),
      LayoutNode("Paused", "Paused", parentId = Some("Active"), domId = Some("state-Paused-3")),
      LayoutNode("Done", "Done", domId = Some("state-Done-5")),
      LayoutNode("root_end", "root_end", shape = "stateEnd", domId = Some("state-root_end-5"))
    ),
    edges = Seq(
      LayoutEdge("edge0", "root_start", "Active"),
      LayoutEdge("edge1", "Active_start", "Typing"),
      LayoutEdge("edge2", "Typing", "Paused", "pause"),
      LayoutEdge("edge3", "Paused", "Typing", "resume"),
      LayoutEdge("edge4", "Active", "Done", "quit"),
      LayoutEdge("edge5", "Done", "root_end")
    )
  )

  test("a composite state is a group, and its states are its members"):
    val graph = stateDiagramToViewerGraph(compositeLayout)
    assertEquals(graph.groupIds.toSet, Set(GroupId("Active")))
    assertEquals(
      graph.memberships.toSet,
      Set[(GroupMemberId, GroupId)](
        NodeId("Active_start") -> GroupId("Active"),
        NodeId("Typing")       -> GroupId("Active"),
        NodeId("Paused")       -> GroupId("Active")
      )
    )
    // The transitions into and out of Active end at a node with its id.
    assert(graph.nodeIds.contains(NodeId("Active")))
    assertEquals(graph.arrows.size, 6)
    assertEquals(graph.nodes(NodeId("root_start")).attributes.values.get(MermaidStateTypeAttr).map(_.toString), Some("start"))
    assertEquals(graph.nodes(NodeId("root_end")).attributes.values.get(MermaidStateTypeAttr).map(_.toString), Some("end"))

  test("the printer writes a composite state as a block, with its start state inside"):
    val text = viewerGraphToMermaidText(stateDiagramToViewerGraph(compositeLayout))
    assertEquals(
      text,
      """stateDiagram-v2
        |  state Active {
        |    [*] --> Typing
        |    Typing --> Paused : pause
        |    Paused --> Typing : resume
        |  }
        |  [*] --> Active
        |  Active --> Done : quit
        |  Done --> [*]
        |""".stripMargin
    )

  test("the ids of the drawn elements are the ids of the graph"):
    val graph = stateDiagramToViewerGraph(compositeLayout)
    assertEquals(compositeLayout.nodeIdsByDomId.values.toSet, graph.nodeIds.toSet - NodeId("Active"))
    assertEquals(compositeLayout.arrowIdsByDomId.values.toSet, graph.arrowIds.toSet)
    assertEquals(compositeLayout.arrowIdsByDomId("edge2"), ArrowId("Typing->Paused/1"))

  test("two transitions with the same ends get the sequence numbers 1 and 2"):
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(LayoutNode("A", "A"), LayoutNode("B", "B")),
      edges = Seq(LayoutEdge("edge0", "A", "B", "x"), LayoutEdge("edge1", "A", "B", "y"))
    )
    assertEquals(diagram.arrowIdsByDomId, Map("edge0" -> ArrowId("A->B/1"), "edge1" -> ArrowId("A->B/2")))

  test("a concurrent region is not a group, and its start state gets the id of the composite state"):
    // state Conc { [*] --> A1 \n -- \n [*] --> B1 }: Mermaid gives the last
    // region a random id at each parse.
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(
        LayoutNode("Conc", "Conc", shape = "roundedWithTitle", isGroup = true),
        LayoutNode("divider-id-1", "", shape = "divider", parentId = Some("Conc"), isGroup = true),
        LayoutNode("divider-id-1_start", "divider-id-1_start", shape = "stateStart", parentId = Some("divider-id-1")),
        LayoutNode("A1", "A1", parentId = Some("divider-id-1")),
        LayoutNode("id-x7k2-1", "", shape = "divider", parentId = Some("Conc"), isGroup = true),
        LayoutNode("id-x7k2-1_start", "id-x7k2-1_start", shape = "stateStart", parentId = Some("id-x7k2-1")),
        LayoutNode("B1", "B1", parentId = Some("id-x7k2-1"))
      ),
      edges = Seq(LayoutEdge("edge0", "divider-id-1_start", "A1"), LayoutEdge("edge1", "id-x7k2-1_start", "B1"))
    )
    assertEquals(diagram.composites.map(_.id), List("Conc"))
    assertEquals(
      diagram.states.map(s => s.id -> s.parent),
      List("Conc_start" -> Some("Conc"), "A1" -> Some("Conc"), "B1" -> Some("Conc"))
    )
    assertEquals(diagram.transitions.map(t => t.start -> t.end), List("Conc_start" -> "A1", "Conc_start" -> "B1"))

  test("notes are not elements of the graph"):
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(
        LayoutNode("Idle", "Idle"),
        LayoutNode("Idle----parent", "a note", shape = "noteGroup", isGroup = true),
        LayoutNode("Idle----note-9", "a note", shape = "note", parentId = Some("Idle----parent"))
      ),
      edges = Seq(LayoutEdge("Idle-Idle----note-9", "Idle", "Idle----note-9", classes = "transition note-edge"))
    )
    assertEquals(diagram.states.map(_.id), List("Idle"))
    assertEquals(diagram.composites, Nil)
    assertEquals(diagram.transitions, Nil)

  test("labels: Mermaid's entity placeholders become their characters"):
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(LayoutNode("Idle", "Long ﬂ°°58¶ß name ﬂ°quot¶ßqﬂ°quot¶ß ﬂ°amp¶ß", descriptions = List("extra")), LayoutNode("B", "B")),
      edges = Seq(LayoutEdge("edge0", "Idle", "B", "a ﬂ°°59¶ß b"))
    )
    assertEquals(diagram.states.head.label, Some("Long : name \"q\" #amp;"))
    assertEquals(diagram.states.head.descriptions, List("extra"))
    assertEquals(diagram.transitions.head.label, Some("a ; b"))

  test("the printer escapes what Mermaid would read as the end of a label"):
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(LayoutNode("Idle", "say \"hi\": now", descriptions = List("x: y")), LayoutNode("B", "B")),
      edges = Seq(LayoutEdge("edge0", "Idle", "B", "a: b; c"))
    )
    val text = viewerGraphToMermaidText(stateDiagramToViewerGraph(diagram))
    assert(text.contains("""state "say #quot;hi#quot;: now" as Idle"""), text)
    assert(text.contains("Idle : x#58; y"), text)
    assert(text.contains("Idle --> B : a#58; b#59; c"), text)

  test("fork, join and choice states, the direction, the title and the styling are written back"):
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(
        LayoutNode("F", "F", shape = "fork"),
        LayoutNode("C", "C", shape = "choice"),
        LayoutNode("Y", "Y", cssClasses = "bad statediagram-state", cssStyles = List("fill:#0f0"))
      ),
      edges = Seq(LayoutEdge("edge0", "F", "C"), LayoutEdge("edge1", "C", "Y")),
      direction = Some("LR"),
      title = Some("Flow"),
      classDefs = Seq("bad" -> List("fill:#f00", "color:white"))
    )
    val text = viewerGraphToMermaidText(stateDiagramToViewerGraph(diagram))
    assertEquals(
      text,
      """---
        |title: Flow
        |---
        |stateDiagram-v2
        |  direction LR
        |  classDef bad fill:#f00,color:white
        |  state F <<fork>>
        |  state C <<choice>>
        |  F --> C
        |  C --> Y
        |  class Y bad
        |  style Y fill:#0f0
        |""".stripMargin
    )

  test("a state with no transition in its block is declared in its block"):
    // state Active { Typing } and Typing --> Done at the top level.
    val diagram = MermaidStateGraph.fromLayout(
      nodes = Seq(
        LayoutNode("Active", "Active", shape = "roundedWithTitle", isGroup = true),
        LayoutNode("Typing", "Typing", parentId = Some("Active")),
        LayoutNode("Done", "Done")
      ),
      edges = Seq(LayoutEdge("edge0", "Typing", "Done"))
    )
    assertEquals(
      viewerGraphToMermaidText(stateDiagramToViewerGraph(diagram)),
      """stateDiagram-v2
        |  state Active {
        |    Typing
        |  }
        |  Typing --> Done
        |""".stripMargin
    )

  test("a new node and a new arrow come after the states and transitions of the source"):
    val graph             = stateDiagramToViewerGraph(compositeLayout)
    val (withNode, newId) = graph.addNode(attributes = Attributes.of(Label -> "Saving"))
    val (edited, _)       = withNode.addArrow(NodeId("Done"), newId)
    val text              = viewerGraphToMermaidText(edited)
    assert(text.startsWith("stateDiagram-v2\n"), text)
    assert(text.contains(s"""  state "Saving" as ${newId.value}\n"""), text)
    assert(text.endsWith(s"  Done --> [*]\n  Done --> ${newId.value}\n"), text)

  test("a flowchart graph is still written as a flowchart"):
    val graph = toViewerGraph(MermaidGraph(vertices = Map("A" -> MermaidVertex("A", "A")), edges = List(MermaidEdge("A", "B"))))
    assert(viewerGraphToMermaidText(graph).startsWith("flowchart "), viewerGraphToMermaidText(graph))
