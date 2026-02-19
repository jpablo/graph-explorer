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

    assert(result.contains("flowchart TB"), s"Should start with flowchart TB, got: $result")
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

  test("viewerGraphToMermaidText should emit standalone nodes without redundant labels"):
    val nodeA = NodeId("a")
    val nodeB = NodeId("b")
    val nodeC = NodeId("c")
    val nodes = VectorMap(
      nodeA -> ViewerNode.nodeWithDefaults(nodeA),
      nodeB -> ViewerNode.nodeWithDefaults(nodeB),
      nodeC -> ViewerNode.nodeWithDefaults(nodeC)
    )
    val arrow = Arrow(nodeA, nodeB)
    val graph = ViewerGraph(
      elements = ViewerGraphElements(
        nodes = nodes,
        arrows = Map(arrow.id -> arrow)
      )
    )

    val result = viewerGraphToMermaidText(graph)

    assert(result.contains("a --> b"), s"Should contain edge a --> b, got: $result")
    assert(result.contains("\n  c\n"), s"Should contain standalone node c without brackets, got: $result")
    assert(!result.contains("a[a]"), s"Should omit redundant node label for a, got: $result")
    assert(!result.contains("b[b]"), s"Should omit redundant node label for b, got: $result")
    assert(!result.contains("c[c]"), s"Should omit redundant node label for c, got: $result")

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
