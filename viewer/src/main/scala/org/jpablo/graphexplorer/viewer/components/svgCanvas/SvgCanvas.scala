package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.gxcore.layout.BendPath
import org.jpablo.graphexplorer.gxcore.model.LayoutPoint
import org.jpablo.graphexplorer.viewer.backends.graphviz.BendRender
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.mouseActions.*
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.{CanvasTransform, DiagramSelectionOps, ManualLayoutOps, RecordCellOps, SelectedCell, UIState}
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint, MouseActionRect}

import scala.scalajs.js
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
// rawSvg is the SVG element as it comes from DOT or Mermaid
def SvgCanvas(
    rawSvg:        ReactiveSvgElement[dom.svg.SVG],
    transform:     Signal[CanvasTransform],
    viewerOps:     DiagramSelectionOps & RecordCellOps & AddNewArrowOps & MoveArrowEndpointOps & ExtendSelectionOps & UIState &
      ManualLayoutOps,
    mouseAction:   MouseActionVar,
    edgePositions: Map[String, ArrowPosition],
    strategy:      SelectableElementStrategy,
    /** The center and the size of each drawn node (see `SvgWithPositions`). A bend drag ends an arrow on the box of a
      * node with no shape in the SVG. */
    nodePositions: Map[String, Point] = Map.empty,
    nodeSizes:     Map[String, (Double, Double)] = Map.empty,
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
    /** The action of a click on a link icon: open the URL (see `ViewerState.openLink`). */
    openLink: String => Unit = url => org.jpablo.graphexplorer.viewer.desktop.ExternalLink.open(url),
    /** Runs once the svg is MOUNTED (viewport anchoring, transitions — anything
      * needing real client geometry). */
    onRendered: dom.svg.SVG => Unit = _ => ()
): ReactiveSvgElement[dom.svg.SVG] =
  import viewerOps.selection

  val mainGroup = CanvasRoot.mainGroup(rawSvg.ref)
  val tr        = getTranslate(mainGroup)
  val magicX    = 0.4 // TODO: Find a better way to calculate this
  val magicY    = -0.4
  val viewBox   = rawSvg.ref.viewBox.baseVal
  val bbox      = BBox(viewBox.x - tr.x + magicX, viewBox.y - tr.y + magicY, viewBox.width, viewBox.height)
  // The zoom scales the drawing around the center of the viewBox below.
  CanvasRoot.centerScaleOrigin(mainGroup, bbox)

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

  def handleElementClick(ev: dom.MouseEvent, now: Double, currentElementIdO: Option[ElementId]): ClickResult =
    val isRepeatClick = currentElementIdO.exists: id =>
      lastClickedElementId.contains(id) && (now - lastClickTimestamp) < doubleClickThreshold
    val result: ClickResult = currentElementIdO match
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
            if isRepeatClick then
              viewerOps.recordCells.editCell(SelectedCell(nodeId, path))
              ClickResult.Edited
            else ClickResult.CellSelected
          case None => ClickResult.NotHandled
      case Some(currentElementId) if isRepeatClick =>
        // Double click detected on a selectable element
        ev.preventDefault()
        ev.stopPropagation()
        // Ensure the element is selected before editing (in case the first click didn't select it)
        viewerOps.selection.set(ElementIds.from(currentElementId))
        viewerOps.selection.editSelectedLabel()
        ClickResult.Edited
      case _ => ClickResult.NotHandled
    // Bookkeeping for the next click: a consumed double-click resets (so a triple
    // click does not re-open the editor); anything else arms the element clicked.
    currentElementIdO match
      case Some(id) if !(result != ClickResult.NotHandled && isRepeatClick) =>
        lastClickTimestamp = now
        lastClickedElementId = Some(id)
      case _ => forgetClick()
    result
  end handleElementClick

  /** The next press is not the second click of a double-click. */
  def forgetClick(): Unit =
    lastClickTimestamp = 0.0
    lastClickedElementId = None

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

  // --- The drag of nodes (manual layout, ADR 0004) ---

  /** The look of the drag in flight, if any (see `NodeDrag`). */
  var nodeDrag = Option.empty[NodeDrag]

  /** The look of the drag of a bend in flight, if any (see `BendControls.BendDrag`). */
  var bendDrag = Option.empty[BendControls.BendDrag]

  /** The node of the full graph under the target of a press. A collapsed group box is not a node of the full graph,
    * so a press on it keeps its old meaning. The search starts at the target itself: the label of a Mermaid node is
    * HTML in a `foreignObject`, and a search of svg elements only does not find it.
    */
  def pressedNode(target: dom.EventTarget): Option[NodeId] =
    target match
      case el: dom.Element =>
        Option(el.closest(strategy.nodeSelector))
          .flatMap(SelectableElement.fromDomElement(_, strategy))
          .flatMap(_.nodeId)
          .filter(viewerOps.isGraphNodeNow)
      case _ => None

  /** A click on the node `id`: the selection rule of a click (Shift adds or removes the node). */
  def clickOn(id: NodeId, shift: Boolean): Unit =
    viewerOps.selection.updateSelectionStatus(id)(shift)

  /** A move of the pointer during a press on a node. The action changes only at a change of phase: a move of a drag
    * writes the svg directly, and the signals of the canvas do not run for each move.
    */
  def onNodePressMove(a: MoveNodesAction, end: ClientPoint, alt: Boolean): Unit =
    a.phase match
      case NodePress.Pending if NodePress.isDrag(a.rect.update(end, a.rect.shift)) =>
        // The press becomes a drag. It is not the first click of a double-click.
        forgetClick()
        val visible = viewerOps.drawnArrowEndsNow()
        val drag =
          viewerOps.startNodeDrag(a.pressed).flatMap(NodeDrag.begin(rawSvg.ref, mainGroup, strategy, _, visible))
        nodeDrag = drag
        drag.foreach(_.update(end.x - a.rect.start.x, end.y - a.rect.start.y, alt))
        val phase = if drag.isDefined then NodePress.Dragging else NodePress.Inert
        mouseAction.start(a.copy(rect = a.rect.update(end, a.rect.shift), alt = alt, phase = phase))
      case NodePress.Dragging =>
        nodeDrag.foreach(_.update(end.x - a.rect.start.x, end.y - a.rect.start.y, alt))
      case _ => ()

  /** The release of a press on a node: a click selects, and a drag stores one move. */
  def onNodePressEnd(a: MoveNodesAction): Unit =
    mouseAction.inactive()
    a.phase match
      case NodePress.Pending  => if a.clickOnRelease then clickOn(a.pressed, a.rect.shift)
      case NodePress.Inert    => ()
      case NodePress.Dragging =>
        nodeDrag.foreach: drag =>
          val (dx, dy) = drag.delta
          if dx == 0 && dy == 0 then drag.cancel()
          else
            val before = viewerOps.layout.now()
            viewerOps.moveNodesBy(drag.nodes, dx, dy)
            // The move gives a new render, which replaces this svg. Until then the nodes stay where the person put
            // them, so that the layout transition starts from there. The event handler runs in a transaction of
            // Airstream, so the layout changes after it. With no change, no render comes, and the drawing goes back.
            drag.hold()
            js.timers.setTimeout(0)(if viewerOps.layout.now() eq before then drag.cancel())
        nodeDrag = None

  /** Stop a drag with no move: Escape, or a new press before the release. */
  def cancelNodeDrag(): Unit =
    nodeDrag.foreach(_.cancel())
    nodeDrag = None
    bendDrag.foreach(_.cancel())
    bendDrag = None

  // --- The bend points of the selected arrow (manual layout, ADR 0004, part 4) ---

  /** The last click on a bend handle: the arrow, the index of the bend, and the time. A second click soon after it is
    * a double-click, and it removes the bend.
    */
  var lastBendClick = Option.empty[(ArrowId, Int, Double)]

  /** The point of the SVG for a layout point, with the offset of the layout now. */
  def layoutToSvg(p: LayoutPoint): BendPath.Vec = BendRender.toSvg(p, viewerOps.layoutOffsetNow())

  /** The arrow of a press on the path of the selected arrow: in a free layout, a drag there adds a bend. Shift keeps
    * the meaning of a click with Shift (a change of the selection).
    */
  def pressedBendPath(ev: dom.MouseEvent): Option[ArrowId] =
    if ev.shiftKey || !viewerOps.drawsFreeLayoutNow then None
    else
      ev.target match
        case el: dom.Element if el.tagName.equalsIgnoreCase("path") =>
          Option(el.closest(strategy.edgeSelector))
            .flatMap(SelectableElement.fromDomElement(_, strategy))
            .flatMap(_.arrowId)
            .filter(id => selection.now().size == 1 && selection.now().contains(id))
        case _ => None

  /** A press on the handle of the bend `index` of the arrow `arrow`. A second press soon after a click is a
    * double-click: it removes the bend. Else the press can become a drag of the bend.
    */
  def onBendHandlePress(arrow: ArrowId, index: Int, ev: dom.MouseEvent): Unit =
    if ev.button == 0 then
      ev.stopPropagation()
      ev.preventDefault()
      cancelNodeDrag()
      forgetClick()
      val now = js.Date.now()
      lastBendClick match
        case Some((id, i, at)) if id == arrow && i == index && now - at < ManualLayoutOps.DoubleClickMs =>
          lastBendClick = None
          mouseAction.inactive()
          viewerOps.removeBend(arrow, index, now)
        case _ =>
          lastBendClick = Some((arrow, index, now))
          val pos = ClientPoint(ev.clientX, ev.clientY)
          mouseAction.start(MoveBendAction(MouseActionRect(pos, pos, false), arrow, index, insert = false, NodePress.Pending))

  /** The handles of the selected arrow: in a free layout, at each of its bends. A drag of a bend hides them. */
  def refreshBendHandles(elem: Option[SelectableElement], action: MouseAction): Unit =
    val show = action match
      case Inactive          => true
      case a: MoveBendAction => a.phase == NodePress.Pending
      case _                 => false
    val arrow = elem.flatMap(_.arrowId).filter(_ => show && viewerOps.drawsFreeLayoutNow)
    val bends = arrow.fold(Vector.empty)(viewerOps.bendsNow)
    BendControls.refresh(mainGroup, bends.map(layoutToSvg), (i, ev) => arrow.foreach(onBendHandlePress(_, i, ev)))

  /** The start of the look of a drag of a bend. None when the canvas cannot draw it. */
  def beginBendDrag(a: MoveBendAction): Option[BendControls.BendDrag] =
    if !viewerOps.drawsFreeLayoutNow || !viewerOps.startBendDrag() then None
    else
      for
        (from, to) <- viewerOps.drawnArrowEndsOf(a.arrow)
        edge     <- queryElements(ElementIds.from(a.arrow)).headOption.map(_.ref)
        position <- edgePositions.get(a.arrow.value)
        shape     = BendRender.nodeShapes(mainGroup, nodePositions, nodeSizes)
        source   <- shape(from)
        target   <- shape(to)
        ctm      <- Option(mainGroup.getScreenCTM()).filter(m => m.a != 0 && m.d != 0)
        base      = viewerOps.bendsNow(a.arrow)
        if a.insert || base.indices.contains(a.index)
      yield
        val (ox, oy) = viewerOps.layoutOffsetNow()
        def toLayout(at: ClientPoint): LayoutPoint =
          val local = at.toSvgPoint(ctm)
          LayoutPoint(local.x + ox, -local.y + oy)
        def drawingToLayout(x: Double, y: Double) = BendPath.Vec(x + ox, y + oy)
        val pressAt = toLayout(a.rect.start)
        val (index, origin) =
          if a.insert then
            val start = drawingToLayout(position.startPoint.x, position.startPoint.y)
            val end   = drawingToLayout(position.endPoint.x, position.endPoint.y)
            val at    = BendPath.insertionIndex(start, base.map(p => BendPath.Vec(p.x, p.y)), end, BendPath.Vec(pressAt.x, pressAt.y))
            (at, pressAt)
          else (a.index, base(a.index))
        BendControls.BendDrag(mainGroup, edge, position, source, target, a.arrow, base, index, a.insert, origin, pressAt, toLayout, layoutToSvg)

  /** A move of the pointer during a press on a bend or on the path. The action changes only at a change of phase. */
  def onBendPressMove(a: MoveBendAction, end: ClientPoint, alt: Boolean): Unit =
    a.phase match
      case NodePress.Pending if NodePress.isDrag(a.rect.update(end, a.rect.shift)) =>
        forgetClick()
        lastBendClick = None
        val drag = beginBendDrag(a)
        bendDrag = drag
        drag.foreach(_.update(end, alt))
        val phase = if drag.isDefined then NodePress.Dragging else NodePress.Inert
        mouseAction.start(a.copy(rect = a.rect.update(end, a.rect.shift), phase = phase))
      case NodePress.Dragging =>
        bendDrag.foreach(_.update(end, alt))
      case _ => ()

  /** The release of a press on a bend or on the path: a drag stores the new bends in one change. */
  def onBendPressEnd(a: MoveBendAction): Unit =
    mouseAction.inactive()
    if a.phase == NodePress.Dragging then
      bendDrag.foreach: drag =>
        if !drag.changed then drag.cancel()
        else
          val before = viewerOps.layout.now()
          viewerOps.setArrowBends(drag.arrow, drag.points)
          // As for a drag of nodes: the new path stays until the new render replaces this svg.
          drag.hold()
          js.timers.setTimeout(0)(if viewerOps.layout.now() eq before then drag.cancel())
    bendDrag = None

  // Fires when a layout transition lands (LayoutTransition.finish dispatches it
  // on this svg). Overlay controls built at mount were positioned at frame 0 —
  // the OLD layout, by the transition's pixel-exact contract — so they must be
  // rebuilt from the settled geometry.
  val layoutSettled = EventBus[Unit]()

  // The listeners on the svg element live as long as the element, so they are
  // added here, one time, and not on mount. The same svg element can mount
  // again: after the 3D view, CanvasContainer shows it again. A listener for
  // each mount gave one more `layoutSettled` emission for each transition
  // end. The transition listener also exists before onRendered starts a
  // transition. The hover of the fold badges is delegated from the svg, so it
  // covers each layout that renders under it.
  rawSvg.ref.addEventListener(LayoutTransition.transitionEndEvent, (_: dom.Event) => layoutSettled.emit(()))
  CountBadges.installHover(mainGroup, strategy)
  LinkBadges.installHover(mainGroup, strategy, viewerOps.hoveredLink.set)

  def refreshOverlayControls(elem: Option[SelectableElement], action: MouseAction): Unit =
    viewerOps.handleNewArrowControls(mainGroup, elem, action)
    viewerOps.handleArrowEndpointControl(mainGroup, elem, action, edgePositions)
    refreshBendHandles(elem, action)

  /** The screen scale of this canvas. It keeps its measures, so that a pan or
    * a zoom reads no layout after the write of the transform. */
  val scale = ScreenConstant.CanvasScale(rawSvg.ref, mainGroup)

  /** Refit everything whose size is a SCREEN quantity, from new measures: the
    * controls, which hold a constant size, and the selection decorations,
    * which hold a constant relationship to the object they mark. The canvas
    * calls it when the base scale of the svg viewport can change. A pan or a
    * zoom refits the chrome in the `transform` binder below. */
  def remeasureChrome(): Unit =
    scale.invalidate()
    ScreenConstant.refitAll(rawSvg.ref, scale)
    SelectionCasing.refit(mainGroup, scale, force = true)

  // A resize of the svg box changes the base scale with no window resize: the
  // Library sidebar and Cmd+B change the width of the canvas. The observer
  // also fires one time when it starts, and thus a mount measures again.
  val svgResize = dom.ResizeObserver((_, _) => remeasureChrome())

  // render all selected elements the first time
  rawSvg
    .amend {
      Seq(
        svg.viewBox   := s"${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}",
        svg.width     := null,
        svg.height    := null,
        svg.className := "graphviz",
        transform --> { tr =>
          // Every control holds its SCREEN size across pan/zoom (none of them
          // rebuild here — a re-fit keeps listeners and drag state intact).
          //
          // Order is important here. A layout read after the transform write
          // forces a synchronous layout of the whole SVG. Thus the scale
          // measures what it does not know BEFORE the write, and the refit
          // after the write computes the scale from the zoom.
          val controls = ScreenConstant.controls(rawSvg.ref)
          scale.prime(controls.flatMap(ScreenConstant.frameOf))
          mainGroup.setAttribute(svg.transform.name, tr.css)
          scale.zoomIs(tr.zoom)
          ScreenConstant.refit(controls, scale)
          SelectionCasing.refit(mainGroup, scale)
        },
        // A window resize rescales the viewBox→client mapping with no transform
        // event — the third way the CTM moves under the controls' feet. A
        // change of the browser zoom (and thus of devicePixelRatio) also
        // resizes the window.
        windowEvents(_.onResize) --> { _ => remeasureChrome() },
        // The svg can mount again (after the 3D view), and the window can
        // change its size while the svg is not in the document. Thus an
        // unmount forgets the measures.
        // The colors follow the theme and the setting. A change paints the
        // colors again, with no new render.
        DiagramColors.follow(rawSvg.ref, viewerOps.diagramDarkColors.signal),
        onMountUnmountCallback(
          mount = _ => svgResize.observe(rawSvg.ref),
          unmount = _ =>
            svgResize.disconnect()
            scale.invalidate()
            // The strip must not keep the URL of a drawing that is gone.
            viewerOps.hoveredLink.set(None)
        ),
        // Post-mount work needing real geometry (badges: getBBox; onRendered:
        // client rects). Registered AFTER the transform binder on purpose —
        // mount runs modifiers in order, and onRendered's screen measurements
        // are garbage until the pan/zoom transform has been applied.
        onMountCallback { _ =>
          // Mermaid sets an inline max-width (its useMaxWidth option) on most
          // diagram kinds. The svg clips its content, so the box must cover the
          // whole canvas for a pan to stay visible. The canvas owns the size.
          rawSvg.ref.style.setProperty("max-width", "none")
          rawSvg.ref.style.setProperty("max-height", "none")
          CountBadges.decorate(mainGroup, strategy, concealedCounts, onToggleConcealed, collapsedCounts, onToggleCollapsed, onCollapseGroup)
          LinkBadges.decorate(mainGroup, strategy, openLink)
          onRendered(rawSvg.ref)
        },
        // --------------------------------------------------------
        // Mouse events
        // --------------------------------------------------------
        // 1. Drawing a selecting rectangle (OR dbl-click) starts here. Other actions start in their respective elements.
        //
        // A press on a node of a diagram with a graph starts a node press. A short move is a click, and a longer move
        // drags the nodes (see `onNodePressMove`). A press on a node that is already selected waits for the release
        // to select, so that a drag keeps a multi-selection. A press on any other node selects at once, as before.
        onMouseDown.filter(leftButton).map(ev => (ev, clientCoords(ev))) --> { case (ev, (pos, shift)) =>
          cancelNodeDrag()
          val result = handleElementClick(
            ev,
            js.Date.now(),
            findClosestElementId(js.Array(ev.target.asInstanceOf[dom.Element]), strategy = strategy)
          )
          val rect = MouseActionRect(pos, pos, shift)
          (result, pressedNode(ev.target)) match
            case (ClickResult.Edited, _) => ()
            case (ClickResult.CellSelected, Some(node)) =>
              mouseAction.start(MoveNodesAction(rect, node, ev.altKey, clickOnRelease = false, NodePress.Pending))
            case (ClickResult.NotHandled, Some(node)) =>
              val alreadySelected = viewerOps.inDragSelection(node)
              if !alreadySelected then clickOn(node, shift)
              mouseAction.start(MoveNodesAction(rect, node, ev.altKey, clickOnRelease = alreadySelected, NodePress.Pending))
            case (ClickResult.NotHandled, None) =>
              // A press on the path of the selected arrow of a free layout can add a bend (part 4). A click there
              // keeps the selection, as before.
              pressedBendPath(ev) match
                case Some(arrow) => mouseAction.start(MoveBendAction(rect, arrow, -1, insert = true, NodePress.Pending))
                case None        => mouseAction.start(ExtendSelectionAction(rect))
            case _ => ()
        },
        // 2. Any ongoing action is updated here (i.e., mouse position). A node press follows the pointer on the
        // document (below), so that a drag continues outside of the svg.
        onMouseMove.filter(leftButtonMoved).map(clientCoords) --> { (end, shift) =>
          mouseAction.now() match
            case _: MoveNodesAction | _: MoveBendAction =>
            case _                                      => mouseAction.updateEndpoint(end, shift)
        },
        // 3. Any ongoing action ends here
        onMouseUp.filter(leftButton)(_.withCurrentValueOf(mouseAction.signal)) --> { (ev, previousAction) =>
          previousAction match
            case _: MoveNodesAction         => // The document listener below ends it.
            case _: MoveBendAction          => // The document listener below ends it.
            case a: AddNewArrowAction       => mouseAction.inactive(); viewerOps.handleAddNewArrowMouseUp(ev, a)
            case a: MoveArrowEndpointAction => mouseAction.inactive(); viewerOps.handleMoveArrowStartMouseUp(ev, a)
            case _                          => mouseAction.inactive()
        },
        // A node press: the moves and the release, also outside of the svg. Escape stops a drag with no move.
        documentEvents(_.onMouseMove) --> { ev =>
          mouseAction.now() match
            case a: MoveNodesAction if ev.buttons == 1 => onNodePressMove(a, ClientPoint(ev.clientX, ev.clientY), ev.altKey)
            case a: MoveBendAction if ev.buttons == 1  => onBendPressMove(a, ClientPoint(ev.clientX, ev.clientY), ev.altKey)
            case _                                     =>
        },
        documentEvents(_.onMouseUp).filter(leftButton) --> { _ =>
          mouseAction.now() match
            case a: MoveNodesAction => onNodePressEnd(a)
            case a: MoveBendAction  => onBendPressEnd(a)
            case _                  =>
        },
        documentEvents(_.onKeyDown).filter(_.key == "Escape") --> { _ =>
          mouseAction.now() match
            case _: MoveNodesAction | _: MoveBendAction =>
              cancelNodeDrag()
              mouseAction.inactive()
            case _ =>
        },
        // A double-click on the empty canvas of a free layout adds a node at that point (ADR 0004, part 3). A
        // double-click on an element keeps its meaning: the press handler above edits the label. In an automatic
        // diagram, a double-click on the empty canvas does nothing, as before.
        onDblClick.filter(leftButton) --> { ev =>
          val onElement = ev.target match
            case el: dom.Element =>
              findClosestElementId(js.Array(el), strategy = strategy).isDefined || el.closest(s".${BendControls.HandleClass}") != null
            case _ => true
          // A double-click on a bend handle removes the bend (part 4). The new render can put the empty canvas under
          // the pointer before the double-click event: that event must not add a node.
          if !onElement && !viewerOps.removedBendRecently(js.Date.now()) then
            viewerOps.newNodeAtClient(ClientPoint(ev.clientX, ev.clientY))
        },
        // The svg can go away during a drag (a new render): the look of the drag goes with it.
        onUnmountCallback: _ =>
          nodeDrag = None
          bendDrag = None
        ,
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
            remeasureChrome()
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
          SelectionCasing.refit(mainGroup, scale, force = true),
        // subsequent selection changes don't trigger onMountCallback, so we can be more
        // precise and only select/unselect the elements that actually changed
        selectionElementChanges --> { groups =>
          // This should only happen when the selection groups are non-empty (see dropWhile above)
          groups.toUnselect.foreach { e => e.unselect(); SelectionCasing.clear(e.ref) }
          groups.toSelect.foreach(_.select())
          // The decorations select() just created are sized from the object
          // they mark, at the CURRENT zoom — nothing about that is knowable
          // from inside select().
          SelectionCasing.refit(mainGroup, scale, force = true)
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

/** What a press did in `handleElementClick`. */
private enum ClickResult derives CanEqual:
  /** The press is not a click of the cell level and not a double-click. */
  case NotHandled

  /** The press selected a cell of the selected record. */
  case CellSelected

  /** The press opened an editor: a double-click. */
  case Edited

/** True while an editable element (input/textarea/contenteditable, incl. the CodeMirror
  * source editor) has keyboard focus — used to avoid stealing focus to the canvas.
  */
private def isTextEditingActive(): Boolean =
  Option(dom.document.activeElement).exists { active =>
    val tag = active.tagName.toUpperCase
    // The focus can be on an svg element: a click on a linked node gives the focus to its svg `<a>`. An svg element
    // has no `isContentEditable`, and a cast to an html element failed with a ClassCastException.
    val editable = active match
      case html: dom.html.Element => html.isContentEditable
      case _                      => false
    tag == "INPUT" || tag == "TEXTAREA" || editable || active.closest(".cm-editor") != null
  }
