package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementIds, NodeId}

trait VisibilityOps:
  this: ViewerState =>

  val hiddenNodes = HiddenNodesOps(project.hiddenElements)

  val hiddenNodesS = hiddenNodes.signal

  def showAllNodes() =
    hiddenNodes.clear()

  def isNodeVisible(id: NodeId) = hiddenNodesS.map(ids => id notIn ids)

  def isEdgeVisible(id: ArrowId) =
    visibleGraph.map(graph => id in graph.arrowIds)

  def toggleNode(id: NodeId) =
    hiddenNodes.toggle(id)
    selection.toggle(id)

  def showOnlyGroup() =
    selection.selectGroupMembers()
    hideNonSelectedNodes()
    selection.clear()


  def hideNodes(ids: Set[NodeId]) =
    hiddenNodes.add(ids)

  def showNodes(ids: Set[NodeId]) =
    hiddenNodes.remove(ids)

  def keepRootsOnly() =
    project.hiddenElements.update(_ ++ (sourceFlow.fullGraph.now().nodeIds -- sourceFlow.fullGraph.now().roots))

  def hideAllNodes() =
    project.hiddenElements.update(_ ++ sourceFlow.fullGraph.now().nodeIds)

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
    project.hiddenElements.update(f(_, selection.now(), sourceFlow.fullGraph.now()))

