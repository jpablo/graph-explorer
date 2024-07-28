package org.jpablo.typeexplorer.viewer.components

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.selectable.*
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.{DiagramSelectionOps, ViewerState}
import org.scalajs.dom
import org.scalajs.dom.{HTMLDivElement, WheelEvent}
import io.laminext.syntax.core.*

def CanvasContainer(
    state:      ViewerState,
    zoomValue:  Var[Double],
    fitDiagram: EventStream[Unit]
) =
  val translateXY: Var[(Double, Double)] = Var((0.0, 0.0))
  val adjustSize = adjustSizeWith(translateXY.set, zoomValue.set)
  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(state.svgDiagram)) --> handleSvgClick(
      state.diagramSelection
    ).tupled,
    onWheel --> handleWheel(zoomValue, translateXY),
    onMountBind { ctx =>
      val svgParent = ctx.thisNode
      def parentSize(): (Double, Double) = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
      // scale the diagram to fit the parent container whenever the "fit" button is clicked
      fitDiagram
        .sample(state.svgDiagram)
        .foreach(adjustSize(parentSize))(ctx.owner)

      resizeObserver --> (_ => state.svgDiagram.foreach(adjustSize(parentSize))(ctx.owner))
    },
    inContext { svgParent => // aka #canvas-container
      def parentSize() = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
      Seq(
        child <-- state.svgDiagram.map: svgDiagram =>
          val selection = state.diagramSelection.now()
          svgDiagram.select(selection)
          // remove elements not present in the new diagram (such elements did exist in the previous diagram)
          state.diagramSelection.remove(selection -- svgDiagram.nodeIds)

          svgDiagram.toLaminar.amend(
            svg.transform <-- translateXY.signal
              .combineWith(zoomValue.signal)
              .map((x, y, z) => s"translate($x $y) scale($z)")
          )
      )
    }
  )

private def adjustSizeWith(
    translateXY: ((Double, Double)) => Unit,
    zoomValue:   Double => Unit
)(parentSize: () => (Double, Double))(svgDiagram: SvgDotDiagram): Unit =
  val (parentWidth, parentHeight) = parentSize()
  val (origW, origH) = svgDiagram.orig
  val z = math.min(parentWidth / origW, parentHeight / origH)
  val trX = (parentWidth - origW) / 2
  val trY = (parentHeight - origH) / 2
  translateXY(trX, trY)
  zoomValue(if z == Double.PositiveInfinity then 1 else z)

private def handleWheel(zoomValue: Var[Double], translateXY: Var[(Double, Double)])(wEv: WheelEvent) =
  val h = dom.window.innerHeight.max(1)
  if wEv.metaKey then zoomValue.update(_ - wEv.deltaY / h)
  else translateXY.update((x, y) => (x - wEv.deltaX, y - wEv.deltaY))

private def handleSvgClick(diagramSelection: DiagramSelectionOps)(
    ev:         dom.MouseEvent,
    svgDiagram: SvgDotDiagram
): Unit =
  // 1. Identify and parse the element that was clicked
  val selectedElement: Option[SelectableElement] =
    ev.target
      .asInstanceOf[dom.Element]
      .parentNodes
      .takeWhile(_.isInstanceOf[dom.SVGElement])
      .map(SelectableElement.from)
      .collectFirst { case Some(g) => g }

  // 2. Update selected element's appearance
  selectedElement match
    case Some(g) =>
      g match
        case node: NodeElement =>
          if ev.metaKey then
            node.toggle()
            diagramSelection.toggle(node.nodeId)
          else
            svgDiagram.unselectAll()
            node.select()
            diagramSelection.replace(node.nodeId)

        case edge: EdgeElement =>
          if ev.metaKey then
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
