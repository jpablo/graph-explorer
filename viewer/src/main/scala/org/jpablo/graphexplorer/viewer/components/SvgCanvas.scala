package org.jpablo.graphexplorer.viewer.components

import scala.scalajs.js
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import org.jpablo.graphexplorer.viewer.models
import org.scalajs.dom.SVGGElement

// A SvgCanvas is a SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  // rawSvg is the SVG element as it comes from DOT
  def apply(state: ViewerState)(rawSvg: dom.SVGSVGElement): ReactiveSvgElement[dom.SVGSVGElement] =

    val viewBox = rawSvg.viewBox.baseVal
    val firstGroup: dom.svg.G =
      val g0 = rawSvg.querySelector("g")
      (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

    val (gX, gY) = getTranslate(firstGroup)
    selfContainedSvg(
      BBox(viewBox.x - gX.value, viewBox.y - gY.value, viewBox.width, viewBox.height),
      // -------------------------------------------------------- 
      // The top level svg.g element
      // --------------------------------------------------------
      foreignSvgElement(firstGroup)
        .amendThis( (thisNode: ReactiveSvgElement[dom.SVGElement]) =>
          val selectableElements = SelectableElement.findAll(thisNode.ref)
          Seq(
            svg.transform <-- state.transform,
            // -------------------------------------------------------- 
            //   "New edge" button
            // --------------------------------------------------------
            child.maybe <--
              state.diagramSelection.signal.map: selectedNodes =>
                newEdgeButtonElement(selectedNodes, selectableElements, thisNode)
                  .map: 
                    _.amend(onMouseDown.stopPropagation --> { _ => state.addNode() })
          )
        ),

      inContext { thisNode =>
        val selectableElements = SelectableElement.findAll(thisNode.ref)
        Seq(
          // --------------------------------------------------------
          //   draw selection rect
          // --------------------------------------------------------
          child.maybe <-- DrawSelectionRect(state.mouse.selectionRect.signal, thisNode.ref),
          // --------------------------------------------------------
          //   select elements intersecting selectionRec
          // --------------------------------------------------------
          state.mouse.selectionRect.signal --> { maybeRect =>
            for rect <- maybeRect do
              val nodesInRect = 
                selectableElements
                .filter(isNodeInRect(_, rect))
                .map(_.nodeId).toSet
              
              if nodesInRect.nonEmpty then
                if rect.shift then
                  state.diagramSelection.add(nodesInRect)
                else
                  state.diagramSelection.set(nodesInRect)
              else if !rect.shift then
                  state.diagramSelection.clear()
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
      },

      // inContext { thisNode =>
      //   val ref = thisNode.ref
      //   val startPosClient = startNode.map(_.map((nodeId, p) => (nodeId, toSVGCoords(p.x, p.y, ref))))
      //   val endPosClient = endPos.map(p => toSVGCoords(p.x, p.y, ref))

      //   child(DraggingArrow(startPosClient, endPosClient)) <-- isDragging,
      // }

    )
  end apply


  private def newEdgeButtonElement(
    selectedNodes: Set[models.NodeId], 
    selectableElements: Seq[SelectableElement], 
    thisNode: ReactiveSvgElement[dom.SVGElement]
  ): Option[ReactiveSvgElement[SVGGElement]] =
    // only show the arrow button if there is a single selected node
    if selectedNodes.size == 1 then
      // Show the + icon
      val nodeId = selectedNodes.head
      val elem = selectableElements.find(_.nodeId == nodeId).get
      // only show the arrow button if the selected node is a node
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
            svg.g(
              svg.transform := s"translate($trX, $trY) scale($scale)",
              svg.pointerEvents := "all",
              // onMouseDown.stopPropagation --> { _ =>
              //   println("clicked")
              // },
              svg.circle(svg.r := "8", svg.cx := "8", svg.cy := "8", svg.fill := "white"),
              svg.path(
                svg.fillRule := "evenodd",
                svg.d := "M1 8a7 7 0 1 0 14 0A7 7 0 0 0 1 8m15 0A8 8 0 1 1 0 8a8 8 0 0 1 16 0M8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293z",
              )
            )
          )
        case _ => None
    else
      None


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

  def selfContainedSvg(
      viewBox: BBox,
      elems:   Modifier[ReactiveSvgElement[dom.SVGSVGElement]]*
  ): ReactiveSvgElement[dom.SVGSVGElement] =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
      svg.cls        := "graphviz no-text-select",
      elems
    )

  private def getTranslate(g: dom.svg.G): Point2d[SvgUnit] =
    if js.isUndefined(g.transform) then SvgUnit.origin
    else
      val transformList = g.transform.baseVal
      (for {
        i <- 0 until transformList.numberOfItems
        transform = transformList.getItem(i)
        if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
      } yield (SvgUnit(transform.matrix.e), SvgUnit(transform.matrix.f))).headOption
        .getOrElse(SvgUnit.origin)

  private def DrawSelectionRect(rect: Signal[Option[SelectionRect]], svgElement: dom.SVGSVGElement): Signal[Option[ReactiveSvgElement[dom.SVGRectElement]]] =
    rect.map:
      _.map:
        selectionRect =>
          val (p0, p1) = selectionRect.asSVGPair(svgElement)
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x := p0.x.min(p1.x).toString,
            svg.y := p0.y.min(p1.y).toString,
            svg.width := math.abs(p1.x - p0.x).toString,
            svg.height := math.abs(p1.y - p0.y).toString,
          )


  private def DraggingArrow(
      startNode: Signal[Option[(models.NodeId, dom.SVGPoint)]],
      endPos:    Signal[dom.SVGPoint]
  ): ReactiveSvgElement[dom.SVGGElement] =
    // Define start and end position signals
    val startX = startNode.map {
      case Some((_, start)) => start.x.toString
      case None             => 0.0.toString
    }
    val startY = startNode.map {
      case Some((_, start)) => start.y.toString
      case None             => 0.0.toString
    }
    val endX = endPos.map(_.x.toString)
    val endY = endPos.map(_.y.toString)
    svg.g(
      svg.idAttr := "dragging-arrow-group",
      // Temporary line for dragging
      svg.line(
        svg.idAttr := "dragging-arrow-line",
        svg.x1 <-- startX,
        svg.y1 <-- startY,
        svg.x2 <-- endX,
        svg.y2 <-- endY
      ),
      // Circle at the start of the line
      svg.circle(
        svg.idAttr := "dragging-arrow-start-circle",
        svg.r      := "1",
        svg.cx <-- startX,
        svg.cy <-- startY
      ),
      // Circle at the end of the line
      svg.circle(
        svg.idAttr := "dragging-arrow-end-circle",
        svg.r      := "1",
        svg.cx <-- endX,
        svg.cy <-- endY
      )
    )
