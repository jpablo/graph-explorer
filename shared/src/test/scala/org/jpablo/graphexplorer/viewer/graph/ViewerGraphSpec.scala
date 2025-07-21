package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.SimpleGraphConverter
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

import scala.collection.immutable.VectorMap

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId    = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addArrow should add an arrow between two nodes") {
    val arrow = Arrow(a, b)
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          arrows = Map(arrow.id -> Arrow(a, b))
        )
      )

    val updated = graph.addArrow(a, b)._1

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an arrow") {
    val arrow = Arrow(a, b)
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> arrow)
        )
      )
    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> Arrow(a, b, Attributes.of(Style -> Style.dashed)))
        )
      )

    val updated =
      graph.updateAttributes(
        ElementIds.from(arrow.id),
        AttributeUpdates.of(Style -> Style.dashed)
      )

    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("b->c:0")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(b, c)
          )
        )
      )

    val expected =
      ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(c))))

    val updated = graph.removeElements(ElementIds.from(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("a->b:1")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrowId2 -> Arrow(a, b, seq = 1))
        )
      )

    val updated = graph.removeElements(ElementIds.from(arrowId1))

    assertEquals(updated, expected)
  }

  test("removeElements should clean up memberships when removing nodes from groups") {
    // Reproduce the scenario from the DOT state:
    // digraph "G" {
    //   subgraph "gdd30b2d0" {
    //     "a" [label="a"];
    //     "b" [label=""];
    //   }
    //   "a" -> "b";
    // }

    val groupId = GroupId("gdd30b2d0")
    val aNode   = NodeId("a")
    val bNode   = NodeId("b")

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, "label" -> "a"),
        nodeWithId(bNode, "label" -> "")
      ),
      arrows = Map(
        ArrowId("a->b:0") -> Arrow(aNode, bNode)
      ),
      groups = Map(
        groupId -> group(
          groupId,
          Attributes.of(
            "label"   -> "A title",
            "lheight" -> "0.23",
            "lp"      -> "43,169.2",
            "lwidth"  -> "0.49",
            "cluster" -> "true"
          )
        )
      ),
      memberships = Map(
        aNode -> groupId,
        bNode -> groupId
      ),
      graphAttributes = Attributes.of("label" -> "A title")
    )

    val graph = ViewerGraph(elements)

    // Try to remove node "b" - this should not cause NoSuchElementException
    val updatedGraph = graph.removeElements(ElementIds.from(bNode))

    // Verify the node was removed
    assert(!updatedGraph.nodes.contains(bNode))

    // Verify the arrow was also removed (since it referenced the removed node)
    assertEquals(updatedGraph.arrows.size, 0)

    // Verify membership was cleaned up
    assert(!updatedGraph.memberships.contains(bNode))

    // This should not throw NoSuchElementException when converting to SimpleGraph
    val simpleGraph = SimpleGraphConverter.fromViewerGraphElements(updatedGraph.elements)
    assert(simpleGraph != null)
  }

  test("duplicateSelection should duplicate a single node with its attributes") {
    // Initial graph: digraph "G" { graph [label=""]; "a" [label="a"]; }
    val aNode = NodeId("a")
    val initialGraph = ViewerGraph(ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, "label" -> "a")
      ),
      graphAttributes = Attributes.of("label" -> "")
    ))

    // Select node "a" for duplication
    val selectedIds = IdsByKind(nodes = Set(aNode))

    // Duplicate the selection
    val (resultGraph, newElementIds) = initialGraph.duplicateSelection(selectedIds)

    // Verify the result
    assertEquals(resultGraph.nodes.size, 2)

    // The original node should still exist
    assert(resultGraph.nodes.contains(aNode))
    assertEquals(resultGraph.nodes(aNode).label.toString, "a")

    // There should be exactly one new node
    val newNodeIds = newElementIds.collect { case id: NodeId => id }
    assertEquals(newNodeIds.size, 1)

    // The new node should be "b" (based on numberToLetterId logic: 1 -> a, 2 -> b)
    val newNodeId = newNodeIds.head
    assertEquals(newNodeId.value, "b")

    // The new node should have the same attributes as the original
    assert(resultGraph.nodes.contains(newNodeId))
    assertEquals(resultGraph.nodes(newNodeId).label.toString, "a")
  }

  test("duplicateSelection should duplicate a node within a group and keep it in the same group") {
    // Initial graph: 
    // digraph "G" {
    //   graph [label=""];
    //   subgraph "gdd2a77a5" {
    //     graph [label="", cluster="true"];
    //     "a" [label="a"];
    //   }
    // }
    val aNode = NodeId("a")
    val groupId = GroupId("gdd2a77a5")
    
    val initialElements = ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, "label" -> "a")
      ),
      groups = Map(
        rootId -> rootGroup,
        groupId -> group(groupId, Attributes.of("label" -> "", "cluster" -> "true"))
      ),
      memberships = Map(
        aNode -> groupId
      ),
      graphAttributes = Attributes.of("label" -> "")
    )
    val initialGraph = ViewerGraph(initialElements)
    
    // Select only node "a" for duplication
    val selectedIds = IdsByKind(nodes = Set(aNode))
    
    // Duplicate the selection
    val (resultGraph, newElementIds) = initialGraph.duplicateSelection(selectedIds)

    pprint.log(resultGraph)
    pprint.log(SimpleGraphConverter.viewerGraphElementsToDotString(resultGraph.elements))

    // Verify the result
    assertEquals(resultGraph.nodes.size, 2)
    
    // The original node should still exist and remain in the group
    assert(resultGraph.nodes.contains(aNode))
    assertEquals(resultGraph.nodes(aNode).label.toString, "a")
    assertEquals(resultGraph.membership(aNode), Some(groupId))
    
    // There should be exactly one new node
    val newNodeIds = newElementIds.collect { case id: NodeId => id }
    assertEquals(newNodeIds.size, 1)
    
    // The new node should be "b"
    val newNodeId = newNodeIds.head
    assertEquals(newNodeId.value, "b")
    
    // The new node should have the same attributes as the original
    assert(resultGraph.nodes.contains(newNodeId))
    assertEquals(resultGraph.nodes(newNodeId).label.toString, "a")
    
    // The new node should be in the same group as the original
    assertEquals(resultGraph.membership(newNodeId), Some(groupId))
    
    // The group should remain unchanged
    assert(resultGraph.groups.contains(groupId))
    val groupAttrs = resultGraph.groups(groupId).attributes
    assertEquals(groupAttrs.values(AttributeId("label")).toString, "")
    assertEquals(groupAttrs.values(AttributeId("cluster")).toString, "true")
  }

  test("duplicateSelection should not copy layout-specific attributes") {
    // Initial graph with a node that has layout attributes
    val aNode = NodeId("a")
    val initialGraph = ViewerGraph(ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, 
          "label" -> "a",
          "_gvid" -> "123",
          "width" -> "0.75",
          "pos" -> "10,20",
          "height" -> "0.5",
          "color" -> "red"  // This should be copied
        )
      )
    ))
    
    // Select node "a" for duplication
    val selectedIds = IdsByKind(nodes = Set(aNode))
    
    // Duplicate the selection
    val (resultGraph, newElementIds) = initialGraph.duplicateSelection(selectedIds)
    
    // Get the new node
    val newNodeId = newElementIds.collect { case id: NodeId => id }.head
    val newNode = resultGraph.nodes(newNodeId)
    
    // Verify that layout-specific attributes are not copied
    assert(!newNode.attributes.contains(AttributeId("_gvid")))
    assert(!newNode.attributes.contains(AttributeId("width")))
    assert(!newNode.attributes.contains(AttributeId("pos")))
    assert(!newNode.attributes.contains(AttributeId("height")))
    
    // Verify that other attributes are copied
    assertEquals(newNode.attributes.get(AttributeId("label")).map(_.toString), Some("a"))
    assertEquals(newNode.attributes.get(AttributeId("color")).map(_.toString), Some("red"))
  }

  test("duplicateSelection should not copy layout-specific attributes for groups") {
    // Initial graph with a group that has layout attributes
    val groupId = GroupId("group1")
    val aNode = NodeId("a")
    
    val groupAttrs = Attributes.of(
      "label" -> "My Group",
      "_gvid" -> "456",
      "lp" -> "50,100",  // label position
      "lwidth" -> "1.5",
      "lheight" -> "0.8",
      "style" -> "filled",  // This should be copied
      "fillcolor" -> "lightblue"  // This should be copied
    )
    
    val initialElements = ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, "label" -> "a")
      ),
      groups = Map(
        rootId -> rootGroup,
        groupId -> group(groupId, groupAttrs)
      ),
      memberships = Map(
        aNode -> groupId
      )
    )
    val initialGraph = ViewerGraph(initialElements)
    
    // Select the group for duplication
    val selectedIds = IdsByKind(groups = Set(groupId))
    
    // Duplicate the selection
    val (resultGraph, newElementIds) = initialGraph.duplicateSelection(selectedIds)
    
    // Get the new group
    val newGroupIds = newElementIds.collect { case id: GroupId => id }
    assertEquals(newGroupIds.size, 1)
    val newGroupId = newGroupIds.head
    val newGroup = resultGraph.groups(newGroupId)
    
    // Verify that layout-specific attributes are not copied
    assert(!newGroup.attributes.contains(AttributeId("_gvid")))
    assert(!newGroup.attributes.contains(AttributeId("lp")))
    assert(!newGroup.attributes.contains(AttributeId("lwidth")))
    assert(!newGroup.attributes.contains(AttributeId("lheight")))
    
    // Verify that other attributes are copied (plus the defaults)
    assertEquals(newGroup.attributes.get(AttributeId("label")).map(_.toString), Some("My Group"))
    assertEquals(newGroup.attributes.get(AttributeId("style")).map(_.toString), Some("filled"))
    assertEquals(newGroup.attributes.get(AttributeId("fillcolor")).map(_.toString), Some("lightblue"))
  }

end ViewerGraphSpec
