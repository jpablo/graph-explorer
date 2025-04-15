package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.toSvgPair
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.SvgPoint

trait ExtendSelectionOps:
  this: ViewerState =>

  def onExtendSelectionAction(selectableElements: Seq[SelectableElement])(action: MouseAction.ExtendSelectionAction) =
    selection.selectExtendSelectionOverlappingElements(
      rect = action.rect,
      selectableElements = selectableElements,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  // --------------------------------------------------------
  //   draw selection rect
  // --------------------------------------------------------
  def buildDrawSelectionRect(topLevelSvgRef: dom.svg.SVG) =
    DrawSelectionRect(mouseAction.extendSelectionAction.map(_.map(_.rect.toSvgPair(topLevelSvgRef.getScreenCTM()))))

  /** Creates a reactive SVG rectangle element representing the selection box when dragging.
    *
    * @param action
    *   Signal containing the current selection rectangle state
    * @param topLevelSVG
    *   The SVG element that contains the selection
    * @return
    *   Signal containing an optional SVG rect element. The rect is only present when there is an active selection action.
    */
  private def DrawSelectionRect(
      startEnd: Signal[Option[(SvgPoint, SvgPoint)]]
  ): Signal[Option[ReactiveSvgElement[dom.svg.RectElement]]] =
    startEnd.map:
      _.flatMap: (start, end) =>
        Some(
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x      := (start.x min end.x).toString,
            svg.y      := (start.y min end.y).toString,
            svg.width  := math.abs(end.x - start.x).toString,
            svg.height := math.abs(end.y - start.y).toString
          )
        )
