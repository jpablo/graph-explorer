package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
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
    edgePositions:   Map[String, ArrowPosition] = Map.empty,
    svgMods:         SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  val isSource = endpoint == ArrowEndpoint.source
  // Define disk properties
  val radius = 4
  val w      = radius * 2
  val h      = radius * 2

  val currentClientSize = clientSize match
    case ClientSize.Small  => 24.0
    case ClientSize.Normal => 12.0

  // Calculate the scaling factor based on the edge group's overall transform
  val scale = SvgUtils.calculateSimpleScale(edge.ref.asInstanceOf[dom.svg.Locatable], w.toDouble, clientSize = currentClientSize)

  // Get translation coordinates from precise position data
  val (trX, trY) = {
    edgePositions
      .get(edge.elementId.value)
      .map { arrowPos =>
        val point = if isSource then arrowPos.startPoint else arrowPos.endPoint
        (point.x, -point.y) // Flip Y coordinate: Graphviz uses upward Y, SVG uses downward Y
      }
      .getOrElse {
        (0.0, 0.0) // Fallback if no position data available
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
