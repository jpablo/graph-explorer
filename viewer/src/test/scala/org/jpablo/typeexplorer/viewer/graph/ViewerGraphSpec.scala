package org.jpablo.typeexplorer.viewer.graph

import org.jpablo.typeexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.typeexplorer.viewer.formats.CSV

class ViewerGraphSpec extends munit.FunSuite:
  test("empty graph from empty string"):
    val g = ViewerGraph.from(CSV.fromString(""))
    assertEquals(g, ViewerGraph.empty)

  test("single arrow"):
    val g = ViewerGraph.from(CSV.fromString("a,b"))
    val expected = Set(Arrow("a", "b")).map(_.toTuple)
    assertEquals(g.arrows.map(_.toTuple), expected)


  test("two arrows"):
    val csv =
      """
        |a,b
        |c,d""".stripMargin
    val g = ViewerGraph.from(CSV.fromString(csv))
    assertEquals(g.arrows.size, 2)
    val arrows = g.arrows.map(_.toTuple)
    val expected = Set(Arrow("a", "b"), Arrow("c", "d")).map(_.toTuple)
    assertEquals(arrows, expected)


