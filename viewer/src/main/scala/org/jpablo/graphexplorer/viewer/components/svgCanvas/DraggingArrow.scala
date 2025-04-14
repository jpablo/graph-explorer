package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.state.MouseAction

/** Creates a reactive SVG arrow element when dragging to create a new edge.
  *
  * @param rect
  *   Signal containing the current selection rectangle state
  * @param rootGroup
  *   The SVG group element that contains the arrow
  * @return
  *   Signal containing an optional SVG group element. The group contains a line from the start node's center to the
  *   current mouse position, and a circle at the end point. Only present during an Edge action.
  */
def DraggingArrow(
    rect:      Signal[Option[MouseAction.AddNewArrowAction]],
    rootGroup: dom.svg.G
): Signal[Option[ReactiveSvgElement[dom.svg.G]]] =
  rect.map:
    _.flatMap: action =>
      if action.rect.isEmpty then
        None
      else
        val point = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())
        val startBBox = action.start.get.getBBox()
        // Calculate center point
        val centerX = startBBox.x + startBBox.width / 2
        val centerY = startBBox.y + startBBox.height / 2

        // Check if target point is inside bounding box
        val isInside = point.x >= startBBox.x && point.x <= startBBox.x + startBBox.width &&
          point.y >= startBBox.y && point.y <= startBBox.y + startBBox.height

        // Calculate start point - use center if inside bbox, intersection if outside
        val (x1, y1) = if isInside then
          (centerX, centerY)
        else
          // Define the four edges of the bounding box
          val left = startBBox.x
          val right = startBBox.x + startBBox.width
          val top = startBBox.y
          val bottom = startBBox.y + startBBox.height

          // Direction vector from center to target point
          val dx = point.x - centerX
          val dy = point.y - centerY

          // Calculate t values for intersections with each edge
          // We need to find which edge the ray from center to target intersects first
          val tLeft = if dx != 0 then (left - centerX) / dx else Double.PositiveInfinity
          val tRight = if dx != 0 then (right - centerX) / dx else Double.PositiveInfinity
          val tTop = if dy != 0 then (top - centerY) / dy else Double.PositiveInfinity
          val tBottom = if dy != 0 then (bottom - centerY) / dy else Double.PositiveInfinity

          // Filter to only consider intersections in the direction of the ray
          // and find the smallest positive t value (first intersection)
          val candidates = Seq(
            if dx > 0 then (tRight, right, centerY + dy * tRight) else null,
            if dx < 0 then (tLeft, left, centerY + dy * tLeft) else null,
            if dy > 0 then (tBottom, centerX + dx * tBottom, bottom) else null,
            if dy < 0 then (tTop, centerX + dx * tTop, top) else null
          ).filter(_ != null)

          // Find the intersection with the smallest positive t value
          val (_, ix, iy) = candidates.minBy(_._1)

          // Handle numerical precision issues by clamping to the bbox edges
          val clampedX = math.max(left, math.min(right, ix))
          val clampedY = math.max(top, math.min(bottom, iy))

          (clampedX, clampedY)

        val scale = SvgUtils.calculateSimpleScale(rootGroup, 1, clientSize = 2)
        Some(
          svg.g(
            svg.idAttr := "dragging-arrow-group",
            // Define the arrowhead marker
            svg.defs(
              svg.marker(
                svg.idAttr       := "arrowhead",
                svg.viewBox      := "0 0 10 10",
                svg.refX         := "9",
                svg.refY         := "5",
                svg.markerWidth  := "4", // Smaller size
                svg.markerHeight := "4", // Smaller size
                svg.orient       := "auto-start-reverse",
                svg.path(
                  // Vee style path with concave base
                  svg.d      := "M 0 0 L 10 5 L 0 10 L 2 5 z",
                  svg.fill   := "#2c70ff", // Selected border blue
                  svg.stroke := "#2c70ff"  // Match the fill color
                )
              )
            ),
            // Draw the line with the arrowhead
            svg.line(
              svg.idAttr      := "dragging-arrow-line",
              svg.x1          := x1.toString,
              svg.y1          := y1.toString,
              svg.x2          := point.x.toString,
              svg.y2          := point.y.toString,
              svg.markerEnd   := "url(#arrowhead)",
              svg.stroke      := "#2c70ff", // Selected border blue
              svg.strokeWidth := scale.toString        // Thinner line to match smaller arrowhead
            )
          )
        )
