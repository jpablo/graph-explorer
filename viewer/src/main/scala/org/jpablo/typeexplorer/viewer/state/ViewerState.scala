package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz.toDot
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import org.jpablo.typeexplorer.viewer.utils.CSVToArray

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
