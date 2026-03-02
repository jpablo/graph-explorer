package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style
import org.jpablo.graphexplorer.viewer.models.AttributeId

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
