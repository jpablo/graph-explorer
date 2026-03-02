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
    val graph1 = graph0.updateAttributes(ElementIds.from(a), AttributeUpdates.of(CornerStyle -> CornerStyle.rounded, BorderStyle -> BorderStyle.dashed))

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

  test("getAttributesUpdatesById should resolve Mermaid node effective styles with precedence") {
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> nodeWithDefaults(
            a,
            Attributes(
              VectorMap(
                Style.attrId -> AttrValue("stroke:#444,stroke-width:4px,fill:#fcc,font-size:20px"),
                AttributeId("mermaid_class") -> AttrValue("pink")
              )
            )
          )
        ),
        graphAttributes = Attributes(
          VectorMap(
            AttributeId("mermaid_classDef_default") -> AttrValue("fill:#aaa,color:#111,font-size:12px"),
            AttributeId("mermaid_classDef_pink") -> AttrValue("fill:#f9f,stroke:#222,color:#eee,font-family:Verdana,font-size:18px")
          )
        )
      )
    )

    val result = graph.getAttributesUpdatesById(ElementIds.from(a))

    assertEquals(result.statuses(FillColor.attrId), AttrStatus.Single(AttrValue("#fcc")))
    assertEquals(result.statuses(Color.attrId), AttrStatus.Single(AttrValue("#444")))
    assertEquals(result.statuses(PenWidth.attrId), AttrStatus.Single(AttrValue("4")))
    assertEquals(result.statuses(FontColor.attrId), AttrStatus.Single(AttrValue("#eee")))
    assertEquals(result.statuses(FontName.attrId), AttrStatus.Single(AttrValue("Verdana")))
    assertEquals(result.statuses(FontSize.attrId), AttrStatus.Single(AttrValue("20")))
  }

  test("getAttributesUpdatesById should not override explicit node attributes when Mermaid-derived values exist") {
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> nodeWithDefaults(
            a,
            Attributes(
              VectorMap(
                FillColor.attrId -> AttrValue("#0af"),
                Style.attrId -> AttrValue("fill:#fcc"),
                AttributeId("mermaid_class") -> AttrValue("pink")
              )
            )
          )
        ),
        graphAttributes = Attributes(
          VectorMap(
            AttributeId("mermaid_classDef_pink") -> AttrValue("fill:#f9f")
          )
        )
      )
    )

    val result = graph.getAttributesUpdatesById(ElementIds.from(a))

    assertEquals(result.statuses(FillColor.attrId), AttrStatus.Single(AttrValue("#0af")))
  }

  test("getAttributesUpdatesById should resolve Mermaid edge effective styles with precedence") {
    val arrow = Arrow(
      a,
      b,
      Attributes(
        VectorMap(
          AttributeId("mermaid_edgeStyle") -> AttrValue("stroke:#f00,stroke-width:4px,color:#fff,font-size:16px")
        )
      )
    )
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
        arrows = Map(arrow.id -> arrow),
        graphAttributes = Attributes(
          VectorMap(
            AttributeId("mermaid_linkStyle_default") -> AttrValue("stroke:#00f,stroke-width:2px,font-size:10px")
          )
        )
      )
    )

    val result = graph.getAttributesUpdatesById(ElementIds.from(arrow.id))

    assertEquals(result.statuses(Color.attrId), AttrStatus.Single(AttrValue("#f00")))
    assertEquals(result.statuses(PenWidth.attrId), AttrStatus.Single(AttrValue("4")))
    assertEquals(result.statuses(FontColor.attrId), AttrStatus.Single(AttrValue("#fff")))
    assertEquals(result.statuses(FontSize.attrId), AttrStatus.Single(AttrValue("16")))
  }

  test("getAttributesUpdatesById should not override explicit edge attributes with Mermaid-derived values") {
    val arrow = Arrow(
      a,
      b,
      Attributes(
        VectorMap(
          Color.attrId -> AttrValue("#0af"),
          AttributeId("mermaid_edgeStyle") -> AttrValue("stroke:#f00")
        )
      )
    )
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = graph.getAttributesUpdatesById(ElementIds.from(arrow.id))

    assertEquals(result.statuses(Color.attrId), AttrStatus.Single(AttrValue("#0af")))
  }

  test("getAttributesUpdatesById should resolve Mermaid group effective styles with precedence") {
    val gid = GroupId("cluster1")
    val graph = ViewerGraph(
      ViewerGraphElements(
        groups = Map(
          gid -> ViewerGroup.group(
            gid,
            Attributes(
              VectorMap(
                AttributeId("mermaid_class") -> AttrValue("clusterX"),
                Style.attrId -> AttrValue("stroke:#666,stroke-width:2px")
              )
            )
          )
        ),
        graphAttributes = Attributes(
          VectorMap(
            AttributeId("mermaid_classDef_default") -> AttrValue("fill:#eee,color:#111,font-size:11px"),
            AttributeId("mermaid_classDef_clusterX") -> AttrValue("fill:#ddd,stroke:#333,color:#fafafa,font-family:Verdana,font-size:14px")
          )
        )
      )
    )

    val result = graph.getAttributesUpdatesById(ElementIds.from(gid))

    assertEquals(result.statuses(FillColor.attrId), AttrStatus.Single(AttrValue("#ddd")))
    assertEquals(result.statuses(PenColor.attrId), AttrStatus.Single(AttrValue("#666")))
    assertEquals(result.statuses(PenWidth.attrId), AttrStatus.Single(AttrValue("2")))
    assertEquals(result.statuses(FontColor.attrId), AttrStatus.Single(AttrValue("#fafafa")))
    assertEquals(result.statuses(FontName.attrId), AttrStatus.Single(AttrValue("Verdana")))
    assertEquals(result.statuses(FontSize.attrId), AttrStatus.Single(AttrValue("14")))
  }
