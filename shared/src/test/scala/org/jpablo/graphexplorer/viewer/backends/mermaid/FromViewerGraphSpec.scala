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

  test("round-trip edit flow should preserve subgraph class assignments when editing a node fill"):
    val mermaidGraph = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "CodeMirror", classes = List("pink")),
        "B" -> MermaidVertex(id = "B", text = "Parser")
      ),
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = List(
        MermaidSubgraph(
          id = "G1",
          title = Some("Service Layer"),
          nodes = List("A", "B"),
          classes = List("pink")
        )
      ),
      classDefs = Map(
        "default" -> MermaidClassDef(styles = List("fill:#fefecc", "stroke:#85df72")),
        "pink"    -> MermaidClassDef(styles = List("fill:#ff66cc", "stroke:#aa0099", "color:#ffffff"))
      )
    )

    val parsedViewerGraph = toViewerGraph(mermaidGraph)
    val editedGraph = parsedViewerGraph.updateAttributes(
      ElementIds.from(NodeId("B")),
      AttributeUpdates.of(
        FillColor -> "#b9f8cf"
      )
    )

    val serialized = viewerGraphToMermaidText(editedGraph)

    assert(serialized.contains("style B fill:#b9f8cf"), s"Node B edit should be serialized as inline style, got: $serialized")
    assert(serialized.contains("class G1 pink"), s"Subgraph class assignment should be preserved after node-only edit, got: $serialized")

  test("round-trip edit flow should preserve node class assignments from source when parser omits them"):
    val source =
      """flowchart LR
        |subgraph G1 [Service Layer]
        |  A[CodeMirror]
        |  B[Parser]
        |end
        |A -->|parses| B
        |classDef default fill:#fefecc,stroke:#85df72
        |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
        |class G1 pink
        |class A pink
        |""".stripMargin

    // Simulate current Mermaid parser behavior where subgraph class survives but vertex class assignment is dropped.
    val parserClassDefs = Map(
      "pink" -> MermaidClassDef(styles = List("fill:#ff66cc", "stroke:#aa0099", "color:#ffffff"))
    )
    val mergedClassDefs = MermaidClassDefFallback.withSourceClassDefs(source, parserClassDefs)
    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "CodeMirror"), // parser omitted `class A pink`
      "B" -> MermaidVertex(id = "B", text = "Parser")
    )
    val parserSubgraphs = List(
      MermaidSubgraph(
        id = "G1",
        title = Some("Service Layer"),
        nodes = List("A", "B"),
        classes = List("pink")
      )
    )
    val (verticesWithSourceClasses, mergedSubgraphs) =
      MermaidClassAssignmentFallback.withSourceClassAssignments(source, parserVertices, parserSubgraphs)
    val mergedVertices = MermaidVertexLabelFallback.withSourceVertexLabels(source, verticesWithSourceClasses)

    val parsedMermaidGraph = MermaidGraph(
      vertices = mergedVertices,
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = mergedSubgraphs,
      classDefs = mergedClassDefs
    )

    val parsedViewerGraph = toViewerGraph(parsedMermaidGraph)
    val editedGraph = parsedViewerGraph.updateAttributes(
      ElementIds.from(NodeId("B")),
      AttributeUpdates.of(
        FillColor -> "#ffb86a"
      )
    )

    val serialized = viewerGraphToMermaidText(editedGraph)

    assert(serialized.contains("class G1 pink"), s"Subgraph class should survive edit, got: $serialized")
    assert(
      serialized.contains("class A pink") || serialized.contains("A[CodeMirror]:::pink"),
      s"Node A should remain pink after unrelated B fill edit, got: $serialized"
    )

  test("round-trip edit flow should preserve node labels from source when parser omits vertex text"):
    val source =
      """flowchart LR
        |subgraph G1 [Service Layer]
        |  A[CodeMirror]
        |  B[Parser]
        |end
        |A -->|parses| B
        |classDef default fill:#fefecc,stroke:#85df72
        |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
        |class G1 pink
        |class A pink
        |linkStyle default stroke:#0044ff,stroke-width:2px
        |""".stripMargin

    // Simulate Mermaid parser output that preserves topology but drops node labels/classes.
    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "A"),
      "B" -> MermaidVertex(id = "B", text = "B")
    )
    val parserSubgraphs = List(
      MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink"))
    )
    val parserClassDefs = Map(
      "pink" -> MermaidClassDef(styles = List("fill:#ff66cc", "stroke:#aa0099", "color:#ffffff"))
    )

    val (verticesWithSourceClasses, mergedSubgraphs) =
      MermaidClassAssignmentFallback.withSourceClassAssignments(source, parserVertices, parserSubgraphs)
    val mergedVertices = MermaidVertexLabelFallback.withSourceVertexLabels(source, verticesWithSourceClasses)
    val mergedClassDefs = MermaidClassDefFallback.withSourceClassDefs(source, parserClassDefs)

    val parsedMermaidGraph = MermaidGraph(
      vertices = mergedVertices,
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = mergedSubgraphs,
      classDefs = mergedClassDefs,
      direction = Some("LR"),
      defaultEdgeStyle = List("stroke:#0044ff", "stroke-width:2px")
    )

    val parsedViewerGraph = toViewerGraph(parsedMermaidGraph)
    val editedGraph = parsedViewerGraph.updateAttributes(
      ElementIds.from(NodeId("B")),
      AttributeUpdates.of(FillColor -> "#b8e6fe")
    )

    val serialized = viewerGraphToMermaidText(editedGraph)

    assert(serialized.contains("class G1 pink"), s"Subgraph class should survive edit, got: $serialized")
    assert(
      serialized.contains("class A pink") || serialized.contains("A[CodeMirror]:::pink") || serialized.contains("A:::pink"),
      s"Node A class should survive edit, got: $serialized"
    )
    assert(serialized.contains("A[CodeMirror]"), s"Node A label should stay CodeMirror, got: $serialized")
    assert(serialized.contains("B[Parser]"), s"Node B label should stay Parser, got: $serialized")

  test("round-trip: edge label survives updateAttributes + viewerGraphToMermaidText + re-parse fallback"):
    // Simulate: user sets a label via the edit dialog, then source is re-parsed with MermaidEdgeLabelFallback
    val mg = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "A"),
        "B" -> MermaidVertex(id = "B", text = "B")
      ),
      edges = List(MermaidEdge(start = "A", end = "B", text = None)) // parser returns no label
    )
    val vg         = toViewerGraph(mg)
    val arrowId    = vg.arrows.keys.head
    val withLabel  = vg.updateAttributes(ElementIds.from(arrowId), AttributeUpdates.of(Label -> "approved"))
    val serialized = viewerGraphToMermaidText(withLabel)

    // The serialized text should include the pipe-label syntax
    assert(serialized.contains("-->|approved|"), s"Label should be in serialized text, got: $serialized")

    // Simulate fallback recovery: parser drops the label from the re-parsed edges
    val edgesDroppedByParser = List(MermaidEdge(start = "A", end = "B", text = None))
    val recovered            = MermaidEdgeLabelFallback.withSourceEdgeLabels(serialized, edgesDroppedByParser)

    assertEquals(recovered.head.text, Some("approved"), "Fallback should recover the label from serialized text")

  test("round-trip: edge label is preserved when parser correctly returns it"):
    // Simulate: parser correctly returns the label — fallback should leave it untouched
    val mg = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "A"),
        "B" -> MermaidVertex(id = "B", text = "B")
      ),
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("approved")))
    )
    val vg         = toViewerGraph(mg)
    val serialized = viewerGraphToMermaidText(vg)

    assert(serialized.contains("-->|approved|"), s"Serialized text should include the label, got: $serialized")

    // Re-parse: parser correctly returns the label
    val edgesWithLabel = List(MermaidEdge(start = "A", end = "B", text = Some("approved")))
    val result         = MermaidEdgeLabelFallback.withSourceEdgeLabels(serialized, edgesWithLabel)

    assertEquals(result.head.text, Some("approved"), "Parser label should be preserved unchanged")

  test("viewerGraphToMermaidText should serialize stored line breaks as <br/>"):
    // Stored labels are DOT-escaped: \n (2 chars) is a line break. Mermaid renders \n
    // literally, so the serializer must emit <br/> instead.
    val nodeA = NodeId("A")
    val nodeB = NodeId("B")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA, Attributes.of(Label -> "line1\\nline2")),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB)
    )
    val arrow = Arrow(nodeA, nodeB, attributes = Attributes.of(Label -> "up\\ndown"))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A[\"line1<br/>line2\"]"), s"Node label breaks should become <br/>, got: $result")
    assert(result.contains("|\"up<br/>down\"|"), s"Edge label breaks should become <br/>, got: $result")

  test("viewerGraphToMermaidText should serialize a stored literal \\n as verbatim text, not a line break"):
    // Stored a\\nb is the literal 4-char text a\nb (the user typed a backslash and an n)
    val nodeId = NodeId("A")
    val node   = ViewerNode.nodeWithDefaults(nodeId, Attributes.of(Label -> "a\\\\nb"))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("A[a\\nb]"), s"Literal backslash-n should stay verbatim, got: $result")

  test("viewerGraphToMermaidText should nest child subgraphs inside their parent"):
    val nodeA = NodeId("a")
    val nodeB = NodeId("b")
    val g1    = GroupId("G1")
    val g2    = GroupId("G2")
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          nodeA -> ViewerNode.nodeWithDefaults(nodeA),
          nodeB -> ViewerNode.nodeWithDefaults(nodeB)
        ),
        groups = Map(
          g1 -> ViewerGroup.group(g1, Attributes.empty),
          g2 -> ViewerGroup.group(g2, Attributes.empty)
        ),
        memberships = VectorMap(nodeA -> g1, g2 -> g1, nodeB -> g2)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    val expected =
      """  subgraph G1
        |    a
        |    subgraph G2
        |      b
        |    end
        |  end
        |""".stripMargin
    assert(result.contains(expected), s"Child group should be emitted inside its parent, got: $result")
