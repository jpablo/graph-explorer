package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.Action
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint, SvgPoint}
import org.jpablo.graphexplorer.viewer.components.toSvgPair

import scala.scalajs.js

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  def clientCoords(e: dom.MouseEvent) = (ClientPoint(e.clientX, e.clientY), e.shiftKey)

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
            //   "New arrow" button
            // --------------------------------------------------------
            child.maybe <--
              diagramSelection.signal.map: selectedNodes =>
                if selectedNodes.size == 1 then
                  val nodeId = selectedNodes.head
                  for
                    elem <- selectableElements.find(_.nodeId == nodeId)
                    btn  <- NewArrowButton(elem, graphTargetAttributes)
                  yield
                  // --------------------------------------------------------
                  // Mouse interaction
                  // --------------------------------------------------------
                  btn.amend(
                    onMouseDown.stopPropagation --> { ev =>
                      diagramSelection.startSelectionLine(ClientPoint(ev.clientX, ev.clientY), shift = false, elem)
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
    val tr = getTranslate(firstGroup)
    val bbox = BBox(viewBox.x - tr.x, viewBox.y - tr.y, viewBox.width, viewBox.height)
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
                dom.document.elementsFromPoint(rect.end.x, rect.end.y)
              )
          },
          diagramSelection.selectionRectLine.signal --> { (actionO: Option[Action.Line]) =>
            for action <- actionO do
              val rect = action.rect
              diagramSelection.handleSelectionLineUpdate(
                action.start,
                dom.document.elementsFromPoint(rect.end.x, rect.end.y)
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
  private def getTranslate(g: dom.svg.G): SvgPoint =
    if js.isUndefined(g.transform) then SvgPoint.origin
    else
      val transformList = g.transform.baseVal
      val transformPoints =
        for
          i <- 0 until transformList.numberOfItems
          transform = transformList.getItem(i)
          if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
        yield SvgPoint(transform.matrix.e, transform.matrix.f)

      transformPoints.headOption.getOrElse(SvgPoint.origin)

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
        val (p0, p1) = action.rect.toSvgPair(svgElement.getScreenCTM())
        Some(
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x      := (p0.x min p1.x).toString,
            svg.y      := (p0.y min p1.y).toString,
            svg.width  := math.abs(p1.x - p0.x).toString,
            svg.height := math.abs(p1.y - p0.y).toString
          )
        )

