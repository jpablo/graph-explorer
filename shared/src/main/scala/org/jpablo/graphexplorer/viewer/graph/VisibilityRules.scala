package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.HiddenElements

import scala.collection.mutable

/** The pure rules behind show/hide of a node's neighbors — extracted from the
  * viewer so both directions share ONE propagation algorithm and the semantics
  * are pinned by JVM tests (VisibilityRulesSpec) instead of living only in
  * browser-side state code.
  *
  * Direction-symmetric contraction rule: hiding the SUCCESSORS of X hides X's
  * visible outgoing arrows, and a target disappears only when it loses ALL
  * remaining visible incoming arrows (it stays while reachable from another
  * visible source). Hiding the PREDECESSORS mirrors it: incoming arrows go,
  * and a source disappears only when it loses all remaining visible OUTGOING
  * arrows (it stays while it still points at another visible node).
  */
object VisibilityRules:

  enum Direction derives CanEqual:
    case Successors, Predecessors

  /** Arrows/nodes to ADD to the hidden set when contracting `dir` of `sel`.
    * `recursive = false` contracts one layer.
    */
  def contract(
      g:         ViewerGraph,
      hidden:    HiddenElements,
      sel:       Set[NodeId],
      dir:       Direction,
      recursive: Boolean
  ): (Set[ArrowId], Set[NodeId]) =
    val hiddenNodeIds  = hidden.nodeIds
    val hiddenArrowIds = hidden.classify.arrows
    val visibleNodes   = g.nodeIds -- hiddenNodeIds
    val visibleArrows = g.arrows.values
      .filter(a => !hiddenArrowIds.contains(a.id) && visibleNodes.contains(a.source) && visibleNodes.contains(a.target))
      .toVector

    // In the WORKING direction: `out` leaves the selection, `in` is what keeps
    // a neighbor alive. For Predecessors the two swap roles.
    val (outBy, inBy) =
      val bySource = visibleArrows.groupBy(_.source).withDefaultValue(Vector.empty)
      val byTarget = visibleArrows.groupBy(_.target).withDefaultValue(Vector.empty)
      dir match
        case Direction.Successors   => (bySource, byTarget)
        case Direction.Predecessors => (byTarget, bySource)
    def farEnd(a: Arrow): NodeId =
      dir match
        case Direction.Successors   => a.target
        case Direction.Predecessors => a.source

    val queue        = mutable.Queue.from(sel.intersect(visibleNodes))
    val processed    = mutable.Set.empty[NodeId]
    val newlyHiddenA = mutable.Set.empty[ArrowId]
    val newlyHiddenN = mutable.Set.empty[NodeId]

    while queue.nonEmpty do
      val src = queue.dequeue()
      if !processed(src) then
        processed += src
        val outs = outBy(src).filterNot(a => newlyHiddenA(a.id))
        if outs.nonEmpty then
          newlyHiddenA ++= outs.map(_.id)
          outs.foreach: a =>
            val far = farEnd(a)
            if far != src && !newlyHiddenN(far) then
              val remaining = inBy(far).filterNot(in => newlyHiddenA(in.id))
              if remaining.isEmpty then
                newlyHiddenN += far
                if recursive then queue.enqueue(far)

    (newlyHiddenA.toSet, newlyHiddenN.toSet)

  /** Direct neighbors of `n` in `dir` that are currently CONCEALED — the far
    * node is hidden, or only the arrow to it is. These are what a "show
    * direct" expansion would reveal; nonEmpty ⇒ the node is expandable.
    */
  def concealedDirect(
      g:      ViewerGraph,
      hidden: HiddenElements,
      n:      NodeId,
      dir:    Direction
  ): Set[NodeId] =
    val hiddenNodeIds  = hidden.nodeIds
    val hiddenArrowIds = hidden.classify.arrows
    if hiddenNodeIds.contains(n) then Set.empty
    else
      val incident = dir match
        case Direction.Successors   => g.arrowsBySource.getOrElse(n, Vector.empty)
        case Direction.Predecessors => g.arrowsByTarget.getOrElse(n, Vector.empty)
      incident.iterator
        .filter(a => a.source != a.target)
        .filter(a => hiddenArrowIds.contains(a.id) || hiddenNodeIds.contains(if dir == Direction.Successors then a.target else a.source))
        .map(a => if dir == Direction.Successors then a.target else a.source)
        .toSet

  /** Per-node concealed-neighbor counts (successors, predecessors) over the
    * visible nodes — the badge model. Nodes with (0, 0) are omitted.
    */
  def concealedCounts(g: ViewerGraph, hidden: HiddenElements): Map[NodeId, (Int, Int)] =
    val visible = g.nodeIds -- hidden.nodeIds
    visible.iterator
      .map { n =>
        n -> (
          concealedDirect(g, hidden, n, Direction.Successors).size,
          concealedDirect(g, hidden, n, Direction.Predecessors).size
        )
      }
      .filter((_, c) => c._1 > 0 || c._2 > 0)
      .toMap
