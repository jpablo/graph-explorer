package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.{DOMPoint, SvgUtils}
import org.jpablo.graphexplorer.viewer.models.ArrowDirection
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction
import scala.scalajs.js

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
  val point        = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())
  val localBBox    = action.originator.ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
  val elemCtm      = Option(action.originator.ref.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
  val rootCtm      = Option(rootGroup.getScreenCTM())
  val (bboxX, bboxY, bboxWidth, bboxHeight) =
    (rootCtm, elemCtm) match
      case (Some(rootMatrix), Some(elemMatrix)) =>
        val inv = rootMatrix.inverse()
        def toRoot(x: Double, y: Double): DOMPoint =
          new DOMPoint(x, y).matrixTransform(elemMatrix).matrixTransform(inv)
        val p1   = toRoot(localBBox.x, localBBox.y)
        val p2   = toRoot(localBBox.x + localBBox.width, localBBox.y)
        val p3   = toRoot(localBBox.x, localBBox.y + localBBox.height)
        val p4   = toRoot(localBBox.x + localBBox.width, localBBox.y + localBBox.height)
        val minX = List(p1.x, p2.x, p3.x, p4.x).min
        val minY = List(p1.y, p2.y, p3.y, p4.y).min
        val maxX = List(p1.x, p2.x, p3.x, p4.x).max
        val maxY = List(p1.y, p2.y, p3.y, p4.y).max
        (minX, minY, maxX - minX, maxY - minY)
      case _ =>
        (localBBox.x, localBBox.y, localBBox.width, localBBox.height)

  // Calculate center point
  val centerX = bboxX + bboxWidth / 2
  val centerY = bboxY + bboxHeight / 2

  // Check if target point is inside bounding box

  val isInside = point.x >= bboxX && point.x <= bboxX + bboxWidth &&
    point.y >= bboxY && point.y <= bboxY + bboxHeight

  // Calculate start point - use center if inside bbox, intersection if outside
  val (x1, y1) = if isInside then
    (centerX, centerY)
  else
    // Define the four edges of the bounding box
    val left   = bboxX
    val right  = bboxX + bboxWidth
    val top    = bboxY
    val bottom = bboxY + bboxHeight

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
    val clampedX = left.max(right.min(ix))
    val clampedY = top.max(bottom.min(iy))

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
