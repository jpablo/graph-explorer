package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selectable.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.KeyCode.Backspace

def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    onKeyDown(_.filter(_.keyCode == Backspace).sample(state.diagramSelection.signal)) --> { selection =>
      state.project.hiddenNodesV.update(_ ++ selection)
    },
    onClick --> handleSvgClick(state),
    onWheel(_.withCurrentValueOf(state.svgDiagramElement)) -->
      handleWheel(state.zoomValue, state.translateXY).tupled,
    fitDiagram --> state.resetView(),
    child <-- state.svgDiagramElement,
    inContext: thisNode =>
      // Sync svg style with internal state
      state.diagramSelection.signal --> { selectedNodes =>
        for elem <- SelectableElement.findAll(thisNode.ref) do
          if elem.nodeId in selectedNodes then
            elem.select()
          else
            elem.unselect()
      }
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
      element match
        case n: NodeElement =>
          if event.metaKey then
            state.diagramSelection.toggle(n.nodeId)
          else
            state.diagramSelection.set(Set(n.nodeId))
        case e: EdgeElement =>
          e.toArrow.foreach: arrow =>
            state.diagramSelection.handleClickOnArrow(arrow)(event.metaKey)

end handleSvgClick
