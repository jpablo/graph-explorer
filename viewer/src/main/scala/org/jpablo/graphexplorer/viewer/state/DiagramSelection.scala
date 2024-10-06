package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps(selectedNodes: Var[SelectedNodes] = Var(Set.empty)):
  val signal = selectedNodes.signal.tapEach(s => dom.console.log(s"selectedNodes: $s"))

  def toggle(ss: NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))
  def set(ss:    NodeId*): Unit = selectedNodes.set(ss.toSet)

  def add(ss: SelectedNodes): Unit = selectedNodes.update(_ ++ ss)
  def remove(ss: SelectedNodes): Unit = selectedNodes.update(_ -- ss)

  def clear(): Unit = selectedNodes.set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(selector: (ViewerGraph, SelectedNodes) => ViewerGraph)(
      fullGraph:   ViewerGraph,
      hiddenNodes: HiddenNodes
  ): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.remove(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    val relatedIds = relatedSubGraph.allNodeIds ++ relatedSubGraph.allArrowIds
    add(relatedIds)

end DiagramSelectionOps
