package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.notIn
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, VisibilityRules}
import org.jpablo.graphexplorer.viewer.graph.VisibilityRules.Direction
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}
import org.scalajs.dom

trait VisibilityOps:
  this: ViewerState =>

  object hiddenElements:
    private val _hiddenElements: Var[HiddenElements] = project.hiddenElements

    def now(): HiddenElements = _hiddenElements.now()

    val update = _hiddenElements.update

    val signal = _hiddenElements.signal

    def toggle(s: NodeId): Unit =
      _hiddenElements.update(_.toggle(s))

    def add(ss: Set[NodeId]): Unit =
      _hiddenElements.update(_ ++ ss)

    def remove(ss: Set[NodeId]): Unit =
      _hiddenElements.update(_ -- ss)

    def clear(): Unit =
      _hiddenElements.set(ElementIds())

  def showAll() =
    hiddenElements.clear()

  def isElementVisible(id: ElementId): Signal[Boolean] =
    hiddenElements.signal.map(ids => id notIn ids)

  def showOnlyGroup() =
    selection.selectGroupMembers()
    hideNonSelectedNodes()
    selection.clear()

  def hideNodes(ids: Set[NodeId]) =
    hiddenElements.add(ids)

  def showNodes(ids: Set[NodeId]) =
    hiddenElements.remove(ids)

  def keepRootsOnly() =
    hiddenElements.update(_ ++ (fullGraphNow().nodeIds -- fullGraphNow().roots))

  def hideAllNodes() =
    hiddenElements.update(_ ++ fullGraphNow().nodeIds)

  /** Permanently delete all elements that are currently hidden.
    * Deletes hidden nodes, arrows, and groups from the full graph and clears the hidden set.
    * Also removes any deleted elements from the current selection.
    */
  def deleteHiddenElements(): Unit =
    val hidden = hiddenElements.now()
    if hidden.isEmpty then
      infoBus.emit("No hidden elements to delete")
    else
      val kinds    = hidden.classify
      val summary  = s"Delete hidden: ${kinds.nodes.size} nodes, ${kinds.arrows.size} arrows, ${kinds.groups.size} groups. Continue?"
      val proceed  = dom.window.confirm(summary)
      if proceed then
        // Remove hidden elements from graph
        phases.fullGraphV.update(_.removeElements(hidden))
        // Clear hidden state and selection references to removed elements
        hiddenElements.clear()
        selection.remove(hidden)
        infoBus.emit("Hidden elements deleted")

  def hideNonSelectedNodes() =
    updateHiddenFromSelection((h, sel, g) => h ++ (g.nodeIds -- sel.nodeIds))

  def showAllSuccessors() =
    updateHiddenFromSelection { (h, sel, g) =>
      val sub = g.allSuccessorsGraph(sel.nodeIds)
      h -- sub.nodeIds -- sub.arrowIds
    }

  def showDirectSuccessors() =
    val g   = fullGraphNow()
    val sel = selection.now()
    val h0  = hiddenElements.now()
    val sub = g.directSuccessorsGraph(sel.nodeIds)
    val newlyShownNodes = sub.nodeIds intersect h0.nodeIds
    // Unhide nodes and connecting arrows for the direct successors
    hiddenElements.update(_ -- sub.nodeIds -- sub.arrowIds)
    // If we actually revealed new nodes, select them to allow stepwise expansion
    if newlyShownNodes.nonEmpty then
      selection.set1(newlyShownNodes)

  def showAllPredecessors() =
    updateHiddenFromSelection { (h, sel, g) =>
      val sub = g.allPredecessorsGraph(sel.nodeIds)
      h -- sub.nodeIds -- sub.arrowIds
    }

  def showDirectPredecessors() =
    updateHiddenFromSelection { (h, sel, g) =>
      val sub = g.directPredecessorsGraph(sel.nodeIds)
      h -- sub.nodeIds -- sub.arrowIds
    }

  /** Hide successors of the currently selected nodes.
    *
    * Semantics (VisibilityRules.contract, pinned by VisibilityRulesSpec):
    *  - Hide all visible outgoing arrows from the selected nodes A -> B.
    *  - If a target node B loses all remaining incoming visible arrows, hide B as well.
    *  - If `recursive` is true, repeat the process from each newly hidden node B.
    *
    * This supports layer-by-layer contraction with reachability preserved from other visible sources.
    */
  def hideSuccessors(recursive: Boolean = true): Unit =
    contractSelection(Direction.Successors, recursive)

  /** The mirror: hide the selected nodes' incoming arrows, and a source that
    * no longer points at ANY visible node hides with them. */
  def hidePredecessors(recursive: Boolean = true): Unit =
    contractSelection(Direction.Predecessors, recursive)

  private def contractSelection(dir: Direction, recursive: Boolean): Unit =
    val selNodes = selection.now().nodeIds
    if selNodes.nonEmpty then
      val (arrows, nodes) =
        VisibilityRules.contract(fullGraphNow(), hiddenElements.now(), selNodes, dir, recursive)
      if arrows.nonEmpty || nodes.nonEmpty then
        hiddenElements.update(_ ++ arrows ++ nodes)

  // ── tree-style toggles ────────────────────────────────────────────────────
  // One key per direction: a node with CONCEALED direct neighbors expands
  // (show them); an already-expanded one contracts a layer. Repeated presses
  // walk deeper / shallower, like a tree view's triangle.

  /** Expandable check for `ids` (or the selection): any concealed direct
    * neighbor in `dir`. */
  private def concealedFor(ids: Set[NodeId], dir: Direction): Set[NodeId] =
    val g = fullGraphNow(); val h = hiddenElements.now()
    ids.flatMap(VisibilityRules.concealedDirect(g, h, _, dir))

  def toggleSuccessors(ids: Set[NodeId] = selection.now().nodeIds): Unit =
    if ids.nonEmpty then
      if concealedFor(ids, Direction.Successors).nonEmpty then
        withSelection(ids)(showDirectSuccessors())
      else
        withSelection(ids)(hideSuccessors(recursive = false))

  def togglePredecessors(ids: Set[NodeId] = selection.now().nodeIds): Unit =
    if ids.nonEmpty then
      if concealedFor(ids, Direction.Predecessors).nonEmpty then
        withSelection(ids)(showDirectPredecessors())
      else
        withSelection(ids)(hidePredecessors(recursive = false))

  /** Run `body` with `ids` as the selection when they differ from it — the
    * badge click passes an explicit node while the keyboard path passes the
    * selection unchanged. */
  private def withSelection(ids: Set[NodeId])(body: => Unit): Unit =
    if selection.now().nodeIds != ids then selection.set1(ids)
    body

  private def updateHiddenFromSelection(f: (HiddenElements, ElementIds, ViewerGraph) => HiddenElements) =
    hiddenElements.update(f(_, selection.now(), fullGraphNow()))
