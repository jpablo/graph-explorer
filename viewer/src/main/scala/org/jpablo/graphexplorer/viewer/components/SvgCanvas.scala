package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Rankdir.*

import scala.scalajs.js
import scala.util.Try

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  def clientCoords(e: dom.MouseEvent): (Point2d[Double], Boolean) = ((e.clientX, e.clientY), e.shiftKey)

  // rawSvg is the SVG element as it comes from DOT
  def apply(
      rawSvg:           dom.SVGSVGElement,
      transform:        Signal[String],
      diagramSelection: DiagramSelectionOps,
      addNode:          () => Unit,
      graphTargetAttributes: Var[Attributes]
  ): ReactiveSvgElement[dom.SVGSVGElement] =

    val viewBox = rawSvg.viewBox.baseVal
    val firstGroup: dom.svg.G =
      val g0 = rawSvg.querySelector("g")
      (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

    val (gX, gY) = getTranslate(firstGroup)

    // --------------------------------------------------------
    // The top level <g> element
    // --------------------------------------------------------
    val topLevelGroup =
      foreignSvgElement(svg.g, firstGroup)
        .amendThis: group =>
          val selectableElements = SelectableElement.findAll(group.ref)
          Seq(
            svg.transform <-- transform,
            // --------------------------------------------------------
            //   "New edge" button
            // --------------------------------------------------------
            child.maybe <--
              diagramSelection.signal.map: selectedNodes =>
                if selectedNodes.size == 1 then
                  val nodeId = selectedNodes.head
                  for
                    elem <- selectableElements.find(_.nodeId == nodeId)
                    btn  <- NewEdgeButtonElement(elem, graphTargetAttributes)
                  yield
                  // --------------------------------------------------------
                  // Mouse interaction
                  // --------------------------------------------------------
                  btn.amend(
                    onMouseDown.stopPropagation --> { ev =>
                      diagramSelection.startSelectionLine((ev.clientX, ev.clientY), shift = false, elem)
                    },
                    onMouseUp.stopPropagation --> { _ =>
                      diagramSelection.endSelectionLine()
                      addNode()
                    }
                  )
                else
                  None
            ,
            // --------------------------------------------------------
            //   draw dragging arrow
            // --------------------------------------------------------
            child.maybe <-- DraggingArrow(diagramSelection.selectionRectLine.signal, group.ref)
          )

    // --------------------------------------------------------
    // The top level <svg> element
    // --------------------------------------------------------
    val bbox = BBox(viewBox.x - gX.value, viewBox.y - gY.value, viewBox.width, viewBox.height)
    selfContainedSvg(bbox)
      .amend(topLevelGroup)
      .amendThis { topLevelSvg =>
        val selectableElements = SelectableElement.findAll(topLevelSvg.ref)
        Seq(
          // --------------------------------------------------------
          //   draw selection rect
          // --------------------------------------------------------
          child.maybe <-- DrawSelectionRect(diagramSelection.selectionRectArea.signal, topLevelSvg.ref),
          // --------------------------------------------------------
          //   select elements intersecting selectionRec
          // --------------------------------------------------------
          // TODO: do we need to listen to state.selectionRectLine.signal here?
          diagramSelection.selectionRectArea.signal --> { actionO =>
            for action <- actionO do
              val rect = action.rect
              diagramSelection.handleSelectionAreaUpdate(
                rect,
                selectableElements,
                dom.document.elementsFromPoint(rect.endX, rect.endY)
              )
          },
          diagramSelection.selectionRectLine.signal --> { (actionO: Option[Action.Line]) =>
            for action <- actionO do
              val rect = action.rect
              diagramSelection.handleSelectionLineUpdate(
                rect,
                action.start,
                dom.document.elementsFromPoint(rect.endX, rect.endY)
              )
          },
          // --------------------------------------------------------
          //   synchronize svg elements with diagramSelection
          // --------------------------------------------------------
          diagramSelection.signal --> { selectedNodes =>
            for elem <- selectableElements do
              if elem.nodeId in selectedNodes then
                elem.select()
              else
                elem.unselect()
          }
        )
      }

  end apply

  private def NewEdgeButtonElement(elem: SelectableElement, graphTargetAttributes: Var[Attributes]): Option[ReactiveSvgElement[dom.svg.G]] =
    val arrowGroup = svg.g(
      svg.path(
        svg.d := "M8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293z"
      )
    )

    val g0 =
      svg.g(
        svg.cls           := s"new-edge-button",
        svg.pointerEvents := "all",
        svg.circle(svg.r := "8", svg.cx := "8", svg.cy := "8"),
        arrowGroup
      )

    elem match
      case NodeElement(ref) =>
        val bbox = ref.getBBox()
        val scale = 0.4
        // https://icons.getbootstrap.com/icons/arrow-down-circle/
        val w = 16 // Original width of the icon
        val h = 16 // Original height of the icon

        // Get the rankdir value from graph attributes
        val rankdir = graphTargetAttributes.now().values.get("rankdir")
          .map(_.value.toString)
          .flatMap(str => Try(Rankdir.valueOf(str)).toOption)
          .getOrElse(TB)

        // Calculate position and rotation based on rankdir
        val (trX, trY, rotation) = rankdir match
          case TB => // Top to Bottom - show below, no rotation needed (default)
            (bbox.x + bbox.width / 2 - (w * scale) / 2, bbox.y + bbox.height + (h * scale) / 4 + 1, 0)
          case LR => // Left to Right - show to the right, rotate 270 degrees
            (bbox.x + bbox.width + (w * scale) / 4 + 1, bbox.y + bbox.height / 2 - (h * scale) / 2, 270)
          case BT => // Bottom to Top - show above, rotate 180 degrees
            (bbox.x + bbox.width / 2 - (w * scale) / 2, bbox.y - (h * scale) - (h * scale) / 4 - 1, 180)
          case RL => // Right to Left - show to the left, rotate 90 degrees
            (bbox.x - (w * scale) - (w * scale) / 4 - 1, bbox.y + bbox.height / 2 - (h * scale) / 2, 90)

        Some(
          g0.amend(
            svg.transform := s"translate($trX, $trY) scale($scale)",
            arrowGroup.amend(
              svg.transform := s"rotate($rotation, 8, 8)"
            )
          )
        )
      case _ => None

  /** Creates a standalone SVG element with the given viewBox
    */
  def selfContainedSvg(viewBox: BBox): ReactiveSvgElement[dom.svg.SVG] =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
      svg.cls        := "graphviz no-text-select"
    )

  /** Gets the x,y translation values from an SVG group element's transform, or (0,0) if none exists
    */
  private def getTranslate(g: dom.svg.G): Point2d[SvgUnit] =
    if js.isUndefined(g.transform) then SvgUnit.origin
    else
      val transformList = g.transform.baseVal
      val tranformPoints =
        for
          i <- 0 until transformList.numberOfItems
          transform = transformList.getItem(i)
          if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
        yield (SvgUnit(transform.matrix.e), SvgUnit(transform.matrix.f))

      tranformPoints.headOption.getOrElse(SvgUnit.origin)

  /** Creates a reactive SVG rectangle element representing the selection box when dragging.
    *
    * @param action
    *   Signal containing the current selection rectangle state
    * @param svgElement
    *   The SVG element that contains the selection
    * @return
    *   Signal containing an optional SVG rect element. The rect is only present when there is an active selection
    *   action.
    */
  private def DrawSelectionRect(
      action:     Signal[Option[Action.Area]],
      svgElement: dom.svg.SVG
  ): Signal[Option[ReactiveSvgElement[dom.svg.RectElement]]] =
    action.map:
      _.flatMap: action =>
        val (p0, p1) = action.rect.asSVGPair(svgElement.getScreenCTM())
        Some(
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x      := p0.x.min(p1.x).toString,
            svg.y      := p0.y.min(p1.y).toString,
            svg.width  := math.abs(p1.x - p0.x).toString,
            svg.height := math.abs(p1.y - p0.y).toString
          )
        )

  /** Creates a reactive SVG arrow element when dragging to create a new edge.
    *
    * @param rect
    *   Signal containing the current selection rectangle state
    * @param svgElement
    *   The SVG group element that contains the arrow
    * @return
    *   Signal containing an optional SVG group element. The group contains a line from the start node's center to the
    *   current mouse position, and a circle at the end point. Only present during an Edge action.
    */
  private def DraggingArrow(
      rect:       Signal[Option[Action.Line]],
      svgElement: dom.svg.G
  ): Signal[Option[ReactiveSvgElement[dom.svg.G]]] =
    rect.map:
      _.flatMap: action =>
        val (p0, p1) = action.rect.asSVGPair(svgElement.getScreenCTM())
        if p0 === p1 then
          None
        else
          val bbox = action.start.get.getBBox()
          // Calculate center point
          val centerX = bbox.x + bbox.width / 2
          val centerY = bbox.y + bbox.height / 2
          
          // Check if target point is inside bounding box
          val isInside = p1.x >= bbox.x && p1.x <= bbox.x + bbox.width &&
                        p1.y >= bbox.y && p1.y <= bbox.y + bbox.height
          
          // Calculate start point - use center if inside bbox, intersection if outside
          val (x1, y1) = if isInside then
            (centerX, centerY)
          else
            // Define the four edges of the bounding box
            val left = bbox.x
            val right = bbox.x + bbox.width
            val top = bbox.y
            val bottom = bbox.y + bbox.height
            
            // Direction vector from center to target point
            val dx = p1.x - centerX
            val dy = p1.y - centerY
            
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

          Some(
            svg.g(
              svg.idAttr := "dragging-arrow-group",
              // Define the arrowhead marker
              svg.defs(
                svg.marker(
                  svg.idAttr      := "arrowhead",
                  svg.viewBox     := "0 0 10 10",
                  svg.refX        := "9",
                  svg.refY        := "5",
                  svg.markerWidth := "4",  // Smaller size
                  svg.markerHeight:= "4",  // Smaller size
                  svg.orient      := "auto-start-reverse",
                  svg.path(
                    // Vee style path with concave base
                    svg.d := "M 0 0 L 10 5 L 0 10 L 2 5 z",
                    svg.fill := "#2c70ff",  // Selected border blue
                    svg.stroke := "#2c70ff"  // Match the fill color
                  )
                )
              ),
              // Draw the line with the arrowhead
              svg.line(
                svg.idAttr        := "dragging-arrow-line",
                svg.x1            := x1.toString,
                svg.y1            := y1.toString,
                svg.x2            := p1.x.toString,
                svg.y2            := p1.y.toString,
                svg.markerEnd     := "url(#arrowhead)",
                svg.stroke        := "#2c70ff",  // Selected border blue
                svg.strokeWidth   := "1"  // Thinner line to match smaller arrowhead
              )
            )
          )
