package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand
import org.jpablo.graphexplorer.viewer.formats.svg.SVGPathParser
import org.jpablo.graphexplorer.viewer.domUtils.{querySelectorAllT, querySelectorT}
import PathCommand.*
import org.jpablo.graphexplorer.viewer.models.ClientSize
import org.jpablo.graphexplorer.viewer.state.mouseActions.ArrowEndpoint

/** Creates a small disk placed near the endpoint of an edge. Diameter: 8px, Border: 1px
  *
  * @param edge
  *   The EdgeElement the disk is associated with.
  * @param endpoint
  *   Whether this disk is for the source or target endpoint.
  * @param clientSize
  *   The client size for scaling calculations.
  * @param endpointElement
  *   Optional SelectableElement for the source/target node to use for positioning.
  * @return
  *   A reactive SVG group element containing the disk.
  */
def ArrowEndpointControl(
    edge:            EdgeElement,
    endpoint:        ArrowEndpoint,
    clientSize:      ClientSize,
    endpointElement: Option[SelectableElement] = None,
    svgMods:         SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  val isSource = endpoint == ArrowEndpoint.source
  // Define disk properties
  val radius = 4
  val w      = radius * 2
  val h      = radius * 2
  // Find the main path element and parse its commands
  val svgPathOpt =
    edge.ref.querySelectorT("path")

  val pathCommands = svgPathOpt
    .flatMap(p => Option(p.getAttribute("d")))
    .flatMap(d => SVGPathParser.parse(d).toOption)
    .getOrElse(Nil)

  // Find potential marker elements (children excluding title and path)
  val markerTags = Set("circle", "ellipse", "polygon", "rect") // Common marker element types
  val potentialMarkers =
    edge.ref.querySelectorAllT[dom.SVGLocatable](markerTags.mkString(","))

  val edgeBBox = edge.ref.getBBox()

  val currentClientSize = clientSize match
    case ClientSize.Small  => 24.0
    case ClientSize.Normal => 12.0

  // Calculate the scaling factor based on the edge group's overall transform
  val scale = SvgUtils.calculateSimpleScale(edge.ref, w.toDouble, clientSize = currentClientSize)

  // ------------

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
          // and if the distance is below a reasonable threshold to ensure it's actually at the endpoint
          val threshold       = 20.0 // Maximum distance to consider a marker valid
          val isValidForStart = isSource && distanceToStart < distanceToEnd && distanceToStart < threshold
          val isValidForEnd   = !isSource && distanceToEnd < distanceToStart && distanceToEnd < threshold

          // Use the appropriate distance based on whether we're looking for start or end point
          val relevantDistance = if (isSource) distanceToStart else distanceToEnd

          (cx = centerX, cy = centerY, distance = relevantDistance, isValid = isValidForStart || isValidForEnd)

        markerDistances
          .filter(_.isValid)  // Only consider markers that are valid for our target point
          .sortBy(_.distance) // Sort by distance
          .headOption         // Get the closest one
          .map(md => (md.cx, md.cy))
      }
    yield markerCenter

  // Determine the translation coordinates: use marker center if found, else use path start/end point
  val (trX, trY) = selectedMarkerCenterOpt.orElse(if isSource then startPointOpt else endPointOpt).getOrElse {
        // Fallback to the overall bounding box center if path points are missing
        (edgeBBox.x + edgeBBox.width / 2, edgeBBox.y + edgeBBox.height / 2)
  }

  svg.g(
    svg.cls           := s"edge-endpoint-disk edge-endpoint-disk-${if (isSource) "source" else "target"}",
    svg.pointerEvents := "all",                    // Keep interactive
    svg.transform     := s"translate($trX, $trY)", // Apply translation first
    svg.g(
      svg.transform := s"scale($scale)",
      svg.circle(svg.r := radius.toString)
    ),
    svgMods
  )
