package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.CSV
import org.jpablo.graphexplorer.viewer.models.Arrow

class ViewerGraphSpec extends munit.FunSuite:
  test("empty graph from empty string"):
    val g = CSV("").toViewerGraph
    assertEquals(g, ViewerGraph.empty)

  test("single arrow"):
    val g = CSV("a,b").toViewerGraph
    val expected = Set(Arrow("a" -> "b", Map.empty))
    assertEquals(g.arrows, expected)


  test("two arrows"):
    val csv =
      """
        |a,b
        |c,d""".stripMargin
    val g = CSV(csv).toViewerGraph
    assertEquals(g.arrows.size, 2)
    val expected = Set(Arrow("a" -> "b", Map.empty), Arrow("c" -> "d", Map.empty))
    assertEquals(g.arrows, expected)


