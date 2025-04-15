package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.{
  AddNewArrowAction,
  ExtendSelectionAction,
  Inactive,
  MoveArrowStartAction
}
import org.jpablo.graphexplorer.viewer.state.mouseActions.{AddNewArrowOps, ExtendSelectionOps, MouseActionVar, MoveArrowStartOps}
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint}

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  extension (e: dom.MouseEvent)
    def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
    def leftButton      = e.button == 0
    def leftButtonMoved = e.buttons == 1

  // rawSvg is the SVG element as it comes from DOT
  def apply(
      rawSvg:      dom.svg.SVG,
      transform:   Signal[String],
      viewerOps:   DiagramSelectionOps & AddNewArrowOps & MoveArrowStartOps & ExtendSelectionOps,
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
            child.maybe <-- singleSelection.map(_.flatMap(viewerOps.buildArrowEndpointButton)),
            // visual feedback for ongoing mouse actions
            child.maybe <-- viewerOps.buildDraggingArrow(group.ref),
            child.maybe <-- viewerOps.buildArrowWithEndpoint(group.ref),
            // selection changes as a result of ongoing mouse actions
            mouseAction.signal --> { action =>
              action match
                case a: ExtendSelectionAction => viewerOps.onExtendSelectionAction(allSelectable)(a)
                case a: AddNewArrowAction     => viewerOps.onAddNewArrowAction(a)
                case a: MoveArrowStartAction  => viewerOps.onMoveArrowStart(a)
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
        child.maybe <-- viewerOps.buildDrawSelectionRect(topLevelSvg.ref),
        // --------------------------------------------------------
        //   synchronize svg elements with diagramSelection
        // --------------------------------------------------------
        selectionGroups --> { (toUnselect: Seq[SelectableElement], toSelect: Seq[SelectableElement]) =>
          toUnselect.foreach(_.unselect())
          toSelect.foreach(_.select())
        }
      )
    }
  end apply

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
