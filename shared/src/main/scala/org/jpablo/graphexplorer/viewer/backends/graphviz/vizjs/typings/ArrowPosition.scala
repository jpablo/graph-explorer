package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

case class Point(x: Double, y: Double)

case class ArrowPosition(
  startPoint:    Point,
  endPoint:      Point,
  controlPoints: List[Point]
)

object ArrowPositionParser:
  def parse(posString: String): Option[ArrowPosition] =
    val coords = posString.trim.split("\\s+").toList
    if coords.length < 2 then None
    else
      var startPoint: Option[Point] = None
      var endPoint: Option[Point]   = None
      val controlPoints             = scala.collection.mutable.ListBuffer[Point]()

      coords.foreach { coord =>
        if coord.startsWith("s,") then
          startPoint = parseCoordinate(coord.drop(2))
        else if coord.startsWith("e,") then
          endPoint = parseCoordinate(coord.drop(2))
        else
          parseCoordinate(coord).foreach(controlPoints += _)
      }

      // If start is missing, take first control point
      if startPoint.isEmpty && controlPoints.nonEmpty then
        startPoint = Some(controlPoints.head)
        controlPoints.remove(0)

      // If end is missing, take LAST control point (paths end at the last coordinate)
      if endPoint.isEmpty && controlPoints.nonEmpty then
        endPoint = Some(controlPoints.last)
        controlPoints.remove(controlPoints.length - 1)

      // Both start and end are required
      (startPoint, endPoint) match
        case (Some(start), Some(end)) => Some(ArrowPosition(start, end, controlPoints.toList))
        case _                        => None

  private def parseCoordinate(coord: String): Option[Point] =
    coord.split(",") match
      case Array(x, y) =>
        try Some(Point(x.toDouble, y.toDouble))
        catch case _: NumberFormatException => None
      case _ => None
