package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand
import org.jpablo.graphexplorer.viewer.formats.svg.SVGPathParser
import org.scalajs.dom

import PathCommand.*

/** Creates a small disk placed near the endpoint of an edge. Diameter: 8px, Border: 1px
  *
  * @param edge
  *   The EdgeElement the disk is associated with.
  * @param start
  *   Whether this disk is for the start or end point (currently unused, assumes start).
  * @return
  *   A reactive SVG group element containing the disk.
  */
def ArrowEndpointButton(
    edge:    EdgeElement,
    start:   Boolean, // TODO: Implement logic for end point (target)
    svgMods: SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  // Define disk properties
  val radius = 4 // Reduced radius for a smaller button
  val w      = radius * 2
  val h      = radius * 2

  // Find the main path element and parse its commands
  val svgPathOpt: Option[dom.svg.Path] =
    Option(edge.ref.querySelector("path")).map(_.asInstanceOf[dom.svg.Path])

  val pathCommands = svgPathOpt
    .flatMap(p => Option(p.getAttribute("d")))
    .flatMap(d => SVGPathParser.parse(d).toOption)
    .getOrElse(Nil)

  // Extract the start and end points from path commands
  val (startPointOpt, endPointOpt) =
    val firstPoint = pathCommands.collectFirst { case MoveTo(_, points) => points.headOption }.flatten
    val lastPoint = pathCommands.lastOption.flatMap:
      case LineTo(_, points)                    => points.lastOption
      case CurveTo(_, points)                   => points.lastOption.map(_._3)
      case SmoothCurveTo(_, points)             => points.lastOption.map(_._2)
      case QuadraticBezierCurveTo(_, points)    => points.lastOption.map(_._2)
      case SmoothQuadraticBezierCurveTo(_, pts) => pts.lastOption
      case EllipticalArc(_, args)               => args.lastOption.map(_._6)
      case _                                    => None
    (firstPoint, lastPoint)

  // Find potential marker elements (children excluding title and path)
  val markerTags = Set("circle", "ellipse", "polygon", "rect") // Common marker element types
  val potentialMarkers =
    edge.ref.querySelectorAll(markerTags.mkString(",")).map(_.asInstanceOf[dom.SVGLocatable]).toSeq

  // Calculate the center of the appropriate marker based on its distance to start/end points
  val selectedMarkerCenterOpt: Option[(Double, Double)] =
    for
      (startX, startY) <- startPointOpt
      (endX, endY)     <- endPointOpt
      markerCenter <- {
        val markerDistances = potentialMarkers.map: elem =>
          val bbox            = elem.getBBox()
          val centerX         = bbox.x + bbox.width / 2.0
          val centerY         = bbox.y + bbox.height / 2.0
          val distanceToStart = math.sqrt(math.pow(centerX - startX, 2) + math.pow(centerY - startY, 2))
          val distanceToEnd   = math.sqrt(math.pow(centerX - endX, 2) + math.pow(centerY - endY, 2))

          // Only consider this marker if it's closer to our target point (start/end) than the opposite point
          val isValidForStart = start && distanceToStart < distanceToEnd
          val isValidForEnd   = !start && distanceToEnd < distanceToStart

          (centerX, centerY, distanceToStart, isValidForStart || isValidForEnd)

        markerDistances
          .filter(_._4) // Only consider markers that are valid for our target point
          .sortBy(_._3) // Sort by distance
          .headOption   // Get the closest one
          .map((cx, cy, _, _) => (cx, cy))
      }
    yield markerCenter

  // Determine the translation coordinates: use marker center if found, else use path start/end point
  val (trX, trY) = selectedMarkerCenterOpt.orElse(if start then startPointOpt else endPointOpt).getOrElse {
    // Fallback to the overall bounding box center if path points are missing
    val bbox = edge.ref.getBBox()
    (bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)
  }

  // Calculate scaling factor based on the edge group's overall transform
  val scale = SvgUtils.calculateSimpleScale(edge.ref, w.toDouble, clientSize = 10)

  svg.g(
    svg.cls           := s"edge-endpoint-disk edge-endpoint-disk-${if (start) "source" else "target"}",
    svg.pointerEvents := "all",                    // Keep interactive
    svg.transform     := s"translate($trX, $trY)", // Apply translation first
    svg.g(
      svg.transform := s"scale($scale)",
      svg.circle(
        // cx/cy are 0 because translation handles positioning
        svg.r           := radius.toString,
        svg.fill        := "white",
        svg.stroke      := "blue",
        svg.strokeWidth := "1" // Use scale-independent stroke width if desired via vector-effect?
        // svg.vectorEffect := "non-scaling-stroke" // Might need this
      )
    ),
    svgMods
  )
