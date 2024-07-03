package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes

type CanvasSelection = Set[NodeId]

class CanvasSelectionOps(canvasSelectionV: Var[CanvasSelection] = Var(Set.empty)):
  export canvasSelectionV.now
  val signal = canvasSelectionV.signal

  def toggle(ss:  NodeId*): Unit = canvasSelectionV.update(ss.foldLeft(_)(_.toggle(_)))
  def replace(ss: NodeId*): Unit = canvasSelectionV.set(ss.toSet)
  def extend(s:   NodeId): Unit = canvasSelectionV.update(_ + s)

  def extend(ss: CanvasSelection): Unit = canvasSelectionV.update(_ ++ ss)
  def remove(ss: CanvasSelection): Unit = canvasSelectionV.update(_ -- ss)

  def clear(): Unit = canvasSelectionV.set(Set.empty)

  def selectParents = selectRelated(_.parentsOfAll(_), _, _, _)
  def selectChildren = selectRelated(_.childrenOfAll(_), _, _, _)
  def selectDirectParents = selectRelated(_.directParentsOfAll(_), _, _, _)
  def selectDirectChildren = selectRelated(_.directChildrenOfAll(_), _, _, _)

  private def selectRelated(
      selector:      (ViewerGraph, CanvasSelection) => ViewerGraph,
      graph:         ViewerGraph,
      svgDiagram:    SvgDotDiagram,
      activeSymbols: VisibleNodes
  ): Unit =
    val subGraph: ViewerGraph = graph.subgraph(activeSymbols.keySet)
    val selection = canvasSelectionV.now()
    val relatedDiagram: ViewerGraph = selector(subGraph, selection)
    val arrowSymbols = relatedDiagram.arrows.map(_.toTuple).map((a, b) => NodeId(s"${b}_$a"))
    extend(relatedDiagram.nodeIds)
    extend(arrowSymbols)
    svgDiagram.select(relatedDiagram.nodeIds)
    svgDiagram.select(arrowSymbols)

end CanvasSelectionOps
