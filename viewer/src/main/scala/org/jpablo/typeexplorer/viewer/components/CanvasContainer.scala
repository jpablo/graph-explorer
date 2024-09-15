package org.jpablo.typeexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.typeexplorer.viewer.components.selectable.*
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.{DiagramSelectionOps, ViewerState}
import org.scalajs.dom

case class ScreenUnit(value: Double) extends AnyVal

extension (p: Point2d[ScreenUnit]) def toSvgUnit: Point2d[SvgUnit] = (SvgUnit(p.x.value), SvgUnit(p.y.value))

extension (we: dom.WheelEvent) def delta: Point2d[ScreenUnit] = (ScreenUnit(we.deltaX), ScreenUnit(we.deltaY))

def CanvasContainer(
    state:      ViewerState,
    zoomValue:  Var[Double],
    fitDiagram: EventStream[Unit]
) =
  val translateXY: Var[Point2d[SvgUnit]] = Var(SvgUnit.origin)

  val transform: Signal[String] =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map { (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"
      }

  val svgElement =
    state.svgDiagram.map(_.ref).map(SvgDotDiagram.withTransform(transform))

  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(svgElement)) --> handleSvgClick(state.diagramSelection).tupled,
    onWheel --> handleWheel(zoomValue, translateXY),
    fitDiagram --> {
      zoomValue.set(1)
      translateXY.set(SvgUnit.origin)
    },
    child <-- svgElement
  )
end CanvasContainer

private def handleWheel(
    zoomValue:   Var[Double],
    translateXY: Var[Point2d[SvgUnit]]
)(wEv: dom.WheelEvent) =
  val h = dom.window.innerHeight.max(1)
  val deltaSvg = wEv.delta.toSvgUnit
  if wEv.metaKey then zoomValue.update(d => (d - wEv.deltaY / h).max(0))
  else translateXY.update(_ - deltaSvg)

private def handleSvgClick(diagramSelection: DiagramSelectionOps)(
    event:      dom.MouseEvent,
    svgDiagram: ReactiveSvgElement[dom.SVGSVGElement]
): Unit =
  val svgDotDiagram = SvgDotDiagram(svgDiagram.ref)
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
            svgDotDiagram.unselectAll()
            node.select()
            diagramSelection.replace(node.nodeId)

        case edge: EdgeElement =>
          if event.metaKey then
            edge.toggle()
            for (a, b) <- edge.endpointIds do
              svgDotDiagram.select(Set(a, b))
              diagramSelection.toggle(a, b)
          else
            svgDotDiagram.unselectAll()
            edge.select()
            for (a, b) <- edge.endpointIds do
              svgDotDiagram.select(Set(a, b))
              diagramSelection.replace(a, b)

    case None =>
      svgDotDiagram.unselectAll()
      diagramSelection.clear()
end handleSvgClick
