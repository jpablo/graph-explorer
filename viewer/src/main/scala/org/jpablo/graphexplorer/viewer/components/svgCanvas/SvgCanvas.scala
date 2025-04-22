package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
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
    rawSvg:      ReactiveSvgElement[dom.svg.SVG],
    transform:   Signal[String],
    viewerOps:   DiagramSelectionOps & AddNewArrowOps & MoveArrowEndpointOps & ExtendSelectionOps & UIState,
    mouseAction: MouseActionVar
): ReactiveSvgElement[dom.svg.SVG] =
  import viewerOps.selection

  val mainGroup = rawSvg.ref.querySelectorT("g").getOrElse(throw Exception("No <g> element found in the SVG"))
  val tr        = getTranslate(mainGroup)
  val magicX    = 0.4 // TODO: Find a better way to calculate this
  val magicY    = -0.4
  val viewBox   = rawSvg.ref.viewBox.baseVal
  val bbox      = BBox(viewBox.x - tr.x + magicX, viewBox.y - tr.y + magicY, viewBox.width, viewBox.height)

  val selectionGroups =
    selection.signal
      .scanLeft(x => (ElementIds(), x)):
        case ((_, curr), next) => (curr, next)
      .map: (curr, next) =>
        val toUnselect = curr.filter(id => !next.contains(id))
        val toSelect   = next.filter(id => !curr.contains(id))
        (SelectableElement.query(rawSvg.ref, toUnselect), SelectableElement.query(rawSvg.ref, toSelect))

  val singleSelection =
    selection.signal.map: selected =>
      if selected.size == 1 then SelectableElement.query(rawSvg.ref, selected).headOption else None

  val allSelectable =
    SelectableElement.findAll(rawSvg.ref)

  rawSvg
    .amend {
      Seq(
        svg.viewBox   := s"${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}",
        svg.width     := null,
        svg.height    := null,
        svg.className := "graphviz",
        transform --> { tr => mainGroup.setAttribute(svg.transform.name, tr) },
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
            case _                          =>
        },
        // --------------------------------------------------------
        // derived events
        // --------------------------------------------------------
        // selection rectangle
        viewerOps.DrawSelectionRect(rawSvg.ref.getScreenCTM),
        // controls to initiate mouse actions
        singleSelection.combineWith(mouseAction.signal) --> { (elem: Option[SelectableElement], action: MouseAction) =>
          viewerOps.handleNewArrowControls(mainGroup)(elem, action)
          viewerOps.handleArrowEndpointControl(mainGroup)(elem, action)
        },
        // dynamic arrow that follows the pointer
        mouseAction.signal --> { action =>
          // TODO: update the coordinates instead of recreating the arrow
          mainGroup.querySelectorAll("g#dragging-arrow-group").foreach(_.remove())
          action match
            case a: AddNewArrowAction if !a.rect.isEmpty       => viewerOps.addArrowFromSourceToPointer(mainGroup, a)
            case a: MoveArrowEndpointAction if !a.rect.isEmpty => viewerOps.addArrowBetweenPointerAndEndpoint(mainGroup, a)
            case _                                             =>
        },
        // selection changes as a result of ongoing mouse actions
        mouseAction.signal --> {
          case a: ExtendSelectionAction   => viewerOps.onExtendSelectionAction(allSelectable)(a)
          case a: AddNewArrowAction       => viewerOps.onAddNewArrowAction(a)
          case a: MoveArrowEndpointAction => viewerOps.onMoveArrowSourceAction(a)
          case _                          =>
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
