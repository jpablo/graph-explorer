package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes

type DiagramSelection = Set[NodeId]

class DiagramSelectionOps(diagramSelection: Var[DiagramSelection] = Var(Set.empty)):
  export diagramSelection.now
  val signal = diagramSelection.signal

  def toggle(ss:  NodeId*): Unit = diagramSelection.update(ss.foldLeft(_)(_.toggle(_)))
  def replace(ss: NodeId*): Unit = diagramSelection.set(ss.toSet)
  def extend(s:   NodeId): Unit = diagramSelection.update(_ + s)

  def extend(ss: DiagramSelection): Unit = diagramSelection.update(_ ++ ss)
  def remove(ss: DiagramSelection): Unit = diagramSelection.update(_ -- ss)

  def clear(): Unit = diagramSelection.set(Set.empty)

  def selectSuccessors = selectRelated(_.unfoldSuccessors(_))
  def selectPredecessors = selectRelated(_.unfoldPredecessors(_))
  def selectDirectSuccessors = selectRelated(_.directSuccessors(_))
  def selectDirectPredecessors: (ViewerGraph, SvgDotDiagram, VisibleNodes) => Unit = selectRelated(_.directPredecessors(_))

  private def selectRelated(selector: (ViewerGraph, DiagramSelection) => ViewerGraph)(
      graph:        ViewerGraph,
      svgDiagram:   SvgDotDiagram,
      visibleNodes: VisibleNodes
  ): Unit =
    val subGraph: ViewerGraph = graph.subgraph(visibleNodes.keySet)
    val relatedDiagram: ViewerGraph = selector(subGraph, diagramSelection.now())
    val arrowIds = relatedDiagram.arrows.map(_.toTuple).zipWithIndex.map((ab, i) => NodeId(s"${ab._1}->${ab._2}:$i"))
    val relatedIds = relatedDiagram.nodeIds ++ arrowIds
    extend(relatedIds)
    svgDiagram.select(relatedIds)

end DiagramSelectionOps
