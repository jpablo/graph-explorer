package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.notIn
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, ElementId, ElementIds, NodeId}
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
        phases.updateFullGraph(_.removeElements(hidden))
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
    * Semantics:
    *  - Hide all visible outgoing arrows from the selected nodes A -> B.
    *  - If a target node B loses all remaining incoming visible arrows, hide B as well.
    *  - If `recursive` is true, repeat the process from each newly hidden node B.
    *
    * This supports layer-by-layer contraction with reachability preserved from other visible sources.
    */
  def hideSuccessors(recursive: Boolean = true): Unit =
    val selNodes = selection.now().nodeIds
    if selNodes.isEmpty then return

    // Snapshot current visible graph based on hidden elements
    val full = fullGraphNow()
    val hiddenNow = hiddenElements.now()
    val hiddenNodeIds  = hiddenNow.nodeIds
    val hiddenArrowIds = hiddenNow.classify.arrows

    // Visible nodes and arrows
    val visibleNodes: Set[NodeId] = full.nodeIds -- hiddenNodeIds
    val visibleArrows = full.arrows.values
      .filter(a => !(hiddenArrowIds.contains(a.id)) && visibleNodes.contains(a.source) && visibleNodes.contains(a.target))
      .toVector

    // Build adjacency in terms of arrows (we work with arrows to support multi-edges and selective hiding)
    val outgoingBySource: Map[NodeId, Vector[Arrow]] =
      visibleArrows.groupBy(_.source).withDefaultValue(Vector.empty)
    val incomingByTarget: Map[NodeId, Vector[Arrow]] =
      visibleArrows.groupBy(_.target).withDefaultValue(Vector.empty)

    import scala.collection.mutable
    val queue               = mutable.Queue.from(selNodes.intersect(visibleNodes))
    val processed           = mutable.Set.empty[NodeId]
    val newlyHiddenArrows   = mutable.Set.empty[ArrowId]
    val newlyHiddenNodes    = mutable.Set.empty[NodeId]

    while queue.nonEmpty do
      val src = queue.dequeue()
      if !processed(src) then
        processed += src
        // Hide all visible outgoing arrows from src
        val outs = outgoingBySource(src).filterNot(a => newlyHiddenArrows(a.id))
        if outs.nonEmpty then
          newlyHiddenArrows ++= outs.map(_.id)

          // For each target, check if any other incoming arrows remain visible
          outs.foreach: a =>
            val tgt = a.target
            if tgt != src && !newlyHiddenNodes(tgt) then
              val remainingIncoming = incomingByTarget(tgt).filterNot(in => newlyHiddenArrows(in.id))
              if remainingIncoming.isEmpty then
                newlyHiddenNodes += tgt
                if recursive then queue.enqueue(tgt)

    if newlyHiddenArrows.nonEmpty || newlyHiddenNodes.nonEmpty then
      hiddenElements.update(_ ++ newlyHiddenArrows.toSet ++ newlyHiddenNodes.toSet)

  private def updateHiddenFromSelection(f: (HiddenElements, ElementIds, ViewerGraph) => HiddenElements) =
    hiddenElements.update(f(_, selection.now(), fullGraphNow()))
