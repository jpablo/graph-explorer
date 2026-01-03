package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Shape, Style}

import scala.collection.immutable.VectorMap

class FromViewerGraphSpec extends FunSuite:

  test("viewerGraphToMermaidText should serialize a simple node"):
    val nodeId = NodeId("A")
    val node = ViewerNode.nodeWithDefaults(nodeId, Attributes.of(Label -> "Hello"))
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(nodeId -> node)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("flowchart TD"), s"Should start with flowchart TD, got: $result")
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
