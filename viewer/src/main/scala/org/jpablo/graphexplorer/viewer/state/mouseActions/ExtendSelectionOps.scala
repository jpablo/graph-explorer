package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.toSvgPair
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.MouseActionRect

trait ExtendSelectionOps:
  this: ViewerState =>

  def onExtendSelectionAction(selectableElements: Seq[SelectableElement])(action: MouseAction.ExtendSelectionAction): Unit =
    selection.selectExtendSelectionOverlappingElements(
      rect = action.rect,
      selectableElements = selectableElements,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  // --------------------------------------------------------
  //   draw selection rect
  // --------------------------------------------------------

  /** Creates a reactive SVG rectangle element representing the selection box when dragging.
    *
    * @param action
    *   Signal containing the current selection rectangle state
    * @param topLevelSVG
    *   The SVG element that contains the selection
    * @return
    *   Signal containing an optional SVG rect element. The rect is only present when there is an active selection action.
    */
  def DrawSelectionRect(screenCtm: dom.SVGMatrix, rect: MouseActionRect) =
    val rectCoords = selectionRectCoords(screenCtm, rect)
    svg.rect(
      svg.idAttr := "selection-rectangle",
      svg.x      := rectCoords.x,
      svg.y      := rectCoords.y,
      svg.width  := rectCoords.width,
      svg.height := rectCoords.height
    )

  def selectionRectCoords(screenCtm: dom.SVGMatrix, rect: MouseActionRect) =
    val (start, end) = rect.toSvgPair(screenCtm)
    (
      x = (start.x min end.x).toString,
      y = (start.y min end.y).toString,
      width = math.abs(end.x - start.x).toString,
      height = math.abs(end.y - start.y).toString
    )
