package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.scalajs.dom
import upickle.default.writeJs

import scala.scalajs.js.JSON

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps(selectedNodes: Var[SelectedNodes] = Var(Set.empty)):
  val signal = selectedNodes.signal.tapEach(s => dom.console.debug("selectedNodes:", JSON.parse(writeJs(s).toString)))

  def now(): SelectedNodes = selectedNodes.now()

  def toggle(ss: NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))

  def set(ss:    SelectedNodes): Unit = selectedNodes.set(ss)
  def add(ss:    SelectedNodes): Unit = selectedNodes.update(_ ++ ss)
  def remove(ss: SelectedNodes): Unit = selectedNodes.update(_ -- ss)

  def contains(s: NodeId): Boolean = selectedNodes.now().contains(s)

  def clear(): Unit = selectedNodes.set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(
      selector: (ViewerGraph, SelectedNodes) => ViewerGraph
  )(fullGraph: ViewerGraph, hiddenNodes: HiddenNodes): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.remove(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    val relatedIds = relatedSubGraph.allNodeIds ++ relatedSubGraph.allArrowIds
    add(relatedIds)

  def handleClickOnArrow(arrow: Arrow)(metaKey: Boolean) =
    val selection = now()
    if metaKey then
      if selection.contains(arrow.nodeId) then
        // for each arrow.source and arrow.target: remove it if it's not part of any edge
        val edgesWithoutClicked = selection
          .filter(nodeId => nodeId.value != arrow.nodeId.value && nodeId.value.contains("->"))
          .map(_.value)

        val nodeIds = selection.filter(!_.value.contains("->"))
        val toRemove = nodeIds.filterNot(nodeId => edgesWithoutClicked.exists(_.contains(nodeId.value)))

        remove(toRemove + arrow.nodeId)
      else
        add(arrow.nodeIds)
    else
      set(arrow.nodeIds)

end DiagramSelectionOps
