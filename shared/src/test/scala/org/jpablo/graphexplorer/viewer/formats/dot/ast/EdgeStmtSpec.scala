package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite

class EdgeStmtSpec extends ScalaCheckSuite {
  test("expandArrows should return all arrows") {
    val edgeStmt =
      EdgeStmt(
        List(
          DotNodeId("a", None),
          DotNodeId("b", None),
          DotNodeId("c", None),
          DotNodeId("d", None),
        ),
        List()
      )
    pprint.pprintln(edgeStmt.expandArrows, showFieldNames = false)
    val expected = "a -- b;"
//    assertEquals(edgeStmt.render(0), expected)
  }
}
