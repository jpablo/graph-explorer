package org.jpablo.graphexplorer.viewer.selection

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

class SelectByKindSpec extends FunSuite:

  test("optionsForGraph reports counts for nodes, arrows, and non-root groups") {
    val a = NodeId("a")
    val b = NodeId("b")
    val arrow = Arrow(a, b)

    val rootId   = ViewerGraphElements.defaultRootId
    val groupId  = GroupId("g1")
    val elements = ViewerGraphElements(
      nodes = VectorMap(ViewerNode.nodeWithId(a), ViewerNode.nodeWithId(b)),
      arrows = Map(arrow.id -> arrow),
      groups = Map(
        rootId  -> ViewerGroup.group(rootId),
        groupId -> ViewerGroup.group(groupId)
      )
    )
    val graph = ViewerGraph(elements)

    val labels = SelectByKind.optionsForGraph(graph).map(_.label)

    assertEquals(labels, List("Nodes (2)", "Arrows (1)", "Groups (1)"))
  }

  test("idsForGraph excludes the root group") {
    val rootId  = ViewerGraphElements.defaultRootId
    val groupId = GroupId("g1")
    val graph = ViewerGraph(
      ViewerGraphElements(
        groups = Map(
          rootId  -> ViewerGroup.group(rootId),
          groupId -> ViewerGroup.group(groupId)
        )
      )
    )

    val ids = SelectByKind.idsForGraph(graph, ElementKind.Groups)

    assertEquals(ids, ElementIds(Set(groupId)))
  }

  test("optionsForSelection reports counts by selected kinds") {
    val a     = NodeId("a")
    val b     = NodeId("b")
    val arrow = Arrow(a, b)
    val group = GroupId("g1")

    val selection = ElementIds.from(a, arrow.id, group)
    val labels = SelectByKind.optionsForSelection(selection.classify).map(_.label)

    assertEquals(labels, List("Nodes (1)", "Arrows (1)", "Groups (1)"))
  }
