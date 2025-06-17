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
import org.jpablo.graphexplorer.viewer.utils.{DistanceUtils, SvgPointExtractor}

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

  // Query all SVG elements that can contain coordinate points
  val svgElements = edge.ref.querySelectorAllT[dom.svg.Element]("path, polygon, polyline")
  
  // Extract all coordinate points from these elements
  val allPoints = svgElements.flatMap(SvgPointExtractor.extractPoints)
  
  val edgeBBox = edge.ref.getBBox()

  val currentClientSize = clientSize match
    case ClientSize.Small  => 24.0
    case ClientSize.Normal => 12.0

  // Calculate the scaling factor based on the edge group's overall transform
  val scale = SvgUtils.calculateSimpleScale(edge.ref, w.toDouble, clientSize = currentClientSize)

  // Extract the start and end points from path commands for fallback
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

  // Determine the translation coordinates based on endpointElement or fallback logic
  val (trX, trY) = endpointElement match {
    case Some(elem) =>
      // Use point-based positioning: find the closest point to the endpoint node's bounding box center
      val endpointNodeCenter = DistanceUtils.boundingBoxCenter(elem.ref.getBBox())
      val threshold = 50.0 // Maximum distance to consider a point valid (configurable)
      
      DistanceUtils.findClosestPointWithinThreshold(endpointNodeCenter, allPoints, threshold) match {
        case Some(closestPoint) => closestPoint
        case None =>
          // Fallback to path start/end points if no points are within threshold
          (if isSource then startPointOpt else endPointOpt).getOrElse {
            // Final fallback to edge bounding box center
            (edgeBBox.x + edgeBBox.width / 2, edgeBBox.y + edgeBBox.height / 2)
          }
      }
    case None =>
      // Legacy fallback when no endpointElement is provided
      (if isSource then startPointOpt else endPointOpt).getOrElse {
        // Final fallback to edge bounding box center
        (edgeBBox.x + edgeBBox.width / 2, edgeBBox.y + edgeBBox.height / 2)
      }
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
