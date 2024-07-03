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

  def selectParents = selectRelated(_.parentsOfAll(_), _, _, _)
  def selectChildren = selectRelated(_.childrenOfAll(_), _, _, _)
  def selectDirectParents = selectRelated(_.directParentsOfAll(_), _, _, _)
  def selectDirectChildren = selectRelated(_.directChildrenOfAll(_), _, _, _)

  private def selectRelated(
      selector:     (ViewerGraph, DiagramSelection) => ViewerGraph,
      graph:        ViewerGraph,
      svgDiagram:   SvgDotDiagram,
      visibleNodes: VisibleNodes
  ): Unit =
    val subGraph: ViewerGraph = graph.subgraph(visibleNodes.keySet)
    val relatedDiagram: ViewerGraph = selector(subGraph, diagramSelection.now())
    val arrowIds = relatedDiagram.arrows.map(_.toTuple).map((a, b) => NodeId(s"${b}_$a"))
    extend(relatedDiagram.nodeIds)
    extend(arrowIds)
    svgDiagram.select(relatedDiagram.nodeIds)
    svgDiagram.select(arrowIds)

end DiagramSelectionOps
