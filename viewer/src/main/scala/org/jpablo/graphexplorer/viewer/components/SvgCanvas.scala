package org.jpablo.graphexplorer.viewer.components

import scala.scalajs.js
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import org.jpablo.graphexplorer.viewer.models
import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.NodeId

// A SvgCanvas is a SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  def clientCoords(e: dom.MouseEvent): (Point2d[Double], Boolean) = ((e.clientX, e.clientY), e.shiftKey)

  // rawSvg is the SVG element as it comes from DOT
  def apply(state: ViewerState)(rawSvg: dom.SVGSVGElement): ReactiveSvgElement[dom.SVGSVGElement] =

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
          var pressing = Var(false)
          Seq(
            svg.transform <-- state.transform,
            // --------------------------------------------------------
            //   "New edge" button
            // --------------------------------------------------------
            child.maybe <--
              state.diagramSelection.signal.combineWith(state.mouse.selectionRect.signal).map: (selectedNodes, selectionRect) =>
                if selectedNodes.size == 1 then
                  val nodeId = selectedNodes.head
                  val active = selectionRect.collect { case SelectionRect(_, _, _, _, _, Action.Edge(_)) => true }.getOrElse(false)
                  for
                    elem <- selectableElements.find(_.nodeId == nodeId)
                    btn <- NewEdgeButtonElement(elem)
                  yield
                    // --------------------------------------------------------
                    // Mouse interaction
                    // --------------------------------------------------------
                    btn.amend(
                      svg.cls := ("selected" -> active),
                      onMouseDown.stopPropagation --> { ev =>
                        state.mouse.startSelection((ev.clientX, ev.clientY), shift = false, Action.Edge(elem))
                      },
                      onMouseUp.stopPropagation --> { ev =>
                        state.addNode()
                        state.mouse.endSelection()
                      }
                    )
                else
                  None,
            // --------------------------------------------------------
            //   draw dragging arrow
            // --------------------------------------------------------
            child.maybe <-- DraggingArrow(state.mouse.selectionRect.signal, group.ref),
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
          child.maybe <-- DrawSelectionRect(state.mouse.selectionRect.signal, topLevelSvg.ref),
          // --------------------------------------------------------
          //   select elements intersecting selectionRec
          // --------------------------------------------------------
          state.mouse.selectionRect.signal --> { maybeRect =>
            for rect <- maybeRect do
              rect.action match
                case Action.Selection =>
                  val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.nodeId).toSet
                  if nodesInRect.nonEmpty then
                    if rect.shift then
                      state.diagramSelection.add(nodesInRect)
                    else
                      state.diagramSelection.set(nodesInRect)
                  else if !rect.shift then
                      state.diagramSelection.clear()

                case Action.Edge(start) =>
                  findNode(rect) match
                    case Some(end) => state.diagramSelection.set(Set(start.nodeId, end))
                    case None      => state.diagramSelection.set(Set(start.nodeId))

          },
          // --------------------------------------------------------
          //   synchronize svg elements with diagramSelection
          // --------------------------------------------------------
          state.diagramSelection.signal --> { selectedNodes =>
            for elem <- selectableElements do
              if elem.nodeId in selectedNodes then
                elem.select()
              else
                elem.unselect()
          }
        )
      }

  end apply


  private def NewEdgeButtonElement(elem: SelectableElement): Option[ReactiveSvgElement[dom.svg.G]] =
    // only show the arrow button if there is a single selected node
    // val elem = selectableElements.find(_.nodeId == nodeId)
    // only show the arrow button if the selected node is a node
    val g0 =
      svg.g(
        svg.cls := s"new-edge-button",
        svg.pointerEvents := "all",
        svg.circle(svg.r := "8", svg.cx := "8", svg.cy := "8"),
        svg.path(svg.d := "M8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293z")
      )

    elem match
      case NodeElement(ref) =>
        val bbox = ref.getBBox()
        val scale = 0.4
        // https://icons.getbootstrap.com/icons/arrow-down-circle/
        val w = 16 // Original width of the icon
        val h = 16 // Original height of the icon
        val trX = bbox.x + bbox.width/2 - (w * scale)/2
        val trY = bbox.y + bbox.height + (h * scale)/4 + 1
        Some(
          g0.amend(svg.transform := s"translate($trX, $trY) scale($scale)")
        )
      case _ => None

  
  /** Checks if a selectable element intersects with a selection rectangle
   * 
   * @param elem The selectable element to check
   * @param rect The selection rectangle in client coordinates
   * @return true if the element's bounding box intersects with the selection rectangle
   * 
   * The method:
   * 1. Gets the element's bounding box in client coordinates
   * 2. Normalizes the selection rect coordinates to handle any direction of dragging
   * 3. Uses a standard rectangle intersection test
   */
  private def isNodeInRect(elem: SelectableElement, rect: SelectionRect): Boolean =
    val bbox = elem.get.getBoundingClientRect()
    val normalizedRect = (
      x = rect.startX.min(rect.endX),
      y = rect.startY.min(rect.endY), 
      width = math.abs(rect.endX - rect.startX),
      height = math.abs(rect.endY - rect.startY)
    )
    !(bbox.right < normalizedRect.x ||
      bbox.left > normalizedRect.x + normalizedRect.width ||
      bbox.bottom < normalizedRect.y ||
      bbox.top > normalizedRect.y + normalizedRect.height)

  
  /** 
   * Creates a standalone SVG element with the given viewBox 
   */
  def selfContainedSvg(viewBox: BBox): ReactiveSvgElement[dom.svg.SVG] =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
      svg.cls        := "graphviz no-text-select",
    )
  
  /** 
   * Gets the x,y translation values from an SVG group element's transform, or (0,0) if none exists 
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
        yield
          (SvgUnit(transform.matrix.e), SvgUnit(transform.matrix.f))

      tranformPoints.headOption.getOrElse(SvgUnit.origin)
  
  /** 
   * Creates a reactive SVG rectangle element representing the selection box when dragging.
   *
   * @param rect Signal containing the current selection rectangle state
   * @param svgElement The SVG element that contains the selection
   * @return Signal containing an optional SVG rect element. The rect is only present
   *         when there is an active selection action.
   */
  private def DrawSelectionRect(rect: Signal[Option[SelectionRect]], svgElement: dom.svg.SVG): Signal[Option[ReactiveSvgElement[dom.svg.RectElement]]] =
    rect.map:
      _.flatMap:
        case selectionRect if selectionRect.action == Action.Selection =>
          svgElement.getScreenCTM()
          val (p0, p1) = selectionRect.asSVGPair(svgElement.getScreenCTM())
          Some(
            svg.rect(
              svg.idAttr := "selection-rectangle",
              svg.x := p0.x.min(p1.x).toString,
              svg.y := p0.y.min(p1.y).toString,
              svg.width := math.abs(p1.x - p0.x).toString,
              svg.height := math.abs(p1.y - p0.y).toString,
            )
          )
        case _ => None


  /** 
   * Creates a reactive SVG arrow element when dragging to create a new edge.
   *
   * @param rect Signal containing the current selection rectangle state
   * @param svgElement The SVG group element that contains the arrow
   * @return Signal containing an optional SVG group element. The group contains a line
   *         from the start node's center to the current mouse position, and a circle
   *         at the end point. Only present during an Edge action.
   */
  private def DraggingArrow(rect: Signal[Option[SelectionRect]], svgElement: dom.svg.G): Signal[Option[ReactiveSvgElement[dom.svg.G]]] =
    rect.map:
      _.flatMap: selectionRect =>
        selectionRect.action match
          case Action.Edge(start) =>
            val (p0, p1) = selectionRect.asSVGPair(svgElement.getScreenCTM())
            if p0 === p1 then
              None
            else
              val bbox = start.get.getBBox()
              val x1 = bbox.x + bbox.width/2
              val y1 = bbox.y + bbox.height/2
              Some(
                svg.g(
                  svg.idAttr := "dragging-arrow-group",
                  svg.line(svg.idAttr := "dragging-arrow-line", svg.x1 := x1.toString, svg.y1 := y1.toString, svg.x2 := p1.x.toString, svg.y2 := p1.y.toString),
                  // svg.circle(svg.idAttr := "dragging-arrow-start-circle", svg.r := "1", svg.cx := p0.x.toString, svg.cy := p0.y.toString),
                  svg.circle(svg.idAttr := "dragging-arrow-end-circle", svg.r := ".5", svg.cx := p1.x.toString, svg.cy := p1.y.toString)
                )
              )
          case _ => None
          
  /** 
   * Finds the node ID at the given selection rectangle's end point 
   */
  private def findNode(rect: SelectionRect): Option[NodeId] =
    val elements = dom.document.elementsFromPoint(rect.endX, rect.endY)
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest("g.node, g.edge")))
      .distinct
      .map(SelectableElement.fromDomElement)
      .collectFirst { case Some(n @ NodeElement(_)) => n.nodeId }
