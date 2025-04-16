package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.mouseActions.*
import org.jpablo.graphexplorer.viewer.utils.BBox

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
// rawSvg is the SVG element as it comes from DOT
def SvgCanvas(
    rawSvg:      dom.svg.SVG,
    transform:   Signal[String],
    viewerOps:   DiagramSelectionOps & AddNewArrowOps & MoveArrowSourceOps & MoveArrowTargetOps & ExtendSelectionOps,
    mouseAction: MouseActionVar
): ReactiveSvgElement[dom.svg.SVG] =
  import viewerOps.selection

  val firstGroup: dom.svg.G =
    val g0 = rawSvg.querySelector("g")
    (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

  // --------------------------------------------------------
  // The top level <svg> element
  // --------------------------------------------------------
  val viewBox = rawSvg.viewBox.baseVal
  val tr      = getTranslate(firstGroup)
  val bbox    = BBox(viewBox.x - tr.x, viewBox.y - tr.y, viewBox.width, viewBox.height)

  emptySvg(
    viewBox = bbox,
    // -------------------------
    // The top level <g> element
    // -------------------------
    foreignSvgElement(svg.g, firstGroup)
      .amendThis: group =>
        val singleSelection =
          selection.signal.map: selected =>
            if selected.size == 1 then SelectableElement.query(group.ref, selected).headOption else None

        val allSelectable =
          SelectableElement.findAll(group.ref)

        Seq(
          svg.transform <-- transform,
          // "buttons" to initiate mouse actions
          child.maybe <-- singleSelection.map(_.flatMap(viewerOps.buildNewArrowButton)),
          children <-- singleSelection.map {
            _.toSeq.flatMap:
              case edge: EdgeElement =>
                Seq(
                  viewerOps.buildArrowEndpointButton(edge),
                  viewerOps.buildArrowTargetEndpointButton(edge)
                )
              case _ => Seq.empty
          },
          // visual feedback for ongoing mouse actions
          child.maybe <--
            mouseAction.signal.map:
              case a: ExtendSelectionAction => None
              case a: AddNewArrowAction     => ArrowFromSourceToPointer(a, group.ref)
              case a: MoveArrowSourceAction => viewerOps.ArrowFromPointerToTarget(a, group.ref)
              case a: MoveArrowTargetAction => viewerOps.arrowFromSourceToPointer(a, group.ref)
              case Inactive                 => None,
          // selection changes as a result of ongoing mouse actions
          mouseAction.signal --> { action =>
            action match
              case a: ExtendSelectionAction => viewerOps.onExtendSelectionAction(allSelectable)(a)
              case a: AddNewArrowAction     => viewerOps.onAddNewArrowAction(a)
              case a: MoveArrowSourceAction => viewerOps.onMoveArrowSourceAction(a)
              case a: MoveArrowTargetAction => viewerOps.onMoveArrowTargetAction(a)
              case Inactive                 => ()
          }
        )
  ).amendThis { topLevelSvg =>
    val selectionGroups =
      selection.signal
        .scanLeft(x => (x, x)):
          case ((_, curr), next) => (curr, next)
        .map: (curr, next) =>
          val toUnselect = curr.filter(id => !next.contains(id))
          val toSelect   = next.filter(id => !curr.contains(id))
          (SelectableElement.query(topLevelSvg.ref, toUnselect), SelectableElement.query(topLevelSvg.ref, toSelect))

    Seq(
      child.maybe <--
        mouseAction.signal.map:
          case a: ExtendSelectionAction => Some(viewerOps.DrawSelectionRect(topLevelSvg.ref, a))
          case _                        => None,
      // --------------------------------------------------------
      //   synchronize svg elements with diagramSelection
      // --------------------------------------------------------
      selectionGroups --> { (toUnselect: Seq[SelectableElement], toSelect: Seq[SelectableElement]) =>
        toUnselect.foreach(_.unselect())
        toSelect.foreach(_.select())
      }
    )
  }
end SvgCanvas

/** Creates a standalone SVG element with the given viewBox
  */
def emptySvg(viewBox: BBox, mods: SvgMods*): ReactiveSvgElement[dom.svg.SVG] =
  svg.svg(
    svg.xmlns      := "http://www.w3.org/2000/svg",
    svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
    svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
    svg.cls        := "graphviz no-text-select",
    mods
  )
