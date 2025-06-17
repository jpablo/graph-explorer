package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.*
import org.scalajs.dom

/**
 * Utility object for extracting coordinate points from various SVG elements.
 * Supports path, polygon, and polyline elements.
 */
object SvgPointExtractor {

  type Point = (Double, Double)
  
  /**
   * Extracts all coordinate points from a path element by parsing its 'd' attribute.
   */
  def extractPathPoints(pathElement: dom.svg.Path): Seq[Point] = {
    Option(pathElement.getAttribute("d"))
      .flatMap(d => SVGPathParser.parse(d).toOption)
      .map(extractPointsFromPathCommands)
      .getOrElse(Seq.empty)
  }
  
  /**
   * Extracts coordinate points from parsed PathCommand sequence.
   */
  def extractPointsFromPathCommands(commands: Seq[PathCommand]): Seq[Point] = {
    commands.flatMap {
      case MoveTo(_, points) => points.map(coord => (coord.x, coord.y))
      case LineTo(_, points) => points.map(coord => (coord.x, coord.y))
      case HorizontalLineTo(_, xs) => xs.map((_, 0.0)) // y-coordinate is implicit
      case VerticalLineTo(_, ys) => ys.map((0.0, _))   // x-coordinate is implicit
      case CurveTo(_, points) => points.flatMap { case (cp1, cp2, end) => 
        Seq((cp1.x, cp1.y), (cp2.x, cp2.y), (end.x, end.y)) 
      }
      case SmoothCurveTo(_, points) => points.flatMap { case (cp, end) => 
        Seq((cp.x, cp.y), (end.x, end.y)) 
      }
      case QuadraticBezierCurveTo(_, points) => points.flatMap { case (cp, end) => 
        Seq((cp.x, cp.y), (end.x, end.y)) 
      }
      case SmoothQuadraticBezierCurveTo(_, points) => points.map(coord => (coord.x, coord.y))
      case EllipticalArc(_, args) => args.map(_._6).map(coord => (coord.x, coord.y)) // Extract end points from arc arguments
      case PathCommand.ClosePath() => Seq.empty
    }
  }
  
  /**
   * Extracts coordinate points from a polygon element by parsing its 'points' attribute.
   */
  def extractPolygonPoints(polygonElement: dom.svg.Polygon): Seq[Point] = {
    Option(polygonElement.getAttribute("points"))
      .map(parsePointsAttribute)
      .getOrElse(Seq.empty)
  }
  
  /**
   * Extracts coordinate points from a polyline element by parsing its 'points' attribute.
   */
  def extractPolylinePoints(polylineElement: dom.svg.Polyline): Seq[Point] = {
    Option(polylineElement.getAttribute("points"))
      .map(parsePointsAttribute)
      .getOrElse(Seq.empty)
  }
  
  /**
   * Parses the 'points' attribute format used by polygon and polyline elements.
   * Format: "x1,y1 x2,y2 x3,y3" or "x1 y1 x2 y2 x3 y3" (space or comma separated)
   */
  private def parsePointsAttribute(pointsStr: String): Seq[Point] = {
    if (pointsStr.trim.isEmpty) return Seq.empty
    
    try {
      // Split by whitespace and/or commas, filter out empty strings
      val tokens = pointsStr.trim.split("[\\s,]+").filter(_.nonEmpty)
      
      // Group tokens into pairs (x, y)
      tokens.grouped(2).collect {
        case Array(x, y) => (x.toDouble, y.toDouble)
      }.toSeq
    } catch {
      case _: NumberFormatException => Seq.empty
    }
  }
  
  /**
   * Extracts coordinate points from any supported SVG element.
   */
  def extractPoints(element: dom.svg.Element): Seq[Point] = {
    element.tagName.toLowerCase match {
      case "path" => extractPathPoints(element.asInstanceOf[dom.svg.Path])
      case "polygon" => extractPolygonPoints(element.asInstanceOf[dom.svg.Polygon])
      case "polyline" => extractPolylinePoints(element.asInstanceOf[dom.svg.Polyline])
      case _ => Seq.empty
    }
  }
}