package org.jpablo.typeexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.selectable.*
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.{DiagramSelectionOps, ViewerState}
import org.scalajs.dom
import org.scalajs.dom.{HTMLDivElement, SVGSVGElement, WheelEvent}
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.scalajs.dom.svg.G

type ZoomValue = (zoom: Double, mousePos: Option[(Double, Double)])

def CanvasContainer(
    state:      ViewerState,
    zoomValue:  Var[Double],
    fitDiagram: EventStream[Unit]
) =
  // import state.owner
  val translateXY: Var[(Double, Double)] = Var((0.0, 0.0))
  val mousePos = Var((0.0, 0.0))

  val svgElement: Signal[ReactiveSvgElement[dom.SVGSVGElement]] =
    state.svgDiagram.map { svgDotDiagram =>
      val ref: dom.SVGSVGElement = svgDotDiagram.ref
      val g: G = svgDotDiagram.firstGroup
      val elem =
        foreignSvgElement(g)
          .amend(
            svg.transform <-- translateXY.signal
              .combineWith(zoomValue.signal)
              .map:
                case (x, y, z) =>
                  val (mx, my) = mousePos.now()
                  // s"translate(${mx} ${my}) scale(${z}) translate(${-mx} ${-my}) translate($x $y)"
                  s"scale($z) translate($x $y)"
//                      s"matrix($z 0 0 $z $x $y)"
          )

      val (x, y) = {
        val transformList = g.transform.baseVal
        (for {
          i <- 0 until transformList.numberOfItems
          transform = transformList.getItem(i)
          if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
        } yield (transform.matrix.e, transform.matrix.f)).headOption.getOrElse((0.0, 0.0))
      }
      dom.console.log(s"Translation values: ($x, $y)")

      val viewBox = ref.viewBox.baseVal

      svg.svg(
        svg.xmlns := "http://www.w3.org/2000/svg",
        svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
//            svg.width      := ref.width.baseVal.valueAsString,
//            svg.height     := ref.height.baseVal.valueAsString,
        svg.viewBox := s"${viewBox.x - x} ${viewBox.y - y} ${viewBox.width} ${viewBox.height}",
        svg.cls := "graphviz",
        elem
      )
    }

  val svgDiagram2: Signal[SvgDotDiagram] = svgElement.map(_.ref).map(SvgDotDiagram(_))
//  mousePos.signal.foreach(p => dom.console.log(s"mousePos: $p"))(state.owner)
//  val adjustSize = adjustSizeWith(translateXY.set, zoomValue.set)
  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(svgDiagram2)) -->
      handleSvgClick(state.diagramSelection).tupled,
    onMouseMove.preventDefault.map(e => (e.clientX, e.clientY)) --> mousePos,
    onWheel --> handleWheel(zoomValue, translateXY),
    fitDiagram.sample(svgDiagram2) --> {
      zoomValue.set(1)
      translateXY.set((0, 0))
    },
    child <-- svgElement
//     onMountBind { ctx =>
//       val svgParent = ctx.thisNode
//       def parentSize(): (Double, Double) = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
//       // the initial resize of the diagram
//       resizeObserver --> (_ => state.svgDiagram.foreach(adjustSize(parentSize))(ctx.owner))
//     },
//    inContext { svgParent => // aka #canvas-container
//      // def parentSize() = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
//
//      Seq(
//        child <-- state.svgDiagram.map: svgDotDiagram =>
//          val selection = state.diagramSelection.now()
//          svgDotDiagram.select(selection)
//          // remove elements not present in the new diagram (such elements did exist in the previous diagram)
//          state.diagramSelection.remove(selection -- svgDotDiagram.nodeIds)
//
//          val ref: SVGSVGElement = svgDotDiagram.ref
//          val g: G = svgDotDiagram.firstGroup
//          val elem =
//            foreignSvgElement(g)
//              .amend(
//                svg.transform <-- translateXY.signal
//                  .combineWith(zoomValue.signal)
//                  .map:
//                    case (x, y, z) =>
//                      val (mx, my) = mousePos.now()
//                      // s"translate(${mx} ${my}) scale(${z}) translate(${-mx} ${-my}) translate($x $y)"
//                      s"scale($z) translate($x $y)"
////                      s"matrix($z 0 0 $z $x $y)"
//              )
//
//          val (x, y) = {
//            val transformList = g.transform.baseVal
//            (for {
//              i <- 0 until transformList.numberOfItems
//              transform = transformList.getItem(i)
//              if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
//            } yield (transform.matrix.e, transform.matrix.f)).headOption.getOrElse((0.0, 0.0))
//          }
//          dom.console.log(s"Translation values: ($x, $y)")
//
//          val viewBox = ref.viewBox.baseVal
//
//          svg.svg(
//            svg.xmlns      := "http://www.w3.org/2000/svg",
//            svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
////            svg.width      := ref.width.baseVal.valueAsString,
////            svg.height     := ref.height.baseVal.valueAsString,
//            svg.viewBox := s"${viewBox.x - x} ${viewBox.y - y} ${viewBox.width} ${viewBox.height}",
//            svg.cls     := "graphviz",
//            elem
//          )
//          // svgDiagram.toLaminar
////            .amend(
////              svg.children(0).transform <-- translateXY.signal
////                .combineWith(zoomValue.signal)
////                .map((x, y, z) => s"translate($x $y) scale($z)")
////            )
//      )
//    }
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
//  translateXY(trX, trY)
//  zoomValue(if z == Double.PositiveInfinity then 1 else z)

private def handleWheel(
    zoomValue:   Var[Double],
    translateXY: Var[(Double, Double)]
)(wEv: WheelEvent) =
  // print the current mouse position to the console
  val h = dom.window.innerHeight.max(1)
  if wEv.metaKey then zoomValue.update(d => (d - wEv.deltaY / h).max(0))
  else translateXY.update((x, y) => (x - wEv.deltaX, y - wEv.deltaY))

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
