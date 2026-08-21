package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.mermaid.effectiveEdgeMarkers
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.gxcore.command.DocumentCommand
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.utils.MouseActionRect

import scala.annotation.targetName
import scala.scalajs.js

type Selection = ElementIds

trait DiagramSelectionOps:
  this: ViewerState =>

  private val selectionV: Var[Selection] = Var(ElementIds())

  val editingElementV = Var[Option[ElementId]](None)

  object selection:
    val signal = selectionV.signal.distinct

    val selectionChanges: EventStream[(toUnselect: ElementIds, toSelect: ElementIds)] =
      selection.signal
        .scanLeft(curr => (ElementIds(), curr)):
          case ((_, curr), next) => (curr, next)
        .map: (curr, next) =>
          (
            toUnselect = curr.filter(id => !next.contains(id)),
            toSelect = next.filter(id => !curr.contains(id))
          )
        .distinct
        .changes

    /** `outgoing` and `transitive` describe the same operation the selector
      * already performs; they exist because the ROW-scoped path has to take the
      * first hop itself (only that hop is constrained by the port) and therefore
      * needs to know which way it points and whether to keep going.
      */
    private def selectRelated(outgoing: Boolean, transitive: Boolean): Unit =
      def selector(graph: ViewerGraph, nodes: Selection): ViewerGraph =
        (outgoing, transitive) match
          case (true, true)   => graph.allSuccessorsGraph(nodes.nodeIds)
          case (true, false)  => graph.directSuccessorsGraph(nodes.nodeIds)
          case (false, true)  => graph.allPredecessorsGraph(nodes.nodeIds)
          case (false, false) => graph.directPredecessorsGraph(nodes.nodeIds)

      val visibleSubGraph: ViewerGraph = fullGraphNow().removeElements(hiddenElements.now())
      recordCells.selectedCellHop(visibleSubGraph, outgoing) match
        // A row of a record/table is the subject: only the arrows attached at
        // its port count, so take that hop by hand. A PORT constrains the first
        // hop and nothing after it — once past the row, "all successors" is the
        // ordinary question about the nodes it reached, so the transitive
        // variants hand off to the usual traversal from there.
        case Some((arrowIds, farNodes)) =>
          val keptCell = recordCells.selectedCell
          // The record is ALREADY expanded — through this row. Re-expanding it
          // would drag in every OTHER row's edges, which is exactly what the row
          // scope exists to prevent, and is what made a second press select the
          // whole table. So the seed is everything else that is selected, minus
          // the record, plus (when transitive) wherever the row's hop landed.
          val rest = selection.now().nodeIds -- recordCells.selectedCellNode.toSet
          val seed = rest ++ (if transitive then farNodes else Set.empty)
          val g    = selector(visibleSubGraph, ElementIds(seed.map(x => x: ElementId)))
          selection.add(ElementIds(arrowIds.map(x => x: ElementId) ++ farNodes) ++ g.nodeIds ++ g.arrowIds)
          // The write above always grows the selection past the record alone, so
          // pruneAgainstSelection has just cleared the row. Put it back, or the
          // NEXT press loses the scope and falls back to the whole table.
          keptCell.foreach(recordCells.restoreCell)
        case None =>
          val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selection.now())
          // Corrected: relatedSubGraph.allArrowIds selects the correct arrowIds
          selection.add(relatedSubGraph.nodeIds ++ relatedSubGraph.arrowIds)

    def editSelectedLabel(): Unit =
      val current = now()
      if current.size == 1 then
        editingElementV.set(Some(current.head))

    def clearEditing(): Unit =
      editingElementV.set(None)

    def now(): Selection = selectionV.now()

    def size(): Int = now().size

    def toggle(ss: ElementId*): Unit = selectionV.update(ss.foldLeft(_)(_.toggle(_)))

    def set(ss: Selection): Unit =
      selectionV.set(ss)

    @targetName("setElementIds")
    def set1(ss: Set[? <: ElementId]): Unit =
      set(ElementIds(ss))

    def set2(ss: ElementId*): Unit =
      set1(ss.toSet)

    @targetName("addElementIds")
    def add(ss: Set[? <: ElementId]): Unit =
      add(ElementIds(ss))

    def add(ss: Selection): Unit =
      val current  = now()
      val newNodes = ss -- current
      if newNodes.nonEmpty then set(current ++ newNodes)

    def remove(ss: Selection): Unit =
      val current       = now()
      val nodesToRemove = ss intersect current
      if nodesToRemove.nonEmpty then set(current -- nodesToRemove)

    /** Keeps only the ids satisfying the predicate. Unlike remove(), the filter is applied when
      * the update TRANSACTION executes, so it composes correctly with selection writes queued in
      * the same propagation (used by the stale-selection pruner).
      */
    def keepOnly(p: ElementId => Boolean): Unit =
      selectionV.update(_.filter(p))

    def clear(): Unit =
      set(ElementIds())

    def contains(id: ElementId) =
      signal.map(ids => id in ids)

    def hide() =
      project.hiddenElements.update(_ ++ resolveCollapsed(selection.now()))

    /** Groups in the selection that can be folded/unfolded: a selected group, or
      * the proxy box standing for an already-collapsed one. */
    def collapsibleGroups(): Set[GroupId] =
      resolveCollapsed(now()).classify.groups

    /** Fold the selected groups into single boxes, or unfold them if they are
      * already folded. A mixed selection folds — the visible outcome then
      * matches what the command says.
      */
    def toggleCollapse(): Unit =
      val gs = collapsibleGroups()
      if gs.nonEmpty then
        project.collapsedGroups.update: collapsed =>
          if gs.subsetOf(collapsed) then collapsed -- gs else collapsed ++ gs

    /** The one-directional versions of toggleCollapse, for when the selection
      * is mixed and "toggle" would guess: fold the selected groups (no-op for
      * ones already folded), or unfold them.
      */
    def collapse(): Unit =
      val gs = collapsibleGroups()
      if gs.nonEmpty then project.collapsedGroups.update(_ ++ gs)

    def expand(): Unit =
      val gs = collapsibleGroups()
      if gs.nonEmpty then project.collapsedGroups.update(_ -- gs)

    def selectGroupMembers() =
      val s          = now()
      val classified = s.classify

      // If we have clusters/groups in the selection, find their members
      if classified.groups.nonEmpty then
        val groupIds          = classified.groups
        val fullGraphSnapshot = fullGraphNow()

        // Get all node ids that are members of the selected groups
        val memberNodeIds = fullGraphSnapshot.getAllChildren(groupIds)

        // Keep the original groups/clusters in the selection and add all members
        set(s ++ memberNodeIds)

    def selectSuccessors()         = selectRelated(outgoing = true, transitive = true)
    def selectPredecessors()       = selectRelated(outgoing = false, transitive = true)
    def selectDirectSuccessors()   = selectRelated(outgoing = true, transitive = false)
    def selectDirectPredecessors() = selectRelated(outgoing = false, transitive = false)

    def addToGroup() =
      val classified = now().classify
      for groupNodeId <- classified.groups.headOption do
        phases.fullGraphV.update(_.moveToGroup(groupNodeId, classified.nodes.toSeq))

    def group() =
      createGroupMaybePrompt(now())

    // Through the vocabulary, not straight to the op (D7.1). What the menu
    // item does and what a socket client's `ungroup` does are now the same
    // code path, which is the only way they stay the same operation.
    def ungroup() =
      runDocumentCommand(DocumentCommand.Ungroup(now().ids.toSet))

    def combineIntoRecord() =
      val nodeIds = now().nodeIds
      if nodeIds.nonEmpty then
        // Selecting what the command created is a VIEW concern, so it stays
        // here rather than in the command — the same operation run headless by
        // `gx` has nothing to select.
        runDocumentCommand(
          DocumentCommand.CombineIntoRecord(nodeIds),
          onApplied = (before, after) => selectNewNodes(before, after)
        )

    def splitRecord() =
      val nodeIds = now().nodeIds
      if nodeIds.size == 1 then
        // The `isRecordNode` guard moved INTO the command, where a headless
        // caller can also be told no. Here it comes back as a message rather
        // than as a silent no-op.
        runDocumentCommand(
          DocumentCommand.SplitRecord(nodeIds.head),
          onApplied = (before, after) => selectNewNodes(before, after)
        )

    def transposeRecord() =
      val nodeIds = now().nodeIds
      if nodeIds.size == 1 then
        runDocumentCommand(DocumentCommand.TransposeRecord(nodeIds.head))

    /** Select whatever the command brought into being.
      *
      * Shared by combine and split because both create nodes and both want the
      * result selected; keeping it in one place is what stops the two from
      * disagreeing about what "the new nodes" means.
      */
    private def selectNewNodes(before: ViewerGraph, after: ViewerGraph): Unit =
      val created = after.nodeIds -- before.nodeIds
      if created.nonEmpty then set1(created)

    def reverseArrowsStyle() =
      val mermaidMode = currentFormatNow() == DiagramFormat.Mermaid
      phases.fullGraphV.update { graph =>
        val classified = now().classify
        // Mermaid has no tail-only link form: swapping the markers of an end-only
        // arrow would make the serializer render it endpoint-swapped, desyncing the
        // in-memory arrow id from the DOM (selection invisible, edge unclickable).
        // For those arrows the identity-coherent equivalent is reversing the
        // endpoints themselves; marker swaps stay marker swaps everywhere else.
        val (toReverse, toSwap) =
          if mermaidMode then
            classified.arrows.partition { id =>
              graph.arrows.get(id).exists { a =>
                val markers = effectiveEdgeMarkers(a.attributes)
                markers.end && !markers.start
              }
            }
          else (Set.empty[ArrowId], classified.arrows)
        val newGraph = graph.reverseArrowsStyle(ElementIds(toSwap)).reverseArrows(ElementIds(toReverse))
        followNewArrows(graph, newGraph, classified.nodes ++ classified.groups ++ toSwap)
        newGraph
      }

    def reverseArrows() =
      phases.fullGraphV.update { graph =>
        val classified = now().classify
        val newGraph   = graph.reverseArrows(now())
        followNewArrows(graph, newGraph, classified.nodes ++ classified.groups)
        newGraph
      }

    /** Follow arrows whose ids changed with their endpoints: keeping the old ids would
      * leave the selection pointing at nothing (pruned on the next graph change).
      */
    private def followNewArrows(old: ViewerGraph, updated: ViewerGraph, keep: Set[ElementId]): Unit =
      val newArrowIds = updated.arrowIds -- old.arrowIds
      if newArrowIds.nonEmpty then
        set1(keep ++ newArrowIds)

    def selectAllVisibleNodes() =
      val visibleNodes = visibleGraphNow().nodeIds
      set1(visibleNodes)

    def selectAllVisibleArrows() =
      set1(visibleGraphNow().arrowIds)

    /** Every group, folded or not. `visibleGraphNow().groupIds` alone misses the
      * folded ones — collapsing REPLACES a group with a proxy node, so a folded
      * group is not a group in the visible graph at all, and this command used
      * to skip exactly the groups whose boxes are most visible.
      */
    def selectAllVisibleGroups() =
      val visible = visibleGraphNow()
      set1(visible.groupIds.map(g => g: ElementId) ++ visible.proxyIds)

    def selectAll() =
      val visibleGraph = visibleGraphNow()
      val nodes        = visibleGraph.nodeIds
      val edges        = visibleGraph.arrowIds
      val groups       = visibleGraph.groupIds
      set1(nodes ++ edges ++ groups)

    def deleteSelection() =
      // Deleting a collapsed box deletes the GROUP it stands for, not a
      // phantom node with the same id (see ViewerState.resolveCollapsed).
      val toRemove = resolveCollapsed(now())
      phases.fullGraphV.update(_.removeElements(toRemove))
      // Nothing left to keep folded once the group itself is gone.
      project.collapsedGroups.update(_ -- toRemove.classify.groups)

    /** Duplicates the currently selected nodes, arrows, and groups. Creates new elements with the same attributes as the selected ones.
      * Nodes are placed in the corresponding duplicated group if their original group was also selected. Arrows are duplicated connecting
      * the corresponding (potentially new) nodes. The newly created elements become the selected elements after duplication.
      */
    def duplicateSelection() =
      phases.fullGraphV.update: graph =>
        val currentSelection = now()
        if currentSelection.isEmpty then
          graph
        else
          val (newGraph, newElements) = graph.duplicateSelection(currentSelection.classify)
          if newElements.nonEmpty then
            set1(newElements)
          newGraph

    /** Removes all attributes except 'label' from the selected elements.
      */
    def resetAttributes(): Unit =
      val selection = now()
      if selection.nonEmpty then
        phases.fullGraphV.update(_.resetAttributes(selection))

    /** Resets the layout-related attributes for the selected elements. Placeholder implementation.
      */
    def resetLayout(): Unit =
      // TODO: Implement layout reset logic
      // Could involve removing attributes like pos, width, height, bb?
      println("resetLayout called for selection: " + now())

    def updateSelectionStatus(elementId: ElementId)(shiftKey: Boolean) =
      if shiftKey then
        toggle(elementId)
      else
        set(ElementIds.from(elementId))
      // keyboard navigation starts from the element last clicked — but only a
      // click that leaves the element SELECTED may claim the cursor. A
      // shift-click deselect must not clobber the previous (still valid) one.
      if now().contains(elementId) then navCursorSet(elementId)

    def handleClickOnArrow(arrow: Arrow)(shiftKey: Boolean) =
      val nodeId = arrow.id
      if shiftKey then
        toggle(nodeId)
      else
        set(ElementIds.from(nodeId))
      if now().contains(nodeId) then navCursorSet(nodeId)

    /** `elementsFromRectEnd` is BY-NAME: it is a document-wide hit test that
      * forces layout, and only the click branch below reads it — this runs on
      * every mouse-move of a rubber-band drag.
      */
    def selectExtendSelectionOverlappingElements(
        rect:                 MouseActionRect,
        selectableElements:   Seq[SelectableElement],
        elementsFromRectEnd: => js.Array[dom.Element]
    ) =
      if rect.isEmpty then
        // Equivalent to an onClick event
        findClosestElementId(elementsFromRectEnd, strategy = selectionStrategyNow()) match
          case Some(end) => updateSelectionStatus(end)(rect.shift)
          case None      => clear()
      else
        val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.elementId).toSet
        if nodesInRect.nonEmpty then
          if rect.shift then
            add(nodesInRect)
          else
            set1(nodesInRect)
        else if !rect.shift then
          clear()

    /** Checks if a selectable element intersects with a selection rectangle
      *
      * @param elem
      *   The selectable element to check
      * @param rect
      *   The selection rectangle in client coordinates
      * @return
      *   true if the element's bounding box intersects with the selection rectangle
      *
      * The method:
      *   1. Gets the element's bounding box in client coordinates 2. Normalizes the selection rect coordinates to handle any direction of
      *      dragging 3. Uses a standard rectangle intersection test
      */
    def isNodeInRect(elem: SelectableElement, rect: MouseActionRect): Boolean =
      val bbox   = elem.ref.getBoundingClientRect()
      val x      = rect.start.x min rect.end.x
      val y      = rect.start.y min rect.end.y
      val width  = math.abs(rect.end.x - rect.start.x)
      val height = math.abs(rect.end.y - rect.start.y)
      !(bbox.right < x ||
        bbox.left > x + width ||
        bbox.bottom < y ||
        bbox.top > y + height)

  def printSelectionToConsole(): Unit =
    // Don't remove this line!! it IS the actual functionality
    pprint.log(selection.now())
    dom.console.log("Visible current selection to the console")

end DiagramSelectionOps

object DiagramSelectionOps:
  /** Finds the node ID at the given selection rectangle's end point.
    *
    * @param elements The elements to search in
    * @param strategy The selection strategy for extracting element IDs
    * @param selector CSS selector for selectable elements (defaults to strategy's allSelector)
    */
  def findClosestElementId(
      elements: js.Array[dom.Element],
      strategy: SelectableElementStrategy,
      selector: Option[String] = None
  ): Option[ElementId] =
    val effectiveSelector = selector.getOrElse(strategy.allSelector)
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest(effectiveSelector)))
      .distinct
      .collect:
        case e: dom.Element => e
      .map(SelectableElement.fromDomElement(_, strategy))
      .collectFirst:
        case Some(elem) => elem.elementId

end DiagramSelectionOps
