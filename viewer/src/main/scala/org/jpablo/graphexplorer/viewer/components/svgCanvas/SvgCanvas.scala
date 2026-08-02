package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorT
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.mouseActions.*
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.{DiagramSelectionOps, RecordCellOps, SelectedCell, UIState}
import org.jpablo.graphexplorer.viewer.utils.{BBox, MouseActionRect}

import scala.scalajs.js
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
// rawSvg is the SVG element as it comes from DOT or Mermaid
def SvgCanvas(
    rawSvg:        ReactiveSvgElement[dom.svg.SVG],
    transform:     Signal[String],
    viewerOps:     DiagramSelectionOps & RecordCellOps & AddNewArrowOps & MoveArrowEndpointOps & ExtendSelectionOps & UIState,
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
    onToggleCollapsed: org.jpablo.graphexplorer.viewer.models.NodeId => Unit = _ => (),
    /** The fold badge's action on an EXPANDED group (collapse it). */
    onCollapseGroup: org.jpablo.graphexplorer.viewer.models.GroupId => Unit = _ => (),
    /** Runs once the svg is MOUNTED (viewport anchoring, transitions — anything
      * needing real client geometry). */
    onRendered: dom.svg.SVG => Unit = _ => ()
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

  // --- Helpers for click / double-click logic ---
  // Defined locally within SvgCanvas

  /** The record cell under the pointer (model hit-test via RecordCellOps). */
  def cellUnderPointer(nodeId: NodeId, ev: dom.MouseEvent) =
    viewerOps.recordCells.cellPathAtClientPoint(nodeId, ev.clientX, ev.clientY)

  /** The record cell an arrow drag is hovering: node under the point, then its cell. */
  def dropCellAt(end: org.jpablo.graphexplorer.viewer.utils.ClientPoint): Option[(NodeId, List[Int])] =
    findClosestElementId(dom.document.elementsFromPoint(end.x, end.y), strategy, Some(strategy.nodeSelector))
      .flatMap(_.asNodeId)
      .flatMap(nodeId => viewerOps.recordCells.cellPathAtClientPoint(nodeId, end.x, end.y).map(nodeId -> _))

  def handleElementClick(ev: dom.MouseEvent, now: Double, currentElementIdO: Option[ElementId]): Boolean =
    val isRepeatClick = currentElementIdO.exists: id =>
      lastClickedElementId.contains(id) && (now - lastClickTimestamp) < doubleClickThreshold
    val handled = currentElementIdO match
      case Some(nodeId: NodeId)
          if viewerOps.recordCells.isCellEditable(nodeId) &&
            viewerOps.selection.now().size == 1 &&
            viewerOps.selection.now().contains(nodeId) =>
        // A click on the already-selected record/table descends to the CELL
        // under the pointer (draw.io's two-level model); a fast second click
        // edits it.
        cellUnderPointer(nodeId, ev) match
          case Some(path) =>
            ev.preventDefault()
            ev.stopPropagation()
            viewerOps.recordCells.selectCell(nodeId, path)
            if isRepeatClick then viewerOps.recordCells.editCell(SelectedCell(nodeId, path))
            true
          case None => false
      case Some(currentElementId) if isRepeatClick =>
        // Double click detected on a selectable element
        ev.preventDefault()
        ev.stopPropagation()
        // Ensure the element is selected before editing (in case the first click didn't select it)
        viewerOps.selection.set(ElementIds.from(currentElementId))
        viewerOps.selection.editSelectedLabel()
        true
      case _ => false
    // Bookkeeping for the next click: a consumed double-click resets (so a triple
    // click does not re-open the editor); anything else arms the element clicked.
    currentElementIdO match
      case Some(id) if !(handled && isRepeatClick) =>
        lastClickTimestamp = now
        lastClickedElementId = Some(id)
      case _ =>
        lastClickTimestamp = 0.0
        lastClickedElementId = None
    handled
  end handleElementClick

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

  // Fires when a layout transition lands (LayoutTransition.finish dispatches it
  // on this svg). Overlay controls built at mount were positioned at frame 0 —
  // the OLD layout, by the transition's pixel-exact contract — so they must be
  // rebuilt from the settled geometry.
  val layoutSettled = EventBus[Unit]()

  def refreshOverlayControls(elem: Option[SelectableElement], action: MouseAction): Unit =
    viewerOps.handleNewArrowControls(mainGroup, elem, action)
    viewerOps.handleArrowEndpointControl(mainGroup, elem, action, edgePositions)

  /** Everything whose size is a SCREEN quantity: the controls, which hold a
    * constant size, and the selection decorations, which hold a constant
    * relationship to the object they mark. Both go stale the moment the canvas
    * transform moves. */
  def refitChrome(): Unit =
    ScreenConstant.refitAll(rawSvg.ref)
    SelectionCasing.refit(mainGroup)

  // render all selected elements the first time
  rawSvg
    .amend {
      Seq(
        svg.viewBox   := s"${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}",
        svg.width     := null,
        svg.height    := null,
        svg.className := "graphviz",
        transform --> { tr =>
          mainGroup.setAttribute(svg.transform.name, tr)
          // Every control holds its SCREEN size across pan/zoom (none of them
          // rebuild here — a re-fit keeps listeners and drag state intact).
          refitChrome()
        },
        // A window resize rescales the viewBox→client mapping with no transform
        // event — the third way the CTM moves under the controls' feet.
        windowEvents(_.onResize) --> { _ => refitChrome() },
        // Post-mount work needing real geometry (badges: getBBox; onRendered:
        // client rects). Registered AFTER the transform binder on purpose —
        // mount runs modifiers in order, and onRendered's screen measurements
        // are garbage until the pan/zoom transform has been applied.
        onMountCallback { _ =>
          // Registered BEFORE onRendered: the transition starts inside onRendered,
          // so the listener must already exist when finish() eventually fires.
          rawSvg.ref.addEventListener(LayoutTransition.transitionEndEvent, (_: dom.Event) => layoutSettled.emit(()))
          CountBadges.decorate(mainGroup, strategy, concealedCounts, onToggleConcealed, collapsedCounts, onToggleCollapsed, onCollapseGroup)
          // Delegated from the svg, so once is enough however often the layout
          // re-renders underneath it.
          CountBadges.installHover(mainGroup, strategy)
          onRendered(rawSvg.ref)
        },
        // --------------------------------------------------------
        // Mouse events
        // --------------------------------------------------------
        // 1. Drawing a selecting rectangle (OR dbl-click) starts here. Other actions start in their respective elements.
        onMouseDown.filter(leftButton).map(ev => (ev, clientCoords(ev))) --> { case (ev, (pos, shift)) =>
          val handled = handleElementClick(
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
          refreshOverlayControls(elem, action)
        },
        // ...and once more when a layout transition settles, from final geometry.
        // Badges too: they are decorated at frame 0, when the viewBox still shows
        // the OLD frame — the transition tweens the viewBox, which changes the CTM
        // scale without ever touching the transform signal.
        layoutSettled.events.sample(singleSelection.combineWith(mouseAction.signal)) --> {
          (elem: Option[SelectableElement], action: MouseAction) =>
            refreshOverlayControls(elem, action)
            refitChrome()
        },
        // --------------------------------------------------------
        // record CELL selection (one level below the element selection)
        // --------------------------------------------------------
        // The overlay draws from model geometry; it re-renders on every cell
        // change (the signal also fires once on bind, covering mount).
        viewerOps.selectedCellV.signal.distinct --> { cellOpt =>
          RecordCellOverlay.refresh(mainGroup, strategy, cellOpt, viewerOps.recordCells.cellBoxes)
        },
        // ...and once more when a layout transition settles (frame-0 geometry
        // may be the OLD layout, same as the badges).
        layoutSettled.events.sample(viewerOps.selectedCellV.signal) --> { cellOpt =>
          RecordCellOverlay.refresh(mainGroup, strategy, cellOpt, viewerOps.recordCells.cellBoxes)
        },
        // The cell selection exists only while its record stays the single
        // selected element. (The fold badges used to follow the selection from
        // here; they follow the pointer now — see CountBadges.installHover.)
        selection.signal --> { sel =>
          viewerOps.recordCells.pruneAgainstSelection(sel)
          SelectionZOrder.reflectSelection(mainGroup, strategy, sel)
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
          // While the drag hovers a record, outline the CELL under the pointer —
          // the exact attach target (its port, minted on drop).
          val dropCell = action match
            case a: AddNewArrowAction if !a.rect.isEmpty       => dropCellAt(a.rect.end)
            case a: MoveArrowEndpointAction if !a.rect.isEmpty => dropCellAt(a.rect.end)
            case _                                             => None
          RecordCellOverlay.refreshDropHighlight(mainGroup, strategy, dropCell, viewerOps.recordCells.cellBoxes)
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
          queryElements(selection.now()).foreach(_.select())
          // …and size what select() just made, exactly as the change path does.
          // A re-render (an edit, a layout change) comes through HERE, not
          // through selectionElementChanges — that stream carries CHANGES, and
          // the selection did not change, only the svg under it. Without this
          // the decorations land unsized: a selected edge kept its endpoint
          // disks and its recoloured arrowhead, both driven by signals that
          // re-fire on subscribe, while its casing silently went missing.
          SelectionCasing.refit(mainGroup, force = true),
        // subsequent selection changes don't trigger onMountCallback, so we can be more
        // precise and only select/unselect the elements that actually changed
        selectionElementChanges --> { groups =>
          // This should only happen when the selection groups are non-empty (see dropWhile above)
          groups.toUnselect.foreach { e => e.unselect(); SelectionCasing.clear(e.ref) }
          groups.toSelect.foreach(_.select())
          // The decorations select() just created are sized from the object
          // they mark, at the CURRENT zoom — nothing about that is knowable
          // from inside select().
          SelectionCasing.refit(mainGroup, force = true)
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
