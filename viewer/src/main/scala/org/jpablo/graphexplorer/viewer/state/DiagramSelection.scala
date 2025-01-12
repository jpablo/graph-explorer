package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.scalajs.dom
import upickle.default.writeJs

import scala.scalajs.js.JSON

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps:
  private val selectedNodes: Var[SelectedNodes] = Var(Set.empty)

  val signal = selectedNodes.signal
    .distinct
    .tapEach(s => if s.nonEmpty then dom.console.log("Selection: " + JSON.parse(writeJs(s).toString)))

  def now(): SelectedNodes = selectedNodes.now()

  def toggle(ss: NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))

  def set(ss: SelectedNodes): Unit = selectedNodes.set(ss)

  def add(ss: SelectedNodes): Unit = 
    val current = selectedNodes.now()
    val newNodes = ss -- current
    if newNodes.nonEmpty then selectedNodes.set(current ++ newNodes)

  def remove(ss: SelectedNodes): Unit = 
    val current = selectedNodes.now()
    val nodesToRemove = ss intersect current
    if nodesToRemove.nonEmpty then selectedNodes.set(current -- nodesToRemove)

  // def contains(s: NodeId): Boolean = selectedNodes.now().contains(s)

  def clear(): Unit = selectedNodes.set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(
      selector: (ViewerGraph, SelectedNodes) => ViewerGraph
  )(fullGraph: ViewerGraph, hiddenNodes: HiddenNodes): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.removeNodes(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    val relatedIds = relatedSubGraph.allNodeIds ++ relatedSubGraph.allArrowIds
    add(relatedIds)

  def handleClickOnNode(nodeId: NodeId)(metaKey: Boolean) =
    if metaKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

  def handleClickOnArrow(arrow: Arrow)(metaKey: Boolean) =
    val nodeId = arrow.id
    if metaKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

end DiagramSelectionOps
