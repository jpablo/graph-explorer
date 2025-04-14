package org.jpablo.graphexplorer.viewer.utils

case class UserActionRect(
    start: ClientPoint,
    end:   ClientPoint,
    shift: Boolean
):
  def isEmpty: Boolean = start.x == end.x && start.y == end.y

  def update(end: ClientPoint, shift: Boolean) =
    UserActionRect(start, end = end, shift = shift)

// SVG internal coordinate system (user space)
case class SvgPoint(x: Double, y: Double)

object SvgPoint:
  val origin: SvgPoint = SvgPoint(0.0, 0.0)

  extension (a: SvgPoint)
    def -(b: SvgPoint): SvgPoint = SvgPoint(x = a.x - b.x, y = a.y - b.y)
    def *(b: Double): SvgPoint = SvgPoint(a.x * b, a.y * b)

// Client coordinate system (screen space, pixels)
case class ClientPoint(x: Double, y: Double)

case class BBox(x: Double, y: Double, width: Double, height: Double)
