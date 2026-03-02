package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Color, FillColor, FontColor, FontName, FontSize, Label, PenColor, PenWidth, Shape, Style}

import scala.collection.immutable.VectorMap

class FromViewerGraphSpec extends FunSuite:

  test("viewerGraphToMermaidText should serialize a simple node"):
    val nodeId = NodeId("A")
    val node   = ViewerNode.nodeWithDefaults(nodeId, Attributes.of(Label -> "Hello"))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("flowchart TB"), s"Should start with flowchart TB, got: $result")
    assert(result.contains("A[Hello]"), s"Should contain node A with label Hello, got: $result")

  test("viewerGraphToMermaidText should serialize an edge"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A --> B"), s"Should contain edge A --> B, got: $result")

  test("viewerGraphToMermaidText should emit standalone nodes without redundant labels"):
    val nodeA = NodeId("a")
    val nodeB = NodeId("b")
    val nodeC = NodeId("c")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB),
      nodeC -> ViewerNode.nodeWithDefaults(nodeC)
    )
    val arrow = Arrow(nodeA, nodeB)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("a --> b"), s"Should contain edge a --> b, got: $result")
    assert(result.contains("\n  c\n"), s"Should contain standalone node c without brackets, got: $result")
    assert(!result.contains("a[a]"), s"Should omit redundant node label for a, got: $result")
    assert(!result.contains("b[b]"), s"Should omit redundant node label for b, got: $result")
    assert(!result.contains("c[c]"), s"Should omit redundant node label for c, got: $result")

  test("viewerGraphToMermaidText should serialize a diamond shape"):
    val nodeId = NodeId("Decision")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes.of(Label -> "Is it ok?", Shape -> Shape.diamond)
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("Decision{Is it ok?}"), s"Should use diamond brackets, got: $result")

  test("viewerGraphToMermaidText should serialize a circle shape"):
    val nodeId = NodeId("Start")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes.of(Label -> "Begin", Shape -> Shape.circle)
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("Start((Begin))"), s"Should use circle brackets, got: $result")

  test("viewerGraphToMermaidText should serialize edge with label"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB, Attributes.of(Label -> "yes"))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("-->|yes|"), s"Should contain edge with label, got: $result")

  test("viewerGraphToMermaidText should serialize dashed edge"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB, Attributes.of(Style -> Style.dashed))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("-.->"), s"Should use dashed arrow, got: $result")

  test("viewerGraphToMermaidText should write normalized edge attrs as linkStyle"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(
      nodeA,
      nodeB,
      Attributes.of(
        Color     -> "#f00",
        PenWidth  -> 2.0,
        FontColor -> "#fff",
        FontName  -> "Arial",
        FontSize  -> 16.0
      )
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A --> B"), s"Should keep edge declaration, got: $result")
    assert(
      result.matches("(?s).*linkStyle 0 stroke:#f00,stroke-width:2(?:\\.0)?px,color:#fff,font-family:Arial,font-size:16(?:\\.0)?px.*"),
      s"Should emit linkStyle from normalized edge attrs, got: $result"
    )

  test("viewerGraphToMermaidText should merge edge metadata and normalized attrs in linkStyle"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(
      nodeA,
      nodeB,
      Attributes(
        VectorMap(
          AttributeId("mermaid_edgeStyle")       -> AttrValue("stroke:#111,stroke-dasharray:3 3"),
          AttributeId("mermaid_edgeInterpolate") -> AttrValue("basis"),
          Color.attrId                           -> AttrValue("#f00"),
          PenWidth.attrId                        -> AttrValue("4.0")
        )
      )
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.contains("linkStyle 0 stroke:#f00,stroke-width:4.0px,interpolate:basis,stroke-dasharray:3 3"),
      s"Normalized attrs should override edge metadata while preserving extra directives, got: $result"
    )

  test("viewerGraphToMermaidText should emit classDef lines from graph attributes"):
    val nodeId = NodeId("A")
    val node   = ViewerNode.nodeWithDefaults(nodeId)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node),
        graphAttributes = Attributes(VectorMap(
          AttributeId("mermaid_classDef_green") -> AttrValue("fill:#9f6,stroke:#333"),
          AttributeId("mermaid_classDef_red")   -> AttrValue("fill:#f66,stroke:#900")
        ))
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("classDef green fill:#9f6,stroke:#333"), s"Should contain classDef green, got: $result")
    assert(result.contains("classDef red fill:#f66,stroke:#900"), s"Should contain classDef red, got: $result")

  test("viewerGraphToMermaidText should merge classDef styles and classDef text styles"):
    val nodeId = NodeId("A")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> ViewerNode.nodeWithDefaults(nodeId)),
        graphAttributes = Attributes(VectorMap(
          AttributeId("mermaid_classDef_warn")     -> AttrValue("fill:#f66,stroke:#900"),
          AttributeId("mermaid_classDefText_warn") -> AttrValue("color:#fff")
        ))
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.contains("classDef warn fill:#f66,stroke:#900,color:#fff"),
      s"Should merge classDef + classDefText into one declaration, got: $result"
    )

  test("viewerGraphToMermaidText should emit default linkStyle from graph attributes"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          nodeA -> ViewerNode.nodeWithDefaults(nodeA),
          nodeB -> ViewerNode.nodeWithDefaults(nodeB)
        ),
        graphAttributes = Attributes(VectorMap(
          AttributeId("mermaid_linkStyle_default")       -> AttrValue("stroke:#f00,stroke-width:2px"),
          AttributeId("mermaid_linkInterpolate_default") -> AttrValue("stepBefore")
        ))
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.contains("linkStyle default stroke:#f00,stroke-width:2px,interpolate:stepBefore"),
      s"Should emit default linkStyle with interpolation, got: $result"
    )

  test("viewerGraphToMermaidText should serialize edges in deterministic order"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodeC = NodeId("C")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          nodeA -> ViewerNode.nodeWithDefaults(nodeA),
          nodeB -> ViewerNode.nodeWithDefaults(nodeB),
          nodeC -> ViewerNode.nodeWithDefaults(nodeC)
        ),
        arrows = Map(
          Arrow(
            nodeB,
            nodeC,
            Attributes.of(Label -> "second"),
            seq = 2
          ).id -> Arrow(nodeB, nodeC, Attributes.of(Label -> "second"), seq = 2),
          Arrow(nodeA, nodeB, Attributes.of(Label -> "first"), seq = 1).id -> Arrow(nodeA, nodeB, Attributes.of(Label -> "first"), seq = 1),
          Arrow(nodeB, nodeC, Attributes.of(Label -> "middle"), seq = 1).id -> Arrow(
            nodeB,
            nodeC,
            Attributes.of(Label -> "middle"),
            seq = 1
          )
        )
      )
    )

    val result    = viewerGraphToMermaidText(graph)
    val edgeLines = result.linesIterator.filter(_.contains("-->")).toVector

    assertEquals(
      edgeLines,
      Vector(
        "  A -->|first| B",
        "  B -->|middle| C",
        "  B -->|second| C"
      )
    )

  test("viewerGraphToMermaidText should append :::className on nodes with mermaid_class"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes(VectorMap(
        Label.attrId                 -> AttrValue("Hello"),
        AttributeId("mermaid_class") -> AttrValue("green")
      ))
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A[Hello]:::green"), s"Should append :::green to node, got: $result")

  test("viewerGraphToMermaidText should emit inline style directive for CSS styles"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes(VectorMap(
        Style.attrId -> AttrValue("fill:#f9f,stroke:#333,stroke-width:4px")
      ))
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("style A fill:#f9f,stroke:#333,stroke-width:4px"), s"Should emit style directive, got: $result")

  test("viewerGraphToMermaidText should write normalized node attrs as Mermaid CSS"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes.of(
        FillColor -> "#f9f",
        Color     -> "#333",
        PenWidth  -> 2.0,
        FontColor -> "#fff",
        FontName  -> "Arial",
        FontSize  -> 20.0
      )
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.matches("(?s).*style A fill:#f9f,stroke:#333,stroke-width:2(?:\\.0)?px,color:#fff,font-family:Arial,font-size:20(?:\\.0)?px.*"),
      s"Should write normalized attrs as Mermaid CSS style directive, got: $result"
    )

  test("viewerGraphToMermaidText should let normalized node attrs override existing CSS keys"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes(
        VectorMap(
          Style.attrId     -> AttrValue("fill:#111,stroke:#222,rx:6px"),
          FillColor.attrId -> AttrValue("#f9f"),
          PenWidth.attrId  -> AttrValue("3.0")
        )
      )
    )
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.contains("style A fill:#f9f,stroke:#222,stroke-width:3.0px,rx:6px"),
      s"Explicit normalized attrs should win over raw CSS for matching keys, got: $result"
    )

  test("viewerGraphToMermaidText should not emit style directive for DOT-style values"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes(VectorMap(
        Style.attrId -> AttrValue("dashed")
      ))
    )
    val nodeB = NodeId("B")
    val arrow = Arrow(nodeId, nodeB, Attributes.of(Style -> Style.dashed))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          nodeId -> node,
          nodeB  -> ViewerNode.nodeWithDefaults(nodeB)
        ),
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(!result.contains("style A"), s"Should not emit style directive for DOT-style 'dashed', got: $result")

  test("viewerGraphToMermaidText should emit connected node with mermaid_class as standalone"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeNoDefaults(
        nodeA,
        Attributes(VectorMap(AttributeId("mermaid_class") -> AttrValue("green")))
      ),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A:::green"), s"Should emit node A with :::green even though connected, got: $result")
    assert(result.contains("A --> B"), s"Should also contain edge, got: $result")

  test("viewerGraphToMermaidText should emit connected node with CSS style as standalone"):
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeNoDefaults(
        nodeA,
        Attributes(VectorMap(Style.attrId -> AttrValue("fill:#f9f,stroke:#333")))
      ),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("style A fill:#f9f,stroke:#333"), s"Should emit style directive for connected node, got: $result")

  test("viewerGraphToMermaidText should write normalized group attrs as Mermaid CSS"):
    val nodeA   = NodeId("A")
    val groupId = GroupId("SG")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeA -> ViewerNode.nodeWithDefaults(nodeA)),
        groups = Map(
          groupId -> ViewerGroup.group(
            groupId,
            Attributes.of(
              Label     -> "Cluster",
              FillColor -> "#eef",
              PenColor  -> "#333",
              PenWidth  -> 2.0,
              FontColor -> "#111",
              FontName  -> "Arial",
              FontSize  -> 13.0
            )
          )
        ),
        memberships = VectorMap(nodeA -> groupId)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("subgraph SG [Cluster]"), s"Should emit group block, got: $result")
    assert(
      result.matches("(?s).*style SG fill:#eef,stroke:#333,stroke-width:2(?:\\.0)?px,color:#111,font-family:Arial,font-size:13(?:\\.0)?px.*"),
      s"Should emit group style from normalized attrs, got: $result"
    )

  test("viewerGraphToMermaidText should let normalized group attrs override existing CSS keys"):
    val nodeA   = NodeId("A")
    val groupId = GroupId("SG")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeA -> ViewerNode.nodeWithDefaults(nodeA)),
        groups = Map(
          groupId -> ViewerGroup.group(
            groupId,
            Attributes(
              VectorMap(
                Label.attrId     -> AttrValue("Cluster"),
                Style.attrId     -> AttrValue("fill:#111,rx:6px"),
                FillColor.attrId -> AttrValue("#f9f")
              )
            )
          )
        ),
        memberships = VectorMap(nodeA -> groupId)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(
      result.contains("style SG fill:#f9f,rx:6px"),
      s"Normalized group attrs should override matching raw CSS keys, got: $result"
    )

  test("round-trip: MermaidGraph with styles → ViewerGraph → Mermaid text preserves styles"):
    val mg = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "Start", classes = List("highlight")),
        "B" -> MermaidVertex(id = "B", text = "End", styles = List("fill:#f9f", "stroke:#333")),
        "C" -> MermaidVertex(id = "C", text = "C")
      ),
      edges = List(MermaidEdge(start = "A", end = "B"), MermaidEdge(start = "B", end = "C")),
      direction = Some("LR"),
      classDefs = Map("highlight" -> MermaidClassDef(styles = List("fill:#9f6", "stroke:#333")))
    )

    val vg     = toViewerGraph(mg)
    val result = viewerGraphToMermaidText(vg)

    assert(result.contains("flowchart LR"), s"Should preserve direction, got: $result")
    assert(result.contains("classDef highlight fill:#9f6,stroke:#333"), s"Should emit classDef, got: $result")
    assert(result.contains("A[Start]:::highlight"), s"Should emit :::highlight on node A, got: $result")
    assert(result.contains("style B fill:#f9f,stroke:#333"), s"Should emit inline style for B, got: $result")
    assert(result.contains("A --> B"), s"Should contain edge A → B, got: $result")
    assert(result.contains("B --> C"), s"Should contain edge B → C, got: $result")

  test("round-trip edit flow should serialize flattened node/edge/group style directives"):
    val mermaidGraph = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "A"),
        "B" -> MermaidVertex(id = "B", text = "B")
      ),
      edges = List(MermaidEdge(start = "A", end = "B")),
      subgraphs = List(MermaidSubgraph(id = "SG", title = Some("Cluster"), nodes = List("A")))
    )

    val parsedViewerGraph = toViewerGraph(mermaidGraph)
    val edgeId            = parsedViewerGraph.arrows.keys.head

    val editedNode = parsedViewerGraph.updateAttributes(
      ElementIds.from(NodeId("A")),
      AttributeUpdates.of(
        FillColor -> "#ff0",
        FontSize  -> 18.0
      )
    )
    val editedEdge = editedNode.updateAttributes(
      ElementIds.from(edgeId),
      AttributeUpdates.of(
        Color    -> "#f00",
        PenWidth -> 2.0
      )
    )
    val editedGraph = editedEdge.updateAttributes(
      ElementIds.from(GroupId("SG")),
      AttributeUpdates.of(
        FillColor -> "#eef",
        PenColor  -> "#333"
      )
    )

    val serialized = viewerGraphToMermaidText(editedGraph)

    assert(
      serialized.matches("(?s).*style A fill:#ff0,font-size:18(?:\\.0)?px.*"),
      s"Node edit should serialize to flat style directive, got: $serialized"
    )
    assert(
      serialized.matches("(?s).*linkStyle 0 stroke:#f00,stroke-width:2(?:\\.0)?px.*"),
      s"Edge edit should serialize to linkStyle directive, got: $serialized"
    )
    assert(serialized.contains("style SG fill:#eef,stroke:#333"), s"Group edit should serialize to style directive, got: $serialized")
