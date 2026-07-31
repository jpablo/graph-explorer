package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
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

  // User units per CLIENT pixel — the disk keeps a constant SCREEN size, and
  // ScreenConstant.refit re-derives it from this same reading when the zoom
  // moves under it.
  val userPerPx = ScreenConstant.userPerPx(edge.ref).getOrElse(1.0)

  // The anchor is the arrow's endpoint from the LAYOUT — a point on the
  // drawing, so it needs no screen-space offset of its own.
  val (trX, trY) =
    edgePositions
      .get(edge.elementId.value)
      .map { arrowPos =>
        val point = if isSource then arrowPos.startPoint else arrowPos.endPoint
        (point.x, -point.y) // Flip Y coordinate: Graphviz uses upward Y, SVG uses downward Y
      }
      .getOrElse {
        (0.0, 0.0) // Fallback if no position data available
      }

  val anchored =
    ScreenConstant.Anchored(trX, trY, oxPx = 0, oyPx = 0, sizePx = currentClientSize, designBox = w.toDouble)

  svg.g(
    svg.cls           := s"edge-endpoint-disk edge-endpoint-disk-${if (isSource) "source" else "target"}",
    svg.pointerEvents := "all", // Keep interactive
    // One transform, not a translate wrapping a scale: the disk is drawn
    // around the origin, so translate-then-scale places and sizes it in one
    // step — the shape [[ScreenConstant.refit]] knows how to rewrite.
    svg.circle(svg.r := radius.toString),
    ScreenConstant.mods(anchored, userPerPx),
    svgMods
  )
