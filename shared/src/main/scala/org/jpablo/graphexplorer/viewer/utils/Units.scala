package org.jpablo.graphexplorer.viewer.utils

case class MouseActionRect(
    start: ClientPoint,
    end:   ClientPoint,
    shift: Boolean
):
  def isEmpty: Boolean = start.x == end.x && start.y == end.y

  def update(end: ClientPoint, shift: Boolean) =
    MouseActionRect(start, end = end, shift = shift)

// SVG internal coordinate system (user space)
case class SvgPoint(x: Double, y: Double):
  def toTuple = (x, y)

object SvgPoint:
  val origin: SvgPoint = SvgPoint(0.0, 0.0)

  extension (a: SvgPoint)
    def -(b: SvgPoint): SvgPoint = SvgPoint(x = a.x - b.x, y = a.y - b.y)
    def *(b: Double): SvgPoint = SvgPoint(a.x * b, a.y * b)

// Client coordinate system (screen space, pixels)
case class ClientPoint(x: Double, y: Double):
  def toTuple = (x, y)

case class BBox(x: Double, y: Double, width: Double, height: Double)
