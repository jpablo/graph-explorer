package org.jpablo.typeexplorer.viewer.components

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.selectable.*
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.DiagramSelectionOps
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def CanvasContainer(
    svgDiagram:       Signal[SvgDotDiagram],
    diagramSelection: DiagramSelectionOps,
    zoomValue:        Var[Double],
    fitDiagram:       EventStream[Unit]
) =
  val translateXY = Var((0.0, 0.0))
  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(svgDiagram)) --> handleSvgClick(diagramSelection).tupled,
    inContext { svgParent =>
      def parentSize() = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
      // scale the diagram to fit the parent container whenever the "fit" button is clicked
      fitDiagram
        .sample(svgDiagram)
        .foreach { svgDiagram =>
          val (parentWidth, parentHeight) = parentSize()
          val z = math.min(parentWidth / svgDiagram.origW, parentHeight / svgDiagram.origH)
          val trX = (parentWidth - svgDiagram.origW) / 2
          val trY = (parentHeight - svgDiagram.origH) / 2
          zoomValue.set(z)
          translateXY.set((trX, trY))
          // TODO: is there a way to avoid unsafeWindowOwner here?
        }(unsafeWindowOwner)

      Seq(
        child <-- svgDiagram.map: diagram =>

          val selection = diagramSelection.now()
          diagram.select(selection)
          // remove elements not present in the new diagram (such elements did exist in the previous diagram)
          diagramSelection.remove(selection -- diagram.nodeIds)

          diagram.toLaminar.amend(
            svg.transform <-- translateXY.signal
              .combineWith(zoomValue.signal)
              .map((x, y, z) => s"translate($x $y) scale($z)")
          )
      )
    },
    onWheel --> { wEv =>
      val h = dom.window.innerHeight.max(1)
      if wEv.metaKey then zoomValue.update(_ - wEv.deltaY / h)
      else translateXY.update((x, y) => (x - wEv.deltaX, y - wEv.deltaY))
    }
  )

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
            for pp <- edge.endpointIds do
              val ids = Set(pp._1, pp._2)
              svgDiagram.select(ids)
              diagramSelection.toggle(ids.toSeq*)
          else
            svgDiagram.unselectAll()
            edge.select()
            for pp <- edge.endpointIds do
              val ids = Set(pp._1, pp._2)
              svgDiagram.select(ids)
              diagramSelection.replace(ids.toSeq*)

    case None =>
      svgDiagram.unselectAll()
      diagramSelection.clear()

//private def handleOnMouseOver(diagramSelection: DiagramSelectionOps)(
//    ev:         dom.MouseEvent,
//    svgDiagram: SvgDotDiagram
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
//            svgDiagram.unselectAll()
//            node.select()
//            diagramSelection.replace(node.nodeId)
//
//        case edge: EdgeElement =>
//          if ev.metaKey then
//            edge.toggle()
//            for pp <- edge.endpointIds do
//              val ids = Set(pp._1, pp._2)
//              svgDiagram.select(ids)
//              diagramSelection.toggle(ids.toSeq*)
//          else
//            svgDiagram.unselectAll()
//            edge.select()
//            for pp <- edge.endpointIds do
//              val ids = Set(pp._1, pp._2)
//              svgDiagram.select(ids)
//              diagramSelection.replace(ids.toSeq*)
//
//    case None =>
//      svgDiagram.unselectAll()
//      diagramSelection.clear()
