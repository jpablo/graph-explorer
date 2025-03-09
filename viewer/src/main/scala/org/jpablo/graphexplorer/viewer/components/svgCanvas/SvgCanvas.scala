package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.{Action, toSvgPair}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint}

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  def clientCoords(e: dom.MouseEvent) = (ClientPoint(e.clientX, e.clientY), e.shiftKey)

  // rawSvg is the SVG element as it comes from DOT
  def apply(
      rawSvg:       dom.svg.SVG,
      transform:    Signal[String],
      selectionOps: DiagramSelectionOps,
      addNode:      () => Unit,
      getRankdir:   () => Rankdir
  ): ReactiveSvgElement[dom.svg.SVG] =

    import selectionOps.selection

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
              selection.signal.map: selectedNodes =>
                if selectedNodes.size == 1 then
                  val elementId = selectedNodes.head
                  for
                    elem <- selectableElements.find(_.elementId == elementId)
                    btn  <- NewArrowButton(elem, getRankdir)
                  yield
                  // --------------------------------------------------------
                  // Mouse interaction
                  // --------------------------------------------------------
                  btn.amend(
                    onMouseDown.stopPropagation --> { ev =>
                      selection.startSelectionLine(ClientPoint(ev.clientX, ev.clientY), shift = false, elem)
                    },
                    onMouseUp.stopPropagation --> { _ =>
                      selection.endSelectionLine()
                      addNode()
                    }
                  )
                else
                  None
            ,
            // --------------------------------------------------------
            //   draw dragging arrow
            // --------------------------------------------------------
            child.maybe <-- DraggingArrow(selection.selectionRectLine.signal, group.ref)
          )

    // --------------------------------------------------------
    // The top level <svg> element
    // --------------------------------------------------------
    val viewBox = rawSvg.viewBox.baseVal
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
          child.maybe <-- DrawSelectionRect(selection.selectionRectArea.signal, topLevelSvg.ref),
          // --------------------------------------------------------
          //   select elements intersecting selectionRec
          // --------------------------------------------------------
          // TODO: do we need to listen to state.selectionRectLine.signal here?
          selection.selectionRectArea.signal --> { actionO =>
            for action <- actionO do
              val rect = action.rect
              selection.handleSelectionAreaUpdate(
                rect,
                selectableElements,
                dom.document.elementsFromPoint(rect.end.x, rect.end.y)
              )
          },
          selection.selectionRectLine.signal --> { (actionO: Option[Action.Line]) =>
            for action <- actionO do
              val rect = action.rect
              selection.handleSelectionLineUpdate(
                action.start,
                dom.document.elementsFromPoint(rect.end.x, rect.end.y)
              )
          },
          // --------------------------------------------------------
          //   synchronize svg elements with diagramSelection
          // --------------------------------------------------------
          selection.signal --> { selectedNodes =>
            for elem <- selectableElements do
              if elem.elementId in selectedNodes then
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

  /** Creates a reactive SVG rectangle element representing the selection box when dragging.
    *
    * @param action
    *   Signal containing the current selection rectangle state
    * @param topLevelSVG
    *   The SVG element that contains the selection
    * @return
    *   Signal containing an optional SVG rect element. The rect is only present when there is an active selection
    *   action.
    */
  private def DrawSelectionRect(
      action:      Signal[Option[Action.Area]],
      topLevelSVG: dom.svg.SVG
  ): Signal[Option[ReactiveSvgElement[dom.svg.RectElement]]] =
    action.map:
      _.flatMap: action =>
        val (start, end) = action.rect.toSvgPair(topLevelSVG.getScreenCTM())
        Some(
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x      := (start.x min end.x).toString,
            svg.y      := (start.y min end.y).toString,
            svg.width  := math.abs(end.x - start.x).toString,
            svg.height := math.abs(end.y - start.y).toString
          )
        )
