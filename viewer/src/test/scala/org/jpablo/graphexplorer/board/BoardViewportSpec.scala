package org.jpablo.graphexplorer.board

import munit.FunSuite

/** The pan and the zoom of the board canvas. */
class BoardViewportSpec extends FunSuite:

  private def close(a: Double, b: Double): Boolean = math.abs(a - b) < 1e-9

  test("a pan moves the view by the pixels of the pointer"):
    assertEquals(BoardViewport(10, 20, 2).panBy(5, -5), BoardViewport(15, 15, 2))

  test("a zoom keeps the board point at the pointer"):
    val before = BoardViewport(40, 40, 1)
    val (x, y) = before.toBoard(300, 200)
    val after  = before.zoomAt(300, 200, 1.5)
    assertEquals(after.scale, 1.5)
    val (x2, y2) = after.toBoard(300, 200)
    assert(close(x, x2) && close(y, y2), s"($x, $y) is now ($x2, $y2)")

  test("the scale stays in its limits"):
    assertEquals(BoardViewport(0, 0, 1).zoomAt(0, 0, 1000).scale, BoardViewport.MaxScale)
    assertEquals(BoardViewport(0, 0, 1).zoomAt(0, 0, 0.0001).scale, BoardViewport.MinScale)

  test("a fit of a large board shows all of it, in the center of the canvas"):
    val bounds = BoardBounds(100, 50, 2000, 1000)
    val view   = BoardViewport.fit(bounds, 1080, 580)
    // The usable area is 1000 x 500, so the scale is 0.5.
    assertEquals(view.scale, 0.5)
    val (left, top)     = (bounds.x * view.scale + view.tx, bounds.y * view.scale + view.ty)
    val (right, bottom) = (bounds.right * view.scale + view.tx, bounds.bottom * view.scale + view.ty)
    assert(close(left, 1080 - right), s"left $left, right ${1080 - right}")
    assert(close(top, 580 - bottom), s"top $top, bottom ${580 - bottom}")

  test("a fit of a small board keeps the scale 1"):
    val view = BoardViewport.fit(BoardBounds(0, 0, 200, 100), 1000, 600)
    assertEquals(view, BoardViewport(400, 250, 1))

  test("the union of two rectangles contains the two"):
    assertEquals(BoardBounds(0, 0, 100, 50).union(BoardBounds(300, -20, 50, 40)), BoardBounds(0, -20, 350, 70))
