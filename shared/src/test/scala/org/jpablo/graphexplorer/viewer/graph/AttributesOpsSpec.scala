package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.NodeStyle.{bold, dashed, filled}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.{defaultNodeAttributes, nodeWithDefaults, nodeWithId}

import scala.collection.immutable.VectorMap

class AttributesOpsSpec extends FunSuite:

  // Common test setup
  val rootId    = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val a                   = NodeId("a")
  val b                   = NodeId("b")
  val c                   = NodeId("c")
  val groupId             = GroupId("1")
  val trueAttr: AttrValue = AttrValue(true.toString)

  val testVizViewerGraphElements =
    val arrow = Arrow(a, b)
    VizViewerGraphElements(
      nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
      arrows = Map(arrow.id -> arrow),
      groups = Map(groupId -> group(groupId, Attributes.of(Label -> "Cluster 1")))
    )

  // Helper method to create a basic graph for testing
  def createTestGraph(): ViewerGraph =
    val arrow = Arrow(a, b)
    ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
        arrows = Map(arrow.id -> arrow),
        groups = Map(groupId -> group(groupId, Attributes.of(Label -> "Cluster 1")))
      )
    )

  test("removeUnsupportedFeatures should remove 'size' attribute from root graph") {
    // Create a graph with a 'size' attribute
    val graph = createTestGraph()

    // Apply the method
    val result = graph.withoutUnsupportedFeatures

    // Verify the 'size' attribute is removed
    assertEquals(
      result.elements.graphAttributes.get(Size.attrId),
      None,
      "The 'size' attribute should be removed"
    )
  }

  test("expandStyleAttributes should expand style attributes into sub-attributes") {
    // Create a graph with a style attribute

    val styleValue = AttrValue(Seq(filled, bold, dashed).mkString(","))
    val elements = testVizViewerGraphElements
      .modify(_.nodes)
      .setTo(
        VectorMap(
          a -> nodeWithDefaults(a, Attributes(Map(NodeStyle.attrId -> styleValue))),
          b -> nodeWithDefaults(b),
          c -> nodeWithDefaults(c)
        )
      )

    // We need to create a new graph with the expanded elements to test it
    val result = elements.expandStyleAttributes

    // Verify the style attribute is expanded using getNode
    val nodeAttrs = result.nodes(a).attributes

    // The original style attribute should be removed
    assertEquals(nodeAttrs.get(NodeStyle), None, "The style attribute should be removed")

    // The sub-attributes should be present
    assertEquals(nodeAttrs.get(FillStyle), Some(trueAttr), "fillStyle should be present")
    assertEquals(nodeAttrs.get(BoldStyle), Some(trueAttr), "boldStyle should be present")
    assertEquals(nodeAttrs.get(BorderStyle), Some(AttrValue(dashed.toString)), "borderStyle should be present")
  }

  test("combineStyleAttributes should combine sub-attributes into a style attribute and ignore `filled`") {
    // Create a graph with sub-attributes
    val graph = createTestGraph()
      .modifyNodes.setTo(
        VectorMap(
          a -> nodeWithDefaults(a, Attributes.of(FillStyle -> true, BoldStyle -> true, BorderStyle -> BorderStyle.dashed)),
          b -> nodeWithDefaults(b),
          c -> nodeWithDefaults(c)
        )
      ).modify(_.elements.groups.at(groupId)).using { g =>
        // make sure that importing a group honors the filled attribute
        g.modifyAttrs.using(_ ++ Attributes.of(FillStyle -> true))
      }

    // We need to create a new graph with the combined elements to test it
    val modifiedGraph = graph.modifyElements.setTo(graph.elements.combineStyleAttributes)

    // Verify the sub-attributes are combined using getNode
    val nodeAttrs = modifiedGraph.getNode(a).get.attributes

    // The sub-attributes should be removed
    assertEquals(nodeAttrs.get(FillStyle), None, "fillstyle should be removed")
    assertEquals(nodeAttrs.get(BoldStyle), None, "boldstyle should be removed")
    assertEquals(nodeAttrs.get(BorderStyle), None, "borderstyle should be removed")

    // The style attribute should be present with the combined value
    assertEquals(nodeAttrs.get(NodeStyle), Some(AttrValue("filled,bold,dashed")), "style attribute should contain the combined values")

    val groupAttrs = modifiedGraph.groups(groupId).attributes
    val expected =
      ViewerGroup.defaultGroupAttributes ++ Attributes.of(
        Style -> Style.filled,
        Label -> "Cluster 1"
      )
    assertEquals(groupAttrs, expected)
  }

  test("combineStyleAttributes should combine sub-attributes") {
    // Create a graph with sub-attributes
    val graph0 = ViewerGraph.minimal
      .addNodeWithId(a)

    // update node a
    val updateAttributes = AttributesOps.elementAttributesUpdates(ElementIds.from(a)).update
    val graph1 = updateAttributes(graph0, AttributeUpdates.of(CornerStyle -> CornerStyle.rounded, BorderStyle -> BorderStyle.dashed))

    import upickle.default.*
    pprint.log(write(graph0.elements, indent = 2))
    pprint.log(write(graph1.elements, indent = 2))
    // ------------------------------------------------------------------
    // We need to create a new graph with the combined elements to test it
    val elements = graph1.elements.combineStyleAttributes
    pprint.log(write(elements, indent = 2))
    // ------------------------------------------------------------------

    // we need to copy the global style into the element style to avoid overriding it.
    assertEquals(
      obtained = elements.nodes(a).attributes.get(NodeStyle).get.toString,
      expected = "rounded,dashed"
    )
  }

  test("updateAttributes should update attributes for nodes") {
    val graph = createTestGraph()

    // Apply the method
    val result =
      graph.updateAttributes(ElementIds.from(a), AttributeUpdates.of(Color -> "red"))

    // Verify the attributes are updated
    assertEquals(
      result.getAttributesById(a),
      defaultNodeAttributes ++ Attributes.of(Color -> "red"),
      "Node attributes should be updated"
    )

    // Verify that the original attributes are preserved
    val result2 =
      result.updateAttributes(ElementIds.from(a), AttributeUpdates.of(Shape -> Shape.box))

    assertEquals(
      result2.getAttributesById(a),
      defaultNodeAttributes ++ Attributes.of(Color -> "red", Shape -> Shape.box),
      "Node attributes should be updated"
    )
  }

  test("updateAttributes should update attributes for arrows") {
    val graph = createTestGraph()
    // Get the arrow ID from the graph
    val arrowId = Arrow(a, b).id
    val updates = AttributeUpdates.of(Color -> "blue")

    // Apply the method
    val result = graph.updateAttributes(ElementIds.from(arrowId), updates)

    // Verify the attributes are updated using getAttributesById
    assertEquals(
      result.getAttributesById(arrowId).get(Color.attrId),
      Some(AttrValue("blue")),
      "Arrow attributes should be updated"
    )
  }

  test("updateAttributes should update attributes for groups") {
    val graph   = createTestGraph()
    val updates = AttributeUpdates.of(Color -> "green")

    // Apply the method
    val result = graph.updateAttributes(ElementIds.from(groupId), updates)

    // Verify the attributes are updated using getAttributesById
    assertEquals(
      result.getAttributesById(groupId).get(Color.attrId),
      Some(AttrValue("green")),
      "Group attributes should be updated"
    )
  }

  test("getAttributesUpdatesById should return attributes for a node") {
    val graph = createTestGraph()
      .modifyNodes.using(_ ++
        Map(a -> nodeWithDefaults(a, Attributes.of(Color -> "red", Shape -> Shape.box))))

    // Apply the method
    val result = graph.getAttributesUpdatesById(ElementIds.from(a))

    // Verify the attributes are returned
    assertEquals(
      result.statuses(Color.attrId),
      AttrStatus.Single(AttrValue("red")),
      "Node color attribute should be returned"
    )
    assertEquals(
      result.statuses(Shape.attrId),
      AttrStatus.Single(AttrValue(Shape.box.toString)),
      "Node shape attribute should be returned"
    )
  }
