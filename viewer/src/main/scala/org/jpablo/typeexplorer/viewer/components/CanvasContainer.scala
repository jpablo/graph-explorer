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
  /*
  Notes on Miro:
  - zoom: [25%, 400%]
  - zoom control: Cmd + vertical wheel
   * */

  // viewBox attribute
  val xy = Var((0.0, 0.0))
  val h = Var(0.0)
  div(
    idAttr := "canvas-container",
    onClick.preventDefault.compose(_.withCurrentValueOf(svgDiagram)) --> handleSvgClick(diagramSelection).tupled,
    inContext { canvasContainer =>
      def parentSize() = (canvasContainer.ref.offsetWidth, canvasContainer.ref.offsetHeight)
      // scale the diagram to fit the parent container whenever the "fit" button is clicked
      fitDiagram
        .sample(svgDiagram)
        .foreach { diagram =>
//          val (parentWidth, parentHeight) = parentSize()
//          val z = math.min(parentWidth / diagram.origW, parentHeight / diagram.origH)
//          zoomValue.set(z)
          // TODO: is there a way to avoid unsafeWindowOwner here?
        }(unsafeWindowOwner)

      Seq(
        child <-- svgDiagram.map: diagram =>

          val selection = diagramSelection.now()
          diagram.select(selection)
          // remove elements not present in the new diagram (such elements did exist in the previous diagram)
          diagramSelection.remove(selection -- diagram.nodeIds)

          diagram.toLaminar.amend(
            svg.transform <-- xy.signal
              .combineWith(h.signal)
              .map { (x, y, h)  =>
                val s = 1 + h / diagram.origH.max(1)
                s"translate(${-x} ${-y}) scale($s $s)"
              },
            onWheel --> { ev =>
              if ev.metaKey then h.update(_ + ev.deltaY)
              else xy.update((x, y) => (x + ev.deltaX, y + ev.deltaY))
            }
          )
      )
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
      .path
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
