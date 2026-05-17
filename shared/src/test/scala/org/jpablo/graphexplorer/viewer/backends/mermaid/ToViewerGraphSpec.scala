package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style
import org.jpablo.graphexplorer.viewer.models.AttributeId
import org.jpablo.graphexplorer.viewer.models.GroupId
import org.jpablo.graphexplorer.viewer.models.NodeId

class ToViewerGraphSpec extends FunSuite:

  test("toViewerGraph should preserve classDefs including default and text styles"):
    val mg = MermaidGraph(
      vertices = Map("A" -> MermaidVertex(id = "A", text = "A")),
      classDefs = Map(
        "default" -> MermaidClassDef(
          styles = List("fill:#fafafa", "stroke:#222"),
          textStyles = List("fill:#111")
        ),
        "highlight" -> MermaidClassDef(
          styles = List("fill:#f9f", "stroke:#333")
        )
      )
    )

    val attrs = toViewerGraph(mg).elements.graphAttributes.values

    assertEquals(
      attrs.get(AttributeId("mermaid_classDef_default")).map(_.toString),
      Some("fill:#fafafa,stroke:#222")
    )
    assertEquals(
      attrs.get(AttributeId("mermaid_classDefText_default")).map(_.toString),
      Some("fill:#111")
    )
    assertEquals(
      attrs.get(AttributeId("mermaid_classDef_highlight")).map(_.toString),
      Some("fill:#f9f,stroke:#333")
    )

  test("toViewerGraph should preserve edge style inputs needed for flattening"):
    val mg = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "A", text = "A"),
        "B" -> MermaidVertex(id = "B", text = "B")
      ),
      edges = List(
        MermaidEdge(
          start = "A",
          end = "B",
          stroke = Some("dotted"),
          styles = List("stroke:#f00", "stroke-width:3px"),
          interpolate = Some("basis")
        )
      ),
      defaultEdgeStyle = List("stroke:#00f", "stroke-width:2px"),
      defaultEdgeInterpolate = Some("linear")
    )

    val viewerGraph = toViewerGraph(mg)
    val graphAttrs  = viewerGraph.elements.graphAttributes.values
    val edgeAttrs   = viewerGraph.elements.arrows.values.head.attributes.values

    assertEquals(
      graphAttrs.get(AttributeId("mermaid_linkStyle_default")).map(_.toString),
      Some("stroke:#00f,stroke-width:2px")
    )
    assertEquals(
      graphAttrs.get(AttributeId("mermaid_linkInterpolate_default")).map(_.toString),
      Some("linear")
    )
    assertEquals(
      edgeAttrs.get(AttributeId("mermaid_edgeStyle")).map(_.toString),
      Some("stroke:#f00,stroke-width:3px")
    )
    assertEquals(
      edgeAttrs.get(AttributeId("mermaid_edgeInterpolate")).map(_.toString),
      Some("basis")
    )
    assertEquals(edgeAttrs.get(Style.attrId).map(_.toString), Some("dashed"))

  test("toViewerGraph should preserve Mermaid subgraph classes"):
    val mg = MermaidGraph(
      vertices = Map("A" -> MermaidVertex(id = "A", text = "A")),
      subgraphs = List(
        MermaidSubgraph(
          id = "ClusterA",
          title = Some("Cluster A"),
          nodes = List("A"),
          classes = List("clusterX")
        )
      )
    )

    val groupAttrs = toViewerGraph(mg).elements.groups(GroupId("ClusterA")).attributes.values

    assertEquals(
      groupAttrs.get(AttributeId("mermaid_class")).map(_.toString),
      Some("clusterX")
    )

  test("toViewerGraph should key nodes by vertex.id when parser dictionary keys are synthetic"):
    val mg = MermaidGraph(
      vertices = Map(
        "flowchart-A-0" -> MermaidVertex(id = "A", text = "CodeMirror", classes = List("pink")),
        "flowchart-B-0" -> MermaidVertex(id = "B", text = "Parser")
      ),
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = List(MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink")))
    )

    val vg = toViewerGraph(mg)

    assert(vg.nodes.contains(NodeId("A")), "Node A should exist with canonical id")
    assert(vg.nodes.contains(NodeId("B")), "Node B should exist with canonical id")
    assert(!vg.nodes.contains(NodeId("flowchart-A-0")), "Synthetic parser key must not leak as node id")
    assert(!vg.nodes.contains(NodeId("flowchart-B-0")), "Synthetic parser key must not leak as node id")
    assertEquals(vg.nodes(NodeId("A")).attributes.get(AttributeId("label")).map(_.toString), Some("CodeMirror"))
    assertEquals(vg.nodes(NodeId("A")).attributes.get(AttributeId("mermaid_class")).map(_.toString), Some("pink"))
    assertEquals(vg.nodes(NodeId("B")).attributes.get(AttributeId("label")).map(_.toString), Some("Parser"))

  test("toViewerGraph should keep parser dictionary keys when they are the ids referenced by edges/subgraphs"):
    val mg = MermaidGraph(
      vertices = Map(
        "A" -> MermaidVertex(id = "flowchart-A-0", text = "CodeMirror", classes = List("pink")),
        "B" -> MermaidVertex(id = "flowchart-B-0", text = "Parser")
      ),
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = List(MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink")))
    )

    val vg = toViewerGraph(mg)

    assert(vg.nodes.contains(NodeId("A")), "Node A should exist using dictionary key id")
    assert(vg.nodes.contains(NodeId("B")), "Node B should exist using dictionary key id")
    assert(!vg.nodes.contains(NodeId("flowchart-A-0")), "DOM-like vertex ids must not replace referenced graph ids")
    assert(!vg.nodes.contains(NodeId("flowchart-B-0")), "DOM-like vertex ids must not replace referenced graph ids")
    assertEquals(vg.nodes(NodeId("A")).attributes.get(AttributeId("label")).map(_.toString), Some("CodeMirror"))
    assertEquals(vg.nodes(NodeId("A")).attributes.get(AttributeId("mermaid_class")).map(_.toString), Some("pink"))
    assertEquals(vg.nodes(NodeId("B")).attributes.get(AttributeId("label")).map(_.toString), Some("Parser"))
