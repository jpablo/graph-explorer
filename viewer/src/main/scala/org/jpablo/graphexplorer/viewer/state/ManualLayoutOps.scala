package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.gxcore.layout.FreeLayout
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowId, GroupId, GroupMemberId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.ClientPoint

/** The manual layout of a diagram (ADR 0004).
  *
  * There is no mode. A node with a stored position stays there, and a node with
  * no stored position gets an automatic place around the pinned nodes. A
  * diagram with no stored position draws as before.
  *
  * The layout is not in the source. A diagram with a file keeps it in a side
  * file next to the file, and a diagram of the browser library keeps it in its
  * entry (see `DiagramPersistence`). Each change of the layout is a step of the
  * undo history (`UndoOps`). The changes of one gesture are one step.
  *
  * The positions are in the frame of the layout: Graphviz points, node centers,
  * with the y axis up. A render can move the whole drawing in its SVG, because
  * Graphviz moves the box of the graph to the origin. Thus a place from a
  * drawing goes into the frame of the layout with the offset of a pinned node
  * ([[nodePlacesNow]]).
  *
  * The API for the next parts:
  *   - part 2 (a drag of a node): [[moveNodesBy]] or [[placeNodes]], for each
  *     move of the gesture. The history merges the moves of one gesture.
  *   - part 3 (a new node at a point): [[layoutPointAt]], [[newNodePlaceNow]] and [[setGraphWithPlaces]]. A drop,
  *     a paste, New node and a double-click on the empty canvas put the node and its place in one step.
  *   - part 4 (bend points): [[activeBends]] for the render, and [[setArrowBends]], [[removeBend]] and
  *     [[resetArrowRoutes]] for the canvas. The layout keeps the bends in `DiagramLayout.arrows`, by the key of
  *     `FreeLayout.arrowKeys`.
  */
