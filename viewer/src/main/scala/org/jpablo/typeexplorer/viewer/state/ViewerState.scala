package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz.toDot
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import ViewerState.VisibleNodes
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.utils.CSVToArray
import org.scalajs.dom

object ViewerState:
  /** The Ids of nodes displayed in the diagram
    */
  type VisibleNodes = Map[NodeId, Option[NodeOptions]]

case class ViewerState(
    initialSource: String,
    renderDot:     String => Signal[SvgDotDiagram]
):
  // TODO: verify that subscriptions are killed when the tab is closed
  given owner: Owner = OneTimeOwner(() => ())

  val source = Var(initialSource)

  val fullGraph =
    source.signal.map(CSVToArray(_)).map(ViewerGraph.from)

  val appConfigDialogOpenV = Var(false)

  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))

  private val pageV: Var[Page] = project.page

  // this should be a subset of visibleNodesV keys
  private val canvasSelectionV = Var(Set.empty[NodeId])

  val visibleNodesV: Var[VisibleNodes] =
    pageV.zoom(_.activeSymbols)((p, s) => p.copy(activeSymbols = s))

  val diagramOptionsV: Var[DiagramOptions] =
    pageV.zoom(_.diagramOptions)((p, s) => p.copy(diagramOptions = s))

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.nodeIds)

  val canvasSelection =
    CanvasSelectionOps(canvasSelectionV)

  val visibleNodes =
    VisibleNodesOps(visibleNodesV, fullGraph, canvasSelectionV)

  val svgDiagram: Signal[SvgDotDiagram] =
    fullGraph
      .combineWith(pageV.signal.distinct)
      .flatMapSwitch: (g, p) =>
        renderDot(g.subgraph(p.activeSymbols.keySet).toDot(""))

end ViewerState

class CanvasSelectionOps(
    canvasSelectionV: Var[Set[NodeId]] = Var(Set.empty)
):
  export canvasSelectionV.now
  val signal = canvasSelectionV.signal

  def toggle(ss:  NodeId*): Unit = canvasSelectionV.update(ss.foldLeft(_)(_.toggle(_)))
  def replace(ss: NodeId*): Unit = canvasSelectionV.set(ss.toSet)
  def extend(s:   NodeId): Unit = canvasSelectionV.update(_ + s)

  def extend(ss: Set[NodeId]): Unit = canvasSelectionV.update(_ ++ ss)
  def remove(ss: Set[NodeId]): Unit = canvasSelectionV.update(_ -- ss)

  def clear(): Unit = canvasSelectionV.set(Set.empty)

  def selectParents = selectRelated(_.parentsOfAll(_), _, _, _)
  def selectChildren = selectRelated(_.childrenOfAll(_), _, _, _)
  def selectDirectParents = selectRelated(_.directParentsOfAll(_), _, _, _)
  def selectDirectChildren = selectRelated(_.directChildrenOfAll(_), _, _, _)

  private def selectRelated(
      selector:      (ViewerGraph, Set[NodeId]) => ViewerGraph,
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
