package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selectable.*
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  div(
    idAttr := "canvas-container",
    inContext { thisNode =>
      state.diagramSelection.signal --> { selectedNodes =>
        for elem <- SelectableElement.findAll(thisNode.ref) do
          if selectedNodes contains elem.nodeId then elem.select()
          else elem.unselect()
      }
    },
    onClick.preventDefault --> handleSvgClick(state),
    onWheel(_.withCurrentValueOf(state.svgDiagramElement)) -->
      handleWheel(state.zoomValue, state.translateXY).tupled,
    fitDiagram --> state.resetView(),
    child <-- state.svgDiagramElement
  )

private def handleWheel(
    zoomValue:   Var[Double],
    translateXY: Var[Point2d[SvgUnit]]
)(wEv: dom.WheelEvent, svgDiagram: ReactiveSvgElement[dom.SVGSVGElement]) =
  val clientHeight = dom.window.innerHeight.max(1)
  val clientWidth = dom.window.innerWidth.max(1)

  if wEv.metaKey && wEv.deltaY != 0 then
    zoomValue.update: z =>
      (z - wEv.deltaY / clientHeight).max(0.001)
  else
    val viewBox = svgDiagram.ref.viewBox.baseVal
    val z = zoomValue.now()
    val scale = (viewBox.width / clientWidth).max(viewBox.height / clientHeight)
    val svgDelta = (SvgUnit(wEv.deltaX * scale / z), SvgUnit(wEv.deltaY * scale / z))
    translateXY.update(_ - svgDelta)

private def handleSvgClick(state: ViewerState)(event: dom.MouseEvent): Unit =
  // 1. Identify and parse the element that was clicked
  val selectedElement: Option[SelectableElement] =
    event.target
      .asInstanceOf[dom.Element]
      .parentNodes
      .takeWhile(_.isInstanceOf[dom.SVGElement])
      .map(SelectableElement.fromDomElement)
      .collectFirst { case Some(g) => g }

  // 2. Update selection based on user action
  selectedElement match
    case None => state.diagramSelection.clear()
    case Some(element) =>
      (element, event.metaKey) match
        case (n @ NodeElement(_), false) => state.diagramSelection.set(n.nodeId)
        case (n @ NodeElement(_), true)  => state.diagramSelection.toggle(n.nodeId)
        case (e @ EdgeElement(_), false) =>
          e.toArrow.foreach { a =>
            state.diagramSelection.set(a.source, a.target, e.nodeId)
          }
        case (e @ EdgeElement(_), true) =>
          e.toArrow.foreach(a => state.diagramSelection.toggle(a.source, a.target, e.nodeId))

end handleSvgClick