trait ManualLayoutOps:
  this: ViewerState =>

  /** The layout of this diagram. The store sets it when the view opens. */
  lazy val layout: Var[DiagramLayout] = Var(DiagramLayout.empty)

  /** The layout for the render: only the positions of the nodes of the full graph. A stored id that the graph does
    * not have stays in [[layout]], and the render ignores it.
    */
  lazy val activeLayout: Signal[DiagramLayout] =
    layout.signal
      .combineWithFn(fullGraph)((stored, graph) => stored.restrictedTo(graph.nodeIds.map(_.value)))
      .distinct

  /** True when a node of the graph has a stored position: the diagram is a free layout. */
  lazy val hasFreeLayout: Signal[Boolean] = activeLayout.map(_.hasPositions).distinct

  private lazy val drawingObs      = svgWithPositions.observe
  private lazy val activeLayoutObs = activeLayout.observe

  /** True when the source is a kind of diagram with no graph, for example a Mermaid sequence diagram. Positions do
    * not apply to it.
    */
  private def renderOnlyNow: Boolean =
    loadStatus.now() match
      case DiagramLoadStatus.RenderOnly(_) => true
      case _                               => false

  /** The place of each drawn node of the full graph, in the frame of the layout.
    *
    * A node with a stored position gives that position. Each other node gives its center in the drawing, moved by
    * the offset between the drawing and the layout. A pinned node in the drawing gives that offset. With no pinned
    * node, the frame of the drawing becomes the frame of the layout.
    */
  def nodePlacesNow(): Map[NodeId, LayoutPoint] =
    val drawn    = drawnCentersNow()
    val active   = activeLayoutObs.now()
    val (dx, dy) = layoutOffsetNow()
    drawn.map: (id, p) =>
      id -> active.point(id.value).getOrElse(LayoutPoint(p.x + dx, p.y + dy).rounded)

  /** The center of each drawn node of the full graph, in the frame of the drawing (points, the y axis up). */
  private def drawnCentersNow(): Map[NodeId, LayoutPoint] =
    val graphNodes = fullGraphNow().nodeIds
    drawingObs.now().fold(Map.empty[NodeId, LayoutPoint]):
      _.nodePositions.collect { case (name, p) if graphNodes.contains(NodeId(name)) => NodeId(name) -> LayoutPoint(p.x, p.y) }

  /** The offset from the frame of the drawing to the frame of the layout: a place of the layout is a place of the
    * drawing plus this offset. A pinned node in the drawing gives it. With no pinned node, the offset is zero.
    *
    * A Graphviz drawing has the place `(x, y)` at the point `(x, -y)` of its SVG.
    */
  def layoutOffsetNow(): (Double, Double) =
    val active = activeLayoutObs.now()
    drawnCentersNow().iterator
      .flatMap((id, p) => active.point(id.value).map(stored => (stored.x - p.x, stored.y - p.y)))
      .nextOption()
      .getOrElse((0.0, 0.0))

  /** Freeze: store the current place of each drawn node, so that the diagram keeps its look as a free layout.
    *
    * The stored positions of the other nodes (a hidden node) stay. A selected renderer that cannot pin a node
    * (Mermaid) goes back to Auto in the same change, so that the free layout shows.
    */
  def freezeLayout(): Unit =
    if renderOnlyNow then infoBus.emit("Positions do not apply to this kind of diagram.")
    else
      val places = nodePlacesNow()
      if places.isEmpty then errorBus.emit("The diagram has no drawing yet. Wait for the drawing, and then freeze the layout.")
      else
        val next = layout.now().placed(places.map((id, p) => id.value -> p))
        val selectedOther = metadata.renderer.now().flatMap(languages.rendererById).exists(_.id != languages.freeLayoutRenderer.id)
        if selectedOther then Var.set(layout -> next, metadata.metadata -> metadata.now().copy(renderer = None))
        else layout.set(next)

  /** Arrange: remove each stored position. The diagram goes back to the automatic layout. */
  def arrangeLayout(): Unit =
    layout.update(_.withoutPositions)

  /** Store the position of each node of `points`. Each other node keeps its position. */
  def placeNodes(points: Map[NodeId, LayoutPoint]): Unit =
    if points.nonEmpty then layout.update(_.placed(points.map((id, p) => id.value -> p)))

  /** Move each node of `ids` by `dx` and `dy` points (the y axis goes up). A node with no stored position gets its
    * current place first, so that it moves from where the person sees it. A new place has two decimals, as a place
    * from Graphviz has.
    */
  def moveNodesBy(ids: Set[NodeId], dx: Double, dy: Double): Unit =
    val stored   = layout.now()
    val unpinned = ids.filter(id => stored.point(id.value).isEmpty)
    val start    = if unpinned.isEmpty then Map.empty else nodePlacesNow().filter((id, _) => unpinned.contains(id))
    // The bends of an arrow with both ends in the move go with the nodes: the arrow keeps its shape, as during the
    // drag. The bends of an arrow with one moved end stay where the person put them.
    val within = FreeLayout.arrowKeysWithin(fullGraphNow(), ids)
    val moved  = stored.placed(start.map((id, p) => id.value -> p)).movedBy(ids.map(_.value), dx, dy, within)
    layout.set(moved.placed(ids.flatMap(id => moved.point(id.value).map(p => id.value -> p.rounded)).toMap))

  // ------------------------------------------------------------ the drag of nodes (part 2)

  /** True while the canvas offers Freeze: a drag on a node of an automatic layout sets it. */
  lazy val freezeOffer: Var[Boolean] = Var(false)

  /** The person accepts the offer: Freeze, as the command does. */
  def acceptFreezeOffer(): Unit =
    freezeOffer.set(false)
    freezeLayout()

  /** True when a node of the graph has a stored position now. */
  def hasFreeLayoutNow: Boolean = activeLayoutObs.now().hasPositions

  private lazy val rendererObs = currentRenderer.observe

  /** The answer to a drag of nodes: the nodes to move, or no drag. */
  def startNodeDrag(pressed: NodeId): Option[ManualLayoutOps.NodeDragPlan] =
    // A lock protects the source, not the layout (ADR 0004). So a locked diagram takes a drag.
    if renderOnlyNow then None
    else if !hasFreeLayoutNow then
      // "Freeze for dot": a drag does not convert the diagram in silence. The canvas offers Freeze.
      freezeOffer.set(true)
      None
    else if rendererObs.now().id != languages.freeLayoutRenderer.id then
      infoBus.emit(s"${rendererObs.now().label} does not use the positions. Select Auto to move the nodes by hand.")
      None
    else
      val places = nodePlacesNow()
      places.get(pressed).map: from =>
        // Auto fits the view again after each layout, so the drawing moved under the pointer after each drop. The
        // person places the nodes on the screen, so the drag sets Auto to off, as a pan does. The anchor of the
        // next layout then keeps the dropped node where the person dropped it.
        if autoFit.now() then autoFit.set(false)
        ManualLayoutOps.NodeDragPlan(nodesToDrag(pressed).filter(places.contains), pressed, from, layoutOffsetNow())

  /** The nodes that a drag from the node `pressed` moves. A press on a node of the selection, or on a member of a
    * selected group, moves the selected nodes and each node in a selected group. A press on another node moves only
    * that node.
    */
  def nodesToDrag(pressed: NodeId): Set[NodeId] =
    if inDragSelection(pressed) then selectedNodesNow() else Set(pressed)

  /** True when `id` is a node of the full graph. A collapsed group box is not. */
  def isGraphNodeNow(id: NodeId): Boolean = fullGraphNow().nodeIds.contains(id)

  /** Each arrow of the visible graph, with its source and its target: the arrows of the drawing. */
  def drawnArrowEndsNow(): Iterable[(ArrowId, NodeId, NodeId)] =
    visibleGraphNow().arrows.values.map(a => (a.id, a.source, a.target))

  /** True when the node is selected, or is in a selected group. A drag from it then moves the whole selection. */
  def inDragSelection(id: NodeId): Boolean =
    selection.now().contains(id) || selectedNodesNow().contains(id)

  /** True when a node is selected, or a group with a node. */
  def hasSelectedNodesNow: Boolean = selectedNodesNow().nonEmpty

  /** The selected nodes of the full graph, and each node in a selected group. */
  private def selectedNodesNow(): Set[NodeId] =
    val selected = selection.now()
    val graph    = fullGraphNow()
    selected.nodeIds.filter(graph.nodeIds.contains) ++
      ManualLayoutOps.nodesWithin(graph.nodeIds, graph.membership, selected.classify.groups)

  /** Move the selected nodes (and the nodes in the selected groups) by `dx` and `dy` steps of the grid. The y axis
    * goes up. Shift and an arrow key do this.
    */
  def nudgeSelection(dx: Int, dy: Int): Unit =
    if hasFreeLayoutNow then
      val ids = selectedNodesNow()
      if ids.nonEmpty then
        // As for a drag: Auto would fit the view again after the new layout, and the drawing would move on the screen.
        if autoFit.now() then autoFit.set(false)
        moveNodesBy(ids, dx * ManualLayoutOps.GridPt, dy * ManualLayoutOps.GridPt)

  // ------------------------------------------------------------ a new node at a place (part 3)

  /** The geometry of the mounted canvas (see [[measurePlacesWith]]). */
  private var placeGeometry = Option.empty[ManualLayoutOps.PlaceGeometry]

  /** The canvas gives its geometry for the place of a new node.
    *
    * @return the function that removes the geometry. It does nothing when a different canvas set a newer geometry.
    */
  def measurePlacesWith(geometry: ManualLayoutOps.PlaceGeometry): () => Unit =
    placeGeometry = Some(geometry)
    () => if placeGeometry.exists(_ eq geometry) then placeGeometry = None

  /** The point of the layout under the client point `at`, on the grid. It is None when the diagram is not a free
    * layout, when a renderer that does not use the positions draws it, and when the canvas has no drawing.
    *
    * The canvas gives the point of the drawing (`getScreenCTM()` of the drawing group, the y axis up). The offset of
    * a pinned node takes it to the frame of the layout, as for a drag ([[layoutOffsetNow]]).
    */
  def layoutPointAt(at: ClientPoint): Option[LayoutPoint] =
    if renderOnlyNow || !hasFreeLayoutNow || rendererObs.now().id != languages.freeLayoutRenderer.id then None
    else
      placeGeometry.flatMap(_.drawingPoint(at)).map: (x, y) =>
        val (dx, dy) = layoutOffsetNow()
        ManualLayoutOps.snapped(LayoutPoint(x + dx, y + dy))

  /** The place of a new node from a paste or from New node: the pointer when it is over the canvas, else the center
    * of the visible part of the canvas. It is None when the diagram is not a free layout (see [[layoutPointAt]]).
    */
  def newNodePlaceNow(): Option[LayoutPoint] =
    placeGeometry.flatMap(g => g.pointer().orElse(g.visibleCenter())).flatMap(layoutPointAt)

  /** A double-click on the empty canvas at the client point `at`: in a free layout, a new node at that point, as New
    * node adds it (with the label dialog when the setting asks for it). In an automatic diagram, nothing changes.
    */
  def newNodeAtClient(at: ClientPoint): Unit =
    layoutPointAt(at).foreach(p => createNodeMaybePrompt(at = Some(p)))

  /** Write the graph `next` with its new nodes, and the places of the new nodes, in one transaction. Thus the history
    * has the nodes and their places in one step, and the side file gets the places with the nodes.
    *
    * Auto goes off, as for a drag: else the next layout fits the view again, and the new node is not where the person
    * put it.
    */
  private[state] def setGraphWithPlaces(next: ViewerGraph, places: Seq[(NodeId, LayoutPoint)]): Unit =
    if autoFit.now() then autoFit.set(false)
    Var.set(
      phases.fullGraphV -> next,
      layout            -> layout.now().placed(places.map((id, p) => id.value -> p).toMap)
    )

  // ------------------------------------------------------------ bend points (part 4)

  /** The bend points of each arrow of the full graph that has bend points, by its id. The keys of the layout come from
    * the full graph, so a hidden arrow does not change the key of an other arrow. Only a free layout has bends.
    */
  lazy val activeBends: Signal[Map[ArrowId, Vector[LayoutPoint]]] =
    activeLayout.combineWithFn(fullGraph)((stored, graph) => FreeLayout.bendsByArrow(graph, stored)).distinct

  /** True when the canvas draws the free layout now: the diagram has positions, and the renderer can pin a node. */
  def drawsFreeLayoutNow: Boolean =
    !renderOnlyNow && hasFreeLayoutNow && rendererObs.now().id == languages.freeLayoutRenderer.id

  /** The source and the target of the drawn arrow `id`: an arrow of the visible graph. */
  def drawnArrowEndsOf(id: ArrowId): Option[(NodeId, NodeId)] =
    visibleGraphNow().arrows.get(id).map(a => (a.source, a.target))

  /** The key of the arrow `id` in the layout, from the full graph. */
  def arrowKeyNow(id: ArrowId): Option[String] = FreeLayout.arrowKeys(fullGraphNow()).get(id)

  /** The stored bend points of the arrow `id`, from its start to its end. */
  def bendsNow(id: ArrowId): Vector[LayoutPoint] = arrowKeyNow(id).fold(Vector.empty)(layout.now().bends)

  /** Store the bend points of the arrow `id`. No point gives the automatic route again. A point has two decimals. A
    * locked diagram takes the change, because a lock protects the source and not the layout.
    */
  def setArrowBends(id: ArrowId, points: Vector[LayoutPoint]): Unit =
    arrowKeyNow(id).foreach: key =>
      val next = layout.now().withBends(key, points.map(_.rounded))
      if next != layout.now() then layout.set(next)

  /** The time of the last removal of a bend with a double-click, from `Date.now`. The double-click event comes after
    * the new render, on the new svg, and it must not add a node there.
    */
  private var bendRemovedAt = 0.0

  /** True for a short time after the removal of a bend with a double-click. */
  def removedBendRecently(now: Double): Boolean = now - bendRemovedAt < ManualLayoutOps.DoubleClickMs * 2

  /** Remove the bend `index` of the arrow `id`. The removal of the last bend gives the automatic route again. */
  def removeBend(id: ArrowId, index: Int, now: Double): Unit =
    val bends = bendsNow(id)
    if bends.indices.contains(index) then
      bendRemovedAt = now
      setArrowBends(id, bends.patch(index, Nil, 1))

  /** Remove all the bends of the arrows `ids`, in one change: Graphviz routes them again. */
  def resetArrowRoutes(ids: Set[ArrowId]): Unit =
    val keys = ids.flatMap(arrowKeyNow)
    val next = layout.now().withoutBends(keys)
    if next != layout.now() then layout.set(next)

  /** Reset arrow route: remove the bends of the selected arrows. */
  def resetSelectedArrowRoutes(): Unit = resetArrowRoutes(selection.now().classify.arrows)

  /** True when a selected arrow has bend points. */
  def hasBentArrowSelectedNow: Boolean = selection.now().classify.arrows.exists(id => bendsNow(id).nonEmpty)

  /** The start of a drag of a bend. Auto goes off, as for a drag of a node. A locked diagram takes the drag. */
  def startBendDrag(): Boolean =
    if autoFit.now() then autoFit.set(false)
    true

