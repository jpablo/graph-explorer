package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.domUtils.{SvgUtils, querySelectorT}
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.state.mouseActions.*
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.{DiagramSelectionOps, UIState}
import org.jpablo.graphexplorer.viewer.utils.{BBox, MouseActionRect}

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
// rawSvg is the SVG element as it comes from DOT
def SvgCanvas(
    rawSvg:      dom.svg.SVG,
    transform:   Signal[String],
    viewerOps:   DiagramSelectionOps & AddNewArrowOps & MoveArrowEndpointOps & ExtendSelectionOps & UIState,
    mouseAction: MouseActionVar
): ReactiveSvgElement[dom.svg.SVG] =
  import viewerOps.selection

  val firstGroup: dom.svg.G =
    rawSvg.querySelectorT("g").getOrElse(dom.document.createElement("g").asInstanceOf[dom.svg.G])

  // --------------------------------------------------------
  // The top level <svg> element
  // --------------------------------------------------------
  val viewBox = rawSvg.viewBox.baseVal
  val tr      = getTranslate(firstGroup)
  // TODO: Find a way to center the SVG content in the viewBox
  val magicX = 0.4
  val magicY = -0.4
  val bbox   = BBox(viewBox.x - tr.x + magicX, viewBox.y - tr.y + magicY, viewBox.width, viewBox.height)

  emptySvg(
    viewBox = bbox,
    foreignSvgElement(svg.g, firstGroup)
  ).amendThis { topLevelSvg =>
    val selectionGroups =
      selection.signal
        .scanLeft(x => (ElementIds(), x)):
          case ((_, curr), next) => (curr, next)
        .map: (curr, next) =>
          val toUnselect = curr.filter(id => !next.contains(id))
          val toSelect   = next.filter(id => !curr.contains(id))
          (SelectableElement.query(topLevelSvg.ref, toUnselect), SelectableElement.query(topLevelSvg.ref, toSelect))

    val singleSelection =
      selection.signal.map: selected =>
        if selected.size == 1 then SelectableElement.query(topLevelSvg.ref, selected).headOption else None

    val allSelectable =
      SelectableElement.findAll(topLevelSvg.ref)

    val firstGroup: dom.svg.G =
      topLevelSvg.ref.querySelectorT("g").getOrElse(throw Exception("No <g> element found in the SVG"))

    Seq(
      // mouseAction.signal.map(_.name).distinct --> { c => pprint.log(c) },
      transform --> { tr => firstGroup.setAttribute("transform", tr) },
      // --------------------------------------------------------
      // Mouse events
      // --------------------------------------------------------
      // 1. Drawing a selecting rectangle starts here. Other actions start in their respective elements.
      onMouseDown.filter(leftButton).map(clientCoords) --> { (pos, shift) =>
        mouseAction.start(ExtendSelectionAction(MouseActionRect(pos, pos, shift)))
      },
      // 2. Any ongoing action is updated here (i.e., mouse position)
      onMouseMove.filter(leftButtonMoved).map(clientCoords) --> mouseAction.updateEndpoint.tupled,
      // 3. Any ongoing action ends here
      onMouseUp.filter(leftButton)(_.withCurrentValueOf(mouseAction.signal)) --> { (ev, previousAction) =>
        mouseAction.inactive()
        previousAction match
          case a: AddNewArrowAction       => viewerOps.handleAddNewArrowMouseUp(ev, a)
          case a: MoveArrowEndpointAction => viewerOps.handleMoveArrowStartMouseUp(ev, a)
          case a: ExtendSelectionAction   =>
          case Inactive                   =>
      },
      // --------------------------------------------------------
      // derived events
      // --------------------------------------------------------
      // selection rectangle
      child.maybe <--
        mouseAction.signal.map:
          case a: ExtendSelectionAction => Some(viewerOps.DrawSelectionRect(topLevelSvg.ref, a))
          case _                        => None,
      // controls to initiate mouse actions
      singleSelection.combineWith(mouseAction.signal) --> viewerOps.handleNewArrowControls(firstGroup).tupled,
      singleSelection.combineWith(mouseAction.signal) --> viewerOps.handleArrowEndpointControl(firstGroup).tupled,
      // dynamic arrow that follows the pointer
      mouseAction.signal --> { action =>
        // TODO: update the coordinates instead of recreating the arrow
        firstGroup.querySelectorAll("g#dragging-arrow-group").foreach(_.remove())
        action match
          case a: AddNewArrowAction if !a.rect.isEmpty       => viewerOps.addArrowFromSourceToPointer(firstGroup, a)
          case a: MoveArrowEndpointAction if !a.rect.isEmpty => viewerOps.addArrowBetweenPointerAndEndpoint(firstGroup, a)
          case _                                             =>
      },
      // selection changes as a result of ongoing mouse actions
      mouseAction.signal --> {
        case a: ExtendSelectionAction   => viewerOps.onExtendSelectionAction(allSelectable)(a)
        case a: AddNewArrowAction       => viewerOps.onAddNewArrowAction(a)
        case a: MoveArrowEndpointAction => viewerOps.onMoveArrowSourceAction(a)
        case Inactive                   =>
      },
      // --------------------------------------------------------
      //   synchronize svg elements with diagramSelection
      // --------------------------------------------------------
      selectionGroups --> { (toUnselect: Seq[SelectableElement], toSelect: Seq[SelectableElement]) =>
        toUnselect.foreach(_.unselect())
        toSelect.foreach(_.select())
        // select/unselect modify the DOM directly, which seems to make the focus go to the
        // document body. We need the focus back to the canvas container to process handle keys.
        dom.window.requestAnimationFrame(_ => viewerOps.canvasContainerFocus.set(true))
        ()
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
