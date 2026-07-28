package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorT
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds}
import org.jpablo.graphexplorer.viewer.state.mouseActions.*
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.{DiagramSelectionOps, UIState}
import org.jpablo.graphexplorer.viewer.utils.{BBox, MouseActionRect}

import scala.scalajs.js
//import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
// rawSvg is the SVG element as it comes from DOT or Mermaid
def SvgCanvas(
    rawSvg:        ReactiveSvgElement[dom.svg.SVG],
    transform:     Signal[String],
    viewerOps:     DiagramSelectionOps & AddNewArrowOps & MoveArrowEndpointOps & ExtendSelectionOps & UIState,
    mouseAction:   MouseActionVar,
    edgePositions: Map[String, ArrowPosition],
    strategy:      SelectableElementStrategy,
    /** Concealed-neighbor counts per visible node (successors, predecessors) —
      * the expand-badge model — and the badge click's action. */
    concealedCounts:   Map[org.jpablo.graphexplorer.viewer.models.NodeId, (Int, Int)] = Map.empty,
    onToggleConcealed: (org.jpablo.graphexplorer.viewer.models.NodeId, Boolean) => Unit = (_, _) => (),
    /** Member counts per collapsed-group box (keyed by proxy id) — the
      * collapse-badge model — and the badge click's action (expand). */
    collapsedCounts:   Map[org.jpablo.graphexplorer.viewer.models.NodeId, Int] = Map.empty,
    onToggleCollapsed: org.jpablo.graphexplorer.viewer.models.NodeId => Unit = _ => ()
): ReactiveSvgElement[dom.svg.SVG] =
  import viewerOps.selection

  val mainGroup = rawSvg.ref.querySelectorT("g").getOrElse(throw Exception("No <g> element found in the SVG"))
  val tr        = getTranslate(mainGroup)
  val magicX    = 0.4 // TODO: Find a better way to calculate this
  val magicY    = -0.4
  val viewBox   = rawSvg.ref.viewBox.baseVal
  val bbox      = BBox(viewBox.x - tr.x + magicX, viewBox.y - tr.y + magicY, viewBox.width, viewBox.height)

  // State for double-click detection
  val doubleClickThreshold                    = 300.0 // milliseconds
  var lastClickTimestamp: Double              = 0.0
  var lastClickedElementId: Option[ElementId] = None

  // --- Helper for double-click logic ---
  // Defined locally within SvgCanvas
  def handleDoubleClick(ev: dom.MouseEvent, now: Double, currentElementIdO: Option[ElementId]): Boolean =
    currentElementIdO match
      case Some(currentElementId) =>
        val previousTimestamp = lastClickTimestamp
        val previousElementId = lastClickedElementId
        if previousElementId.contains(currentElementId) && (now - previousTimestamp) < doubleClickThreshold then
          // Double click detected on a selectable element
          ev.preventDefault()
          ev.stopPropagation()
          // Ensure the element is selected before editing (in case the first click didn't select it)
          viewerOps.selection.set(ElementIds.from(currentElementId))
          viewerOps.selection.editSelectedLabel()
          // Reset the double-click state immediately
          lastClickTimestamp = 0.0
          lastClickedElementId = None
          true // double-click was handled
        else
          // Single click on an element, update state for a potential next click
          lastClickTimestamp = now
          lastClickedElementId = Some(currentElementId)
          false // double-click was not handled

      case None =>
        lastClickTimestamp = 0.0
        lastClickedElementId = None
        false
  end handleDoubleClick

  def queryElements(elems: ElementIds) =
    SelectableElement.query(rawSvg.ref, elems, strategy)

  val selectionElementChanges =
    selection.selectionChanges
      .dropWhile: groups =>
        groups.toSelect.isEmpty && groups.toUnselect.isEmpty
      .map: groups =>
        (
          toUnselect = queryElements(groups.toUnselect),
          toSelect = queryElements(groups.toSelect)
        )

  val singleSelection =
    selection.signal.map: selected =>
      if selected.size == 1 then queryElements(selected).headOption else None

  val allSelectable =
    SelectableElement.findAll(rawSvg.ref, strategy)

  // render all selected elements the first time
  rawSvg
    .amend {
      Seq(
        // Count badges need real geometry: getBBox only works once mounted.
        onMountCallback(_ =>
          CountBadges.decorate(rawSvg.ref, strategy, concealedCounts, onToggleConcealed, collapsedCounts, onToggleCollapsed)
        ),
        svg.viewBox   := s"${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}",
        svg.width     := null,
        svg.height    := null,
        svg.className := "graphviz",
        transform --> { tr => mainGroup.setAttribute(svg.transform.name, tr) },
        // --------------------------------------------------------
        // Mouse events
        // --------------------------------------------------------
        // 1. Drawing a selecting rectangle (OR dbl-click) starts here. Other actions start in their respective elements.
        onMouseDown.filter(leftButton).map(ev => (ev, clientCoords(ev))) --> { case (ev, (pos, shift)) =>
          val handled = handleDoubleClick(
            ev,
            js.Date.now(),
            findClosestElementId(js.Array(ev.target.asInstanceOf[dom.Element]), strategy = strategy)
          )
          if !handled then
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
        // controls to initiate mouse actions:
        // a) new arrow controls
        // b) arrow endpoint controls
        // c) for the "selection" action, the whole canvas is the "control"
        singleSelection.combineWith(mouseAction.signal) --> { (elem: Option[SelectableElement], action: MouseAction) =>
          viewerOps.handleNewArrowControls(mainGroup, elem, action)
          viewerOps.handleArrowEndpointControl(mainGroup, elem, action, edgePositions)
        },
        // UI elements reflecting the current mouse action
        viewerOps.SelectionRect(rawSvg.ref.getScreenCTM),
        // dynamic arrow that follows the pointer when creating a new arrow or moving an arrow endpoint
        mouseAction.signal --> { action =>
          // TODO: update the coordinates instead of recreating the arrow
          mainGroup.querySelectorAll("g#dragging-arrow-group").foreach(_.remove())
          action match
            case a: AddNewArrowAction if !a.rect.isEmpty       => viewerOps.addArrowFromSourceToPointer(mainGroup, a)
            case a: MoveArrowEndpointAction if !a.rect.isEmpty => viewerOps.addArrowBetweenPointerAndEndpoint(mainGroup, a)
            case _                                             =>
        },
        // Updates selection as a result of ongoing mouse actions
        mouseAction.signal --> {
          case a: ExtendSelectionAction =>
            // This makes elements selected as the mouse is moving, which is convenient but should be optimized
            // TODO: optimize this
            viewerOps.onExtendSelectionAction(allSelectable)(a)
          case a: AddNewArrowAction       => viewerOps.onAddNewArrowAction(a)
          case a: MoveArrowEndpointAction => viewerOps.onMoveArrowSourceAction(a)
          case _                          =>
        },
        // --------------------------------------------------------
        //   synchronize svg elements with diagramSelection
        // --------------------------------------------------------
        // After mounting we just render the already selected elements
        // this happens when the diagram is changed and the selection is not empty
        onMountCallback: ctx =>
          queryElements(selection.now()).foreach(_.select()),
        // subsequent selection changes don't trigger onMountCallback, so we can be more
        // precise and only select/unselect the elements that actually changed
        selectionElementChanges --> { groups =>
          // This should only happen when the selection groups are non-empty (see dropWhile above)
          groups.toUnselect.foreach(_.unselect())
          groups.toSelect.foreach(_.select())
          // select/unselect modify the DOM directly, which seems to make the focus go to the
          // document body. We need the focus back to the canvas container to process handle keys —
          // but NOT while the user is typing in an editable element (e.g. the source editor):
          // selection can change from a background re-parse, and stealing focus there routes
          // subsequent keystrokes to the canvas shortcuts ('n' adds a node, Backspace deletes).
          dom.window.requestAnimationFrame { _ =>
            if !isTextEditingActive() then viewerOps.canvasContainerFocus.emit(true)
          }
        }
      )
    }
end SvgCanvas

/** True while an editable element (input/textarea/contenteditable, incl. the CodeMirror
  * source editor) has keyboard focus — used to avoid stealing focus to the canvas.
  */
private def isTextEditingActive(): Boolean =
  Option(dom.document.activeElement).exists { active =>
    val tag = active.tagName.toUpperCase
    tag == "INPUT" || tag == "TEXTAREA" ||
    active.asInstanceOf[dom.html.Element].isContentEditable ||
    active.closest(".cm-editor") != null
  }
