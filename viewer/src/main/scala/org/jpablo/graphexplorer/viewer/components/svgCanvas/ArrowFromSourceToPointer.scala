package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.models.ArrowDirection
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction

/** Creates a reactive SVG arrow element when dragging to create a new edge.
  *
  * @param action
  *   Signal containing the current selection rectangle state
  * @param rootGroup
  *   The SVG group element that contains the arrow
  * @return
  *   Signal containing an optional SVG group element. The group contains a line from the start node's center to the current mouse position,
  *   and a circle at the end point. Only present during an Edge action.
  */
def ArrowFromSourceToPointer(
    action:    MouseAction.AddNewArrowAction,
    rootGroup: dom.svg.G
): ReactiveSvgElement[dom.svg.G] =
  val point     = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())
  val startBBox = action.originator.ref.getBBox()
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
    val left   = startBBox.x
    val right  = startBBox.x + startBBox.width
    val top    = startBBox.y
    val bottom = startBBox.y + startBBox.height

    // Direction vector from center to target point
    val dx = point.x - centerX
    val dy = point.y - centerY

    // Calculate t values for intersections with each edge
    // We need to find which edge the ray from center to target intersects first
    val tLeft   = if dx != 0 then (left - centerX) / dx else Double.PositiveInfinity
    val tRight  = if dx != 0 then (right - centerX) / dx else Double.PositiveInfinity
    val tTop    = if dy != 0 then (top - centerY) / dy else Double.PositiveInfinity
    val tBottom = if dy != 0 then (bottom - centerY) / dy else Double.PositiveInfinity

    // Filter to only consider intersections in the direction of the ray
    // and find the smallest positive t value (first intersection)
    val candidates: Seq[(t: Double, ix: Double, iy: Double)] = Seq(
      if dx > 0 then (tRight, right, centerY + dy * tRight) else null,
      if dx < 0 then (tLeft, left, centerY + dy * tLeft) else null,
      if dy > 0 then (tBottom, centerX + dx * tBottom, bottom) else null,
      if dy < 0 then (tTop, centerX + dx * tTop, top) else null
    ).filter(_ != null)

    // Find the intersection with the smallest positive t value
    val (_, ix, iy) = candidates.minBy(_.t)

    // Handle numerical precision issues by clamping to the bbox edges
    val clampedX = left max (right min ix)
    val clampedY = top max (bottom min iy)

    (clampedX, clampedY)

  val arrowhead = "arrowhead"
  val arrowtail = "arrowtail"

  val markerAttr = action.direction match
    case ArrowDirection.forward =>
      Seq(
        svg.markerStart := s"url(#$arrowtail)",
        svg.markerEnd   := s"url(#$arrowhead)"
      )
    case ArrowDirection.backward =>
      Seq(
        svg.markerStart := s"url(#$arrowhead)",
        svg.markerEnd   := s"url(#$arrowtail)"
      )

  val scale = SvgUtils.calculateSimpleScale(rootGroup, 1, clientSize = 3)
  svg.g(
    svg.idAttr := "dragging-arrow-group",
    svg.defs(arrowHeadMarker(arrowhead), arrowTailMarker(arrowtail)),
    svg.line(
      svg.idAttr := "dragging-arrow-line",
      svg.x1     := x1.toString,
      svg.y1     := y1.toString,
      svg.x2     := point.x.toString,
      svg.y2     := point.y.toString,
      markerAttr,
      svg.strokeWidth := scale.toString // Thinner line to match a smaller arrowhead
    )
  )
