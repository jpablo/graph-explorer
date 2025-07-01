package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraphElements}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

import scala.collection.immutable.VectorMap

class VizViewerGraphElementsSpec extends FunSuite:
  // ----------------------
  // expandStyleAttributes
  // ----------------------

  test("expandStyleAttributes should convert style string to sub-attributes") {
    val nodeId = NodeId("test")
    // Start with a node that has the combined style attribute
    val result =
      VizViewerGraphElements(
        nodes =
          VectorMap(
            nodeId -> ViewerNode.nodeNoDefaults(nodeId, Attributes(Map(Style.attrId -> AttrValue("filled,bold,rounded"))))
          )
      ).expandStyleAttributes

    val resultNode = result.nodes(nodeId)

    val expectedAttributes = Attributes.of(
      BoldStyle   -> true,
      CornerStyle -> CornerStyle.rounded,
      FillStyle   -> true
    )

    assertEquals(resultNode.attributes, expectedAttributes)

    // Style attribute should be removed
    assertEquals(resultNode.attributes.get(Style), None)

  }

  test("expandStyleAttributes should remove a fillcolor without corresponding fillstyle") {
    // Test case based on the DOT input:
    // digraph "G" {
    //   "a" [
    //     label="a",
    //     fillcolor="#fff085"
    //   ];
    // }

    val simple_graph_json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "strict": false,
         |  "bb": "0 0 54 36",
         |  "_subgraph_cnt": 0,
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "a",
         |      "fillcolor": "#fff085",
         |      "label": "a"
         |    }
         |  ]
         |}
         |
         |""".stripMargin

    val nodeId     = NodeId("a")
    val attributes = Attributes(Map(Label.attrId -> AttrValue("a"), AttributeId("_gvid") -> AttrValue("0")))

    val simpleGraph = read[SimpleGraph](simple_graph_json)
    val expanded    = toViewerGraphElements(simpleGraph).expandStyleAttributes

    assertEquals(expanded.nodes(nodeId).attributes, attributes)
  }

  test("expandStyleAttributes with style attribute present") {
    val nodeId = NodeId("styled")
    // Test expanding when there's an actual style attribute
    val elements = VizViewerGraphElements(
      nodes = VectorMap(nodeId -> ViewerNode.nodeNoDefaults(
        nodeId,
        Attributes.of(
          Label     -> "styled",
          FillColor -> "#fff085"
        ) + (Style.attrId -> AttrValue("filled,dashed"))
      ))
    )

    // Apply expandStyleAttributes
    val result     = elements.expandStyleAttributes
    val resultNode = result.nodes(nodeId)

    val expectedAttributes = Attributes.of(
      // Original attributes should remain
      Label     -> "styled",
      FillColor -> "#fff085",
      // Sub-attributes should be created based on the style string
      FillStyle   -> true,
      BorderStyle -> BorderStyle.dashed
    )

    assertEquals(resultNode.attributes, expectedAttributes)

    // Style attribute should be removed and expanded into sub-attributes
    assertEquals(resultNode.attributes.get(Style), None)

  }
