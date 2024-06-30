package org.jpablo.typeexplorer.viewer.graph

import org.jpablo.typeexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.typeexplorer.viewer.utils.CSVToArray

class ViewerGraphSpec extends munit.FunSuite:
  test("empty graph from empty string"):
    val g = ViewerGraph.from(CSVToArray(""))
    assertEquals(g, ViewerGraph.empty)

  test("single arrow"):
    val g = ViewerGraph.from(CSVToArray("a,b"))
    assertEquals(g.arrows.size, 1)
    assertEquals(g.arrows.head.source.value, "a")
    assertEquals(g.arrows.head.target.value, "b")


  test("two arrows"):
    val csv =
      """
        |a,b
        |c,d""".stripMargin
    val g = ViewerGraph.from(CSVToArray(csv))
    assertEquals(g.arrows.size, 2)
    val arrows: Set[(NodeId, NodeId)] = g.arrows.map(_.toTuple)
    val expected = Set(Arrow("a", "b"), Arrow("c", "d")).map(_.toTuple)
    assertEquals(arrows, expected)


