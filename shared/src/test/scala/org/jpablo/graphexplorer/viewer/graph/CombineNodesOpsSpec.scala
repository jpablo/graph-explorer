package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Shape}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId
import scala.collection.immutable.VectorMap

class CombineNodesOpsSpec extends FunSuite:

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")
  val x = NodeId("x")
  val y = NodeId("y")
  val z = NodeId("z")

  test("canCombineNodes returns false for single node"):
    val graph = ViewerGraph(
      ViewerGraphElements(nodes = VectorMap(nodeWithId(a)))
    )
    assert(!graph.canCombineNodes(Set(a)), "Single node should not be combinable")

  test("canCombineNodes returns true for nodes in same group"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
        memberships = VectorMap(a -> GroupId("g1"), b -> GroupId("g1"))
      )
    )
    assert(graph.canCombineNodes(Set(a, b)), "Nodes in same group should be combinable")

  test("canCombineNodes returns false for nodes in different groups"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
        memberships = VectorMap(a -> GroupId("g1"), b -> GroupId("g2"))
      )
    )
    assert(!graph.canCombineNodes(Set(a, b)), "Nodes in different groups should not be combinable")

  test("canCombineNodes returns true for nodes with no group"):
    val graph = ViewerGraph(
      ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b)))
    )
    assert(graph.canCombineNodes(Set(a, b)), "Nodes with no group should be combinable")

  test("combineIntoRecord creates record node with correct label"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a, Attributes.of(Label -> "Node A")),
          b -> ViewerNode.nodeWithDefaults(b, Attributes.of(Label -> "Node B")),
          c -> ViewerNode.nodeWithDefaults(c, Attributes.of(Label -> "Node C"))
        )
      ),
      nodeCounter = 3
    )

    val result = graph.combineIntoRecord(Set(a, b))

    // Should have removed a and b, added new record node
    assertEquals(result.nodeIds.size, 2)
    assert(!result.nodeIds.contains(a), "Node a should be removed")
    assert(!result.nodeIds.contains(b), "Node b should be removed")
    assert(result.nodeIds.contains(c), "Node c should remain")

    // Check the new record node
    val newNode = result.nodes.values.find(n =>
      n.attributes.values.get(Shape.attrId).exists(_.toString == "record")
    )
    assert(newNode.isDefined, "Should have created a record node")

    val recordNode = newNode.get
    val label = recordNode.label.toString
    assert(label.contains("<f0> Node A"), "Record should contain field f0 with Node A")
    assert(label.contains("<f1> Node B"), "Record should contain field f1 with Node B")

  test("combineIntoRecord preserves edges with port mapping"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a, Attributes.of(Label -> "A")),
          b -> ViewerNode.nodeWithDefaults(b, Attributes.of(Label -> "B")),
          x -> ViewerNode.nodeWithDefaults(x),
          y -> ViewerNode.nodeWithDefaults(y)
        ),
        arrows = VectorMap(
          Arrow.arrow(x, a),
          Arrow.arrow(a, y),
          Arrow.arrow(x, b),
          Arrow.arrow(a, b)
        )
      ),
      nodeCounter = 4
    )

    val result = graph.combineIntoRecord(Set(a, b))

    // Check arrows are preserved with ports
    val arrows = result.arrows.values.toSeq

    // Should have 4 arrows still
    assertEquals(arrows.size, 4)

    // Find the new record node
    val recordNodeId = (result.nodeIds -- Set(x, y)).head

    // Check that arrows to/from a and b now use the record node with ports
    val arrowsToRecord = arrows.filter(_.target == recordNodeId)
    val arrowsFromRecord = arrows.filter(_.source == recordNodeId)

    // x -> a and x -> b should now be x -> record:f0 and x -> record:f1
    assert(arrowsToRecord.exists(a => a.source == x && a.targetPort.contains("f0")), "Should have x -> record:f0")
    assert(arrowsToRecord.exists(a => a.source == x && a.targetPort.contains("f1")), "Should have x -> record:f1")

    // a -> y should now be record:f0 -> y
    assert(arrowsFromRecord.exists(a => a.target == y && a.sourcePort.contains("f0")), "Should have record:f0 -> y")

    // a -> b should now be record:f0 -> record:f1
    assert(arrows.exists(a =>
      a.source == recordNodeId &&
      a.target == recordNodeId &&
      a.sourcePort.contains("f0") &&
      a.targetPort.contains("f1")
    ), "Should have self-edge record:f0 -> record:f1")

  test("isRecordNode detects record nodes"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a, Attributes.of(Shape -> Shape.record, Label -> "<f0> Test")),
          b -> ViewerNode.nodeWithDefaults(b, Attributes.of(Label -> "Normal node"))
        )
      )
    )

    assert(graph.isRecordNode(a), "Node with record shape should be detected")
    assert(!graph.isRecordNode(b), "Node without record shape should not be detected")

  test("splitRecordNode splits record back to individual nodes"):
    // First create a record node by combining
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a, Attributes.of(Label -> "Node A")),
          b -> ViewerNode.nodeWithDefaults(b, Attributes.of(Label -> "Node B")),
          c -> ViewerNode.nodeWithDefaults(c, Attributes.of(Label -> "Node C"))
        )
      ),
      nodeCounter = 3
    )

    val combined = graph.combineIntoRecord(Set(a, b))
    val recordNodeId = (combined.nodeIds -- Set(c)).head

    // Now split it back
    val split = combined.splitRecordNode(recordNodeId)

    // Should have 3 nodes again (2 new ones + node c)
    assertEquals(split.nodeIds.size, 3)
    assert(!split.nodeIds.contains(recordNodeId), "Record node should be removed")
    assert(split.nodeIds.contains(c), "Node c should remain")

    // Check that the new nodes have the correct labels
    val newNodes = split.nodes.values.filter(n => !Set(c).contains(n.id))
    val labels = newNodes.map(_.label.toString).toSet
    assert(labels.contains("Node A"), "Should have node with label 'Node A'")
    assert(labels.contains("Node B"), "Should have node with label 'Node B'")

  test("splitRecordNode preserves edges correctly"):
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a, Attributes.of(Label -> "A")),
          b -> ViewerNode.nodeWithDefaults(b, Attributes.of(Label -> "B")),
          x -> ViewerNode.nodeWithDefaults(x),
          y -> ViewerNode.nodeWithDefaults(y)
        ),
        arrows = VectorMap(
          Arrow.arrow(x, a),
          Arrow.arrow(a, y),
          Arrow.arrow(x, b),
          Arrow.arrow(a, b)
        )
      ),
      nodeCounter = 4
    )

    // Combine a and b
    val combined = graph.combineIntoRecord(Set(a, b))
    val recordNodeId = (combined.nodeIds -- Set(x, y)).head

    // Split back
    val split = combined.splitRecordNode(recordNodeId)

    // Should have 4 arrows still
    assertEquals(split.arrows.size, 4)

    // All arrows should not have ports anymore
    split.arrows.values.foreach { arrow =>
      assert(arrow.sourcePort.isEmpty, s"Arrow ${arrow.id} should not have source port")
      assert(arrow.targetPort.isEmpty, s"Arrow ${arrow.id} should not have target port")
    }

    // Check connectivity is preserved (though node IDs will be different)
    val newNodeIds = split.nodeIds -- Set(x, y)
    assert(newNodeIds.size == 2, "Should have 2 new nodes")

    // Should have arrows from x to both new nodes
    val arrowsFromX = split.arrows.values.filter(_.source == x)
    assertEquals(arrowsFromX.size, 2, "Should have 2 arrows from x")

    // Should have an arrow between the new nodes
    val arrowsBetweenNew = split.arrows.values.filter(a =>
      newNodeIds.contains(a.source) && newNodeIds.contains(a.target)
    )
    assert(arrowsBetweenNew.nonEmpty, "Should have arrow between the split nodes")

  test("combineIntoRecord preserves group membership"):
    val groupId = GroupId("g1")
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(
          a -> ViewerNode.nodeWithDefaults(a),
          b -> ViewerNode.nodeWithDefaults(b)
        ),
        memberships = VectorMap(a -> groupId, b -> groupId)
      ),
      nodeCounter = 2
    )

    val result = graph.combineIntoRecord(Set(a, b))

    // The new record node should be in the same group
    val recordNodeId = (result.nodeIds -- Set.empty[NodeId]).head
    assertEquals(result.memberships.get(recordNodeId), Some(groupId), "Record node should be in same group")