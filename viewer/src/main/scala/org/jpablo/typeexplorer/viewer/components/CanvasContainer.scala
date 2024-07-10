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
          // parentWidth = 1302
          // parentHeight = 805
          // svgDiagram.origW = 868
          // svgDiagram.origH = 1424
          val z = math.min(parentWidth / svgDiagram.origW, parentHeight / svgDiagram.origH)
//          val z = math.min(1302 / 868, 805 / 1424)
//          val z = math.min(1.5, 0.5653089887640449)
//          val z = 0.5653089887640449

          val trX = (parentWidth - svgDiagram.origW) / 2
          val trY = (parentHeight - svgDiagram.origH) / 2

          zoomValue.set(z)
          // center
          val w = svgDiagram.origW * z // 490.688202247191
          val h = svgDiagram.origH * z // parentHeight == 805

          val br = svgDiagram.ref.getBoundingClientRect()
          // br.bottom : 1428
          // br.height : 1424
          // br.left : 388
          // br.right : 1256
          // br.top : 4
          // br.width : 868
          // br.x : 388
          // br.y : 4

          // mid point _dimensions_ of svg diagram
          val midX = br.width / 2    // 434
          val midY = br.height / 2   // 712

          val left1 = br.left - midX * z
          // val left1 = 388 - 434 * z
          // val left1 = 388 - 245.3441011235955
          // val left1 = 142.6558988764045
          val bottom1 = br.bottom - midY * z

          val x1 = (parentWidth - (w + midX * z)) / 2
          val y1 = (parentHeight - (h + midY * z)) / 2

          println(s"z: $z")
          dom.console.log(br)
          println(s"parent size: ${(parentWidth, parentHeight)}")
          println(s"midpoint: ($midX, $midY)")
          println(s"new svg size: ($w, $h)")
          println(s"new (x, y): ($x1, $y1)")
          // parent size: (1302,805)
          // midpoint: (434, 712)
          // new svg size: (490.688202247191, 804.9999999999999)
          // new (x, y): (282.98384831460675, -201.2499999999999)
          // translate(0 0) scale(0.5653089887640449)
          // translate(-282.98384831460675 201.2499999999999) scale(0.5653089887640449)

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
              .map: (x, y, z) =>
                println(s"translate(${x} ${y}) scale($z)")
                s"translate(${x} ${y}) scale($z)"
          )
      )
    },
    onWheel --> { wEv =>
      if wEv.metaKey then zoomValue.update(_ + wEv.deltaY / dom.window.innerHeight.max(1))
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
