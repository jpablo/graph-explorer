package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget, nodeWithId}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowTail, ArrowType, BoldStyle, BorderStyle, Color, Dir, DirType, FillStyle, Label, NodeStyle, Shape, Sides, Size, Style}
import org.jpablo.graphexplorer.viewer.models.*

class AttributesOpsSpec extends FunSuite:

  // Common test setup
  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = ViewerGroup(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")
  val groupId = GroupId("cluster_1")

  // Helper method to create a basic graph for testing
  def createTestGraph(): ViewerGraph =
    val arrow = Arrow(a, b)
    ViewerGraph(
      ViewerGraphElements(
        nodes  = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
        arrows = Map(arrow.id -> arrow),
        groups = Map(
          rootId -> rootGroup,
          groupId -> ViewerGroup(
            id         = groupId,
            attributes = Attributes.of(Label -> "Cluster 1"),
            nodeAttrs  = Attributes.of(Shape -> Shape.box),
            arrowAttrs = Attributes.of(Style -> Style.dashed)
          )
        )
      )
    )

  test("removeUnsupportedFeatures should remove 'size' attribute from root graph") {
    // Create a graph with a 'size' attribute
    val graph = createTestGraph()
      .modifyRootGraphAttrs.using(_ + (Size.attrId -> AttrValue("10,10")))

    // Apply the method
    val result = graph.removeUnsupportedFeatures

    // Verify the 'size' attribute is removed
    assertEquals(
      result.rootGroup.attributes.get(Size.attrId),
      None,
      "The 'size' attribute should be removed"
    )
  }

  test("expandStyleAttributes should expand style attributes into sub-attributes") {
    // Create a graph with a style attribute
    val styleValue = AttrValue("filled,bold,dashed")
    val graph = createTestGraph()
      .modifyNodes.setTo(
        Map(
          a -> ViewerNode(a, Attributes(Map(NodeStyle.attrId -> styleValue))),
          b -> ViewerNode(b),
          c -> ViewerNode(c)
        )
      )

    // We need to create a new graph with the expanded elements to test it
    val result = graph.modifyElements.setTo(graph.expandStyleAttributes)

    // Verify the style attribute is expanded using getNode
    val nodeAttrs = result.getNode(a).get.attributes

    // The original style attribute should be removed
    assertEquals(nodeAttrs.get(NodeStyle.attrId), None, "The style attribute should be removed")

    // The sub-attributes should be present
    assertEquals(nodeAttrs.get(FillStyle.attrId), Some(AttrValue(true.toString)), "fillStyle should be present")
    assertEquals(nodeAttrs.get(BoldStyle.attrId), Some(AttrValue(true.toString)), "boldStyle should be present")
    assertEquals(
      nodeAttrs.get(BorderStyle.attrId),
      Some(AttrValue(BorderStyle.dashed.toString)),
      "borderStyle should be present"
    )
  }

  test("combineStyleAttributes should combine sub-attributes into a style attribute") {
    // Create a graph with sub-attributes
    val graph = createTestGraph()
      .modifyNodes.setTo(
        Map(
          a -> ViewerNode(
            a,
            Attributes(Map(
              FillStyle.attrId   -> AttrValue(true.toString),
              BoldStyle.attrId   -> AttrValue(true.toString),
              BorderStyle.attrId -> AttrValue(BorderStyle.dashed.toString)
            ))
          ),
          b -> ViewerNode(b),
          c -> ViewerNode(c)
        )
      )

    // We need to create a new graph with the combined elements to test it
    val result = graph.modifyElements.setTo(graph.combineStyleAttributes)

    // Verify the sub-attributes are combined using getNode
    val nodeAttrs = result.getNode(a).get.attributes

    // The sub-attributes should be removed
    assertEquals(nodeAttrs.get(FillStyle.attrId), None, "fillstyle should be removed")
    assertEquals(nodeAttrs.get(BoldStyle.attrId), None, "boldstyle should be removed")
    assertEquals(nodeAttrs.get(BorderStyle.attrId), None, "borderstyle should be removed")

    // The style attribute should be present with the combined value
    assertEquals(
      nodeAttrs.get(NodeStyle.attrId),
      Some(AttrValue("filled,bold,dashed")),
      "style attribute should contain the combined values"
    )
  }

  test("combineStyleAttributes should use rootGroup.nodeAttrs as defaults for nodes.attributes") {
    // Create a graph with sub-attributes
    val graph0 = ViewerGraph.minimal
      .addNodeWithId(a)
      .updateRootAttributes(AttributeTarget.node)(_ + (BoldStyle.attrId -> AttrValue(true.toString)))

    val updateAttributes = AttributesOps.elementAttributesUpdates(ElementIds.from(a)).out
    val updates = AttributesUpdates(update = Map(BorderStyle.attrId -> AttrValue(BorderStyle.dashed.toString)))

    val graph1 = updateAttributes(graph0, updates)

    // ------------------------------------------------------------------
    // We need to create a new graph with the combined elements to test it
    val graph2 = graph1.modifyElements.setTo(graph1.combineStyleAttributes)
    // ------------------------------------------------------------------

    assertEquals(
      obtained = graph2.getNode(a).get.attributes.get(NodeStyle.attrId).get.toString,
      expected = "bold,dashed",
      "style attribute should contain the combined values"
    )
  }

  test("updateAttributes should update attributes for nodes") {
    val graph = createTestGraph()

    // Apply the method
    val result =
      graph.updateAttributes(ElementIds.from(a), AttributesUpdates(update = Attributes.of(Color -> "red").values))

    // Verify the attributes are updated
    assertEquals(
      result.getAttributesById(a),
      Attributes.of(Color -> "red"),
      "Node attributes should be updated"
    )

    // Verify that the original attributes are preserved
    val result2 =
      result.updateAttributes(ElementIds.from(a), AttributesUpdates(update = Attributes.of(Shape -> Shape.box).values))

    assertEquals(
      result2.getAttributesById(a),
      Attributes.of(Color -> "red", Shape -> Shape.box),
      "Node attributes should be updated"
    )
  }

  test("updateAttributes should update attributes for arrows") {
    val graph = createTestGraph()
    // Get the arrow ID from the graph
    val arrowId = Arrow(a, b).id
    val updates = AttributesUpdates(update = Attributes.of(Color -> "blue").values)

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
    val graph = createTestGraph()
    val updates = AttributesUpdates(
      update = Attributes.of(Color -> "green").values
    )

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
        Map(a -> ViewerNode(a, Attributes.of(Color -> "red", Shape -> Shape.box))))

    // Apply the method
    val result = graph.getAttributesUpdatesById(ElementIds.from(a))

    // Verify the attributes are returned
    assertEquals(
      result.existing(Color.attrId),
      AttrStatus.Single(AttrValue("red")),
      "Node color attribute should be returned"
    )
    assertEquals(
      result.existing(Shape.attrId),
      AttrStatus.Single(AttrValue(Shape.box.toString)),
      "Node shape attribute should be returned"
    )
  }

  test("getRootAttributes should return attributes for the specified target") {
    val graph = createTestGraph()

    // Test for graph attributes
    assertEquals(
      graph.getRootAttributes(AttributeTarget.graph),
      rootGroup.attributes,
      "Should return root graph attributes"
    )

    // Test for node attributes
    assertEquals(
      graph.getRootAttributes(AttributeTarget.node),
      rootGroup.nodeAttrs,
      "Should return root node attributes"
    )

    // Test for edge attributes
    assertEquals(
      graph.getRootAttributes(AttributeTarget.edge),
      rootGroup.arrowAttrs,
      "Should return root edge attributes"
    )
  }

  test("updateRootAttributes should update attributes for the specified target") {
    val graph = createTestGraph()

    // Apply the method for graph attributes
    val result = graph.updateRootAttributes(AttributeTarget.graph)(_ + (Color.attrId -> AttrValue("purple")))

    // Verify the attributes are updated
    assertEquals(
      result.rootGroup.attributes.get(Color.attrId),
      Some(AttrValue("purple")),
      "Root graph attributes should be updated"
    )
  }

  test("setDefaultTheme should set default theme for nodes and edges") {
    val graph = createTestGraph()

    // Apply the method
    val result = graph.setDefaultTheme

    // Verify the default theme is set
    assertEquals(
      result.rootGroup.nodeAttrs.get(Sides.attrId),
      Some(AttrValue("5")),
      "Default node theme should be set"
    )

    assertEquals(
      result.rootGroup.arrowAttrs.get(Dir.attrId),
      Some(AttrValue(DirType.both.toString)),
      "Default edge theme should be set"
    )

    assertEquals(
      result.rootGroup.arrowAttrs.get(ArrowTail.attrId),
      Some(AttrValue(ArrowType.none.toString)),
      "Default edge theme should be set"
    )
  }
