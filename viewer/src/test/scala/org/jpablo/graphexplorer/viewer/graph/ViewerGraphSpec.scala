package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.graphexplorer.viewer.formats.CSV

class ViewerGraphSpec extends munit.FunSuite:
  test("empty graph from empty string"):
    val g = CSV("").toViewerGraph
    assertEquals(g, ViewerGraph.empty)

  test("single arrow"):
    val g = CSV("a,b").toViewerGraph
    val expected = Set(Arrow("a", "b")).map(_.toTuple)
    assertEquals(g.arrows.map(_.toTuple), expected)


  test("two arrows"):
    val csv =
      """
        |a,b
        |c,d""".stripMargin
    val g = CSV(csv).toViewerGraph
    assertEquals(g.arrows.size, 2)
    val arrows = g.arrows.map(_.toTuple)
    val expected = Set(Arrow("a", "b"), Arrow("c", "d")).map(_.toTuple)
    assertEquals(arrows, expected)


