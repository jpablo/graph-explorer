package org.jpablo.typeexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.typeexplorer.viewer.components.selectable.*
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.{DiagramSelectionOps, ViewerState}
import org.scalajs.dom
import org.scalajs.dom.{HTMLDivElement, WheelEvent}

type Point2d = (x: Double, y: Double)

def CanvasContainer(
    state:      ViewerState,
    zoomValue:  Var[Double],
    fitDiagram: EventStream[Unit]
) =
  val translateXY: Var[Point2d] = Var((0.0, 0.0))

  val transform: Signal[String] =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map((z, t) => s"scale($z) translate(${t.x} ${t.y})")

  val svgElement =
    state.svgDiagram.map(_.ref).map(SvgDotDiagram.withTransform(transform))

  val svgDiagram2: Signal[SvgDotDiagram] = svgElement.map(_.ref).map(SvgDotDiagram(_))
  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(svgDiagram2)) -->
      handleSvgClick(state.diagramSelection).tupled,
//    onMouseMove.preventDefault.map(e => (e.clientX, e.clientY)) --> mousePos,
    onWheel --> handleWheel(zoomValue, translateXY),
    fitDiagram --> {
      zoomValue.set(1)
      translateXY.set((0, 0))
    },
    child <-- svgElement
  )
end CanvasContainer

private def handleWheel(
    zoomValue:   Var[Double],
    translateXY: Var[Point2d]
)(wEv: WheelEvent) =
  // print the current mouse position to the console
  val h = dom.window.innerHeight.max(1)
  if wEv.metaKey then zoomValue.update(d => (d - wEv.deltaY / h).max(0))
  else translateXY.update(t => (x = t.x - wEv.deltaX, y = t.y - wEv.deltaY))

private def handleSvgClick(diagramSelection: DiagramSelectionOps)(
    event:      dom.MouseEvent,
    svgDiagram: SvgDotDiagram
): Unit =
  // 1. Identify and parse the element that was clicked
  val selectedElement: Option[SelectableElement] =
    event.target
      .asInstanceOf[dom.Element]
      .parentNodes
      .takeWhile(_.isInstanceOf[dom.SVGElement])
      .map(SelectableElement.build)
      .collectFirst { case Some(g) => g }

  // 2. Update selected element's appearance
  selectedElement match
    case Some(g) =>
      g match
        case node: NodeElement =>
          if event.metaKey then
            node.toggle()
            diagramSelection.toggle(node.nodeId)
          else
            svgDiagram.unselectAll()
            node.select()
            diagramSelection.replace(node.nodeId)

        case edge: EdgeElement =>
          if event.metaKey then
            edge.toggle()
            for (a, b) <- edge.endpointIds do
              svgDiagram.select(Set(a, b))
              diagramSelection.toggle(a, b)
          else
            svgDiagram.unselectAll()
            edge.select()
            for (a, b) <- edge.endpointIds do
              svgDiagram.select(Set(a, b))
              diagramSelection.replace(a, b)

    case None =>
      svgDiagram.unselectAll()
      diagramSelection.clear()

//private def handleOnMouseOver(diagramSelection: DiagramSelectionOps)(
//    ev:         dom.MouseEvent,
//    state.svgDiagram: SvgDotDiagram
//): Unit =
//  // 1. Identify and parse the element that was clicked
//  val selectedElement: Option[SelectableElement] =
//    ev.target
//      .asInstanceOf[dom.Element]
//      .path
//      .takeWhile(_.isInstanceOf[dom.SVGElement])
//      .map(SelectableElement.from)
//      .collectFirst { case Some(g) => g }
//
//  // 2. Update selected element's appearance
//  selectedElement match
//    case Some(g) =>
//      g match
//        case node: NodeElement =>
//          if ev.metaKey then
//            node.toggle()
//            diagramSelection.toggle(node.nodeId)
//          else
//            state.svgDiagram.unselectAll()
//            node.select()
//            diagramSelection.replace(node.nodeId)
//
//        case edge: EdgeElement =>
//          if ev.metaKey then
//            edge.toggle()
//            for pp <- edge.endpointIds do
//              val ids = Set(pp._1, pp._2)
//              state.svgDiagram.select(ids)
//              diagramSelection.toggle(ids.toSeq*)
//          else
//            state.svgDiagram.unselectAll()
//            edge.select()
//            for pp <- edge.endpointIds do
//              val ids = Set(pp._1, pp._2)
//              state.svgDiagram.select(ids)
//              diagramSelection.replace(ids.toSeq*)
//
//    case None =>
//      state.svgDiagram.unselectAll()
//      diagramSelection.clear()