end ManualLayoutOps

object ManualLayoutOps:

  /** The longest time between the two clicks of a double-click, in milliseconds. */
  val DoubleClickMs = 300.0

  /** The spacing of the snap grid, in layout points (1/8 inch). The default node of Graphviz is 54 by 36 points, so a
    * default node with its center on the grid also has its sides on the grid.
    */
  val GridPt = 9.0

  /** A drag of nodes that the state accepts.
    *
    * @param nodes   the nodes that move
    * @param pressed the node under the pointer at the start
    * @param from    the place of `pressed` in the frame of the layout at the start
    * @param offset  the offset from the frame of the drawing to the frame of the layout ([[ManualLayoutOps.layoutOffsetNow]])
    */
  final case class NodeDragPlan(nodes: Set[NodeId], pressed: NodeId, from: LayoutPoint, offset: (Double, Double))

  /** The move of a drag, in layout points, for a raw move `(dx, dy)` of the node at `from`. With `snap`, the move puts
    * the center of that node on the grid of `grid` points. The other nodes of the drag get the same move.
    */
  def dragDelta(from: LayoutPoint, dx: Double, dy: Double, snap: Boolean, grid: Double = GridPt): (Double, Double) =
    if !snap then (dx, dy)
    else
      def onGrid(v: Double) = math.round(v / grid) * grid
      (onGrid(from.x + dx) - from.x, onGrid(from.y + dy) - from.y)

  /** `p` with its two values on the grid of `grid` points. */
  def snapped(p: LayoutPoint, grid: Double = GridPt): LayoutPoint =
    LayoutPoint(math.round(p.x / grid) * grid, math.round(p.y / grid) * grid)

  /** The places of new nodes with the sizes `sizes` (width and height in points), for a drop of several items at the
    * point `first`.
    *
    * The first center is `first` on the grid. Each next node goes below the node before it, in one column, so that a
    * list of files reads from the top down. The gap between two boxes is at least one grid step, and each center is on
    * the grid. The sizes are estimates, because the size of a node is known only after the render. A column needs only
    * the heights, and the height of a node is easier to estimate than its width.
    */
  def columnPlaces(first: LayoutPoint, sizes: Seq[(Double, Double)], grid: Double = GridPt): List[LayoutPoint] =
    if sizes.isEmpty then Nil
    else
      val heights = sizes.map(_._2).toList
      heights
        .zip(heights.tail)
        .scanLeft(snapped(first, grid)):
          case (above, (h0, h1)) =>
            // The small tolerance keeps an exact multiple of the grid at that multiple.
            val step = math.ceil((h0 / 2 + grid + h1 / 2) / grid - 1e-9) * grid
            LayoutPoint(above.x, above.y - step)
        .toList

  /** The geometry of the canvas for the place of a new node. The canvas gives it (see `CanvasContainer`), because the
    * state has no DOM.
    *
    * @param drawingPoint  the point of the drawing under a client point: points, with the y axis up
    * @param pointer       the last client point of the pointer over the canvas, or None when the pointer is not over it
    * @param visibleCenter the client point at the center of the visible part of the canvas
    */
  final case class PlaceGeometry(
      drawingPoint:  ClientPoint => Option[(Double, Double)],
      pointer:       () => Option[ClientPoint],
      visibleCenter: () => Option[ClientPoint]
  )

  /** Each node of `nodes` that is in one of `groups`, directly or in a group inside it. `parent` gives the group
    * that holds a node or a group.
    */
  def nodesWithin(nodes: Set[NodeId], parent: GroupMemberId => Option[GroupId], groups: Set[GroupId]): Set[NodeId] =
    if groups.isEmpty then Set.empty
    else
      // A walk up the groups, with a limit: a bad membership map must not loop.
      def inside(id: NodeId): Boolean =
        Iterator.iterate(parent(id))(_.flatMap(parent)).take(64).takeWhile(_.isDefined).flatten.exists(groups.contains)
      nodes.filter(inside)
