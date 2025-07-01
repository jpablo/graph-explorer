package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.notIn
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}

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

  def hideNonSelectedNodes() =
    updateHiddenFromSelection((h, sel, g) => h ++ (g.nodeIds -- sel.nodeIds))

  def showAllSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allSuccessorsGraph(sel.nodeIds).nodeIds)

  def showDirectSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directSuccessorsGraph(sel.nodeIds).nodeIds)

  def showAllPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allPredecessorsGraph(sel.nodeIds).nodeIds)

  def showDirectPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directPredecessorsGraph(sel.nodeIds).nodeIds)

  private def updateHiddenFromSelection(f: (HiddenElements, ElementIds, ViewerGraph) => HiddenElements) =
    hiddenElements.update(f(_, selection.now(), fullGraphNow()))
