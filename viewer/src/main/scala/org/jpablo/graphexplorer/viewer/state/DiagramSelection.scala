package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.SvgDotDiagram
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps(selectedNodes: Var[SelectedNodes] = Var(Set.empty)):
  export selectedNodes.now
  val signal = selectedNodes.signal

  def toggle(ss:  NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))
  def replace(ss: NodeId*): Unit = selectedNodes.set(ss.toSet)
  def extend(s:   NodeId): Unit = selectedNodes.update(_ + s)

  def extend(ss: SelectedNodes): Unit = selectedNodes.update(_ ++ ss)
  def remove(ss: SelectedNodes): Unit = selectedNodes.update(_ -- ss)

  def clear(): Unit = selectedNodes.set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(selector: (ViewerGraph, SelectedNodes) => ViewerGraph)(
      fullGraph:   ViewerGraph,
      svgDiagram:  SvgDotDiagram,
      hiddenNodes: HiddenNodes
  ): Unit =
    val subGraph: ViewerGraph = fullGraph.remove(hiddenNodes)
    val relatedDiagram: ViewerGraph = selector(subGraph, selectedNodes.now())
    val arrowIds = relatedDiagram.arrows.map(_.toTuple).zipWithIndex.map((ab, i) => NodeId(s"${ab._1}->${ab._2}:$i"))
    val relatedIds = relatedDiagram.allNodeIds ++ arrowIds
    extend(relatedIds)
    svgDiagram.select(relatedIds)

end DiagramSelectionOps
