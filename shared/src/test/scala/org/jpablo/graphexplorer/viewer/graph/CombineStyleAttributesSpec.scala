package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap
import org.jpablo.graphexplorer.viewer.utils.TestAttrHelpers

class CombineStyleAttributesSpec extends FunSuite:

  // ----------------------
  // combineStyleAttributes
  // ----------------------

  test("combineStyleAttributes should assert on node with fillcolor but no filled style") {
    // This test case is based on the DOT input:
    // digraph "G" {
    //   graph [label=""];
    //   "c" [
    //     label="c",
    //     fillcolor="#fff085"
    //   ];
    // }

    // When a fillcolor is present but no style="filled", the fill sub-attribute should be true

    val nodeId = NodeId("c")

    val elements =
      ViewerGraphElements(
        nodes =
          VectorMap(
            nodeId ->
              ViewerNode.nodeNoDefaults(
                nodeId,
                Attributes.of(Label -> "c", FillColor -> "#fff085")
              )
          ),
        graphAttributes = Attributes.of(Label -> "")
      )

    // Calling combineStyleAttributes should be invalid and assert
    intercept[AssertionError] {
      elements.combineStyleAttributes
    }
  }

  // NOTE ABOUT NORMALIZATION
  // ------------------------
  // The core model normalizes fill semantics on write (in update paths), not at export time:
  //  - Setting FillColor to a concrete color implies FillStyle=true
  //  - Setting FillColor to "none" implies FillStyle=false
  // The combine step asserts if it finds FillColor present while FillStyle is not true.
  // Therefore, tests that set FillColor should do so via updateAttributes/defaultAttributesUpdates,
  // which mirrors how the UI interacts with the model and triggers normalization.

  test("combineStyleAttributes should handle node with fillcolor set via updates (normalized to filled)") {
    // This test case is based on the DOT input:
    // digraph "G" {
    //   graph [label=""];
    //   "c" [
    //     label="c",
    //     fillcolor="#fff085"
    //   ];
    // }

    val nodeId = NodeId("c")

    // Build a graph with node c (no fill initially)
    val graph0 = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeId -> ViewerNode.nodeNoDefaults(
          nodeId,
          Attributes.of(Label -> "c", BoldStyle -> false, BorderStyle -> BorderStyle.solid)
        )),
        graphAttributes = Attributes.of(Label -> "")
      )
    )

    // Simulate UI behavior: set only FillColor via update; core normalizes FillStyle=true
    val graph1 = TestAttrHelpers.setFillColor(graph0, nodeId, "#fff085")

    // Apply combineStyleAttributes for DOT export
    val result     = graph1.elements.combineStyleAttributes
    val resultNode = result.nodes(nodeId)

    val expectedAttributes = Attributes.of(
      // Other attributes should remain
      Label     -> "c",
      FillColor -> "#fff085",
      // The style attribute should be "filled" since fill=true and others are defaults
      Style -> Style.filled
      // The sub-attributes should be removed
    )

    assertEquals(resultNode.attributes, expectedAttributes)
  }

  test("combineStyleAttributes should handle multiple style sub-attributes") {
    val nodeId = NodeId("test")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes.of(
        FillStyle      -> true,
        BoldStyle      -> true,
        InvisibleStyle -> false,
        BorderStyle    -> BorderStyle.dashed,
        CornerStyle    -> CornerStyle.rounded
      )
    )

    val elements = ViewerGraphElements(nodes = VectorMap(nodeId -> node))
    val result   = elements.combineStyleAttributes

    val resultNode = result.nodes(nodeId)

    val expectedAttributes = Attributes(Map(
      // The style attribute should combine all non-default values
      Style.attrId -> AttrValue("filled,bold,rounded,dashed")
      // Sub-attributes should be removed
    ))

    assertEquals(resultNode.attributes, expectedAttributes)
  }

  test("combineStyleAttributes should handle default values") {
    val nodeId = NodeId("test")
    val node = ViewerNode.nodeNoDefaults(
      nodeId,
      Attributes.of(
        FillStyle      -> false,
        BoldStyle      -> false,
        InvisibleStyle -> false,
        BorderStyle    -> BorderStyle.solid,
        CornerStyle    -> CornerStyle.normal
      )
    )

    val elements = ViewerGraphElements(nodes = VectorMap(nodeId -> node))
    val result   = elements.combineStyleAttributes

    val resultNode = result.nodes(nodeId)

    // When all values are defaults, the combined style should be empty (reset)
    val expectedAttributes = Attributes(Map(Style.attrId -> AttrValue("")))

    assertEquals(resultNode.attributes, expectedAttributes)
  }
