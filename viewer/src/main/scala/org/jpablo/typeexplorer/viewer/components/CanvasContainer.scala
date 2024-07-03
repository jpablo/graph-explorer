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
  val svgSize =
    zoomValue.signal
      .combineWith(svgDiagram)
      .map((z, diagram) => (z * diagram.origW, z * diagram.origH))
  div(
    cls             := "te-parent p-1 z-10",
    backgroundImage := "radial-gradient(oklch(var(--bc)/.2) .5px,oklch(var(--b2)/1) .5px)",
    backgroundSize  := "5px 5px",
    onClick.preventDefault
      .compose(_.withCurrentValueOf(svgDiagram)) --> handleSvgClick(diagramSelection).tupled,
    inContext { svgParent =>
      def parentSizeNow() = (svgParent.ref.offsetWidth, svgParent.ref.offsetHeight)
      // scale the diagram to fit the parent container whenever the "fit" button is clicked
      fitDiagram
        .sample(svgDiagram)
        .foreach { diagram =>
          val (parentWidth, parentHeight) = parentSizeNow()
          val z = math.min(parentWidth / diagram.origW, parentHeight / diagram.origH)
          zoomValue.set(z)
          // TODO: is there a way to avoid unsafeWindowOwner here?
        }(unsafeWindowOwner)

      // Center the diagram *only* when the parent container is wider than the svg diagram.
      // Otherwise the left side of the diagram is not accessible with scroll bars (as they only extend to the right).
      val flexJustification =
        windowEvents(_.onResize).mapToUnit
          .startWith(())
          .combineWith(svgSize)
          .map: (svgWidth, svgHeight) =>
            val (parentWidth, parentHeight) = parentSizeNow()
            Seq(
              if parentWidth < svgWidth then "justify-start" else "justify-center",
              if parentHeight < svgHeight then "items-start" else "items-center"
            )
      Seq(
        cls <-- flexJustification,
        child <-- svgDiagram.map: diagram =>
          val selection /*: Set[GraphSymbol]*/ = diagramSelection.now()
          diagram.select(selection)
          // remove elements not present in the new diagram (such elements did exist in the previous diagram)
          diagramSelection.remove(selection -- diagram.nodeIds)

          diagram.toLaminar.amend(
            svg.width <-- svgSize.map(_._1.toString + "px"),
            svg.height <-- svgSize.map(_._2.toString + "px")
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
