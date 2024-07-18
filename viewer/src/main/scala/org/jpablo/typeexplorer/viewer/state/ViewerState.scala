package org.jpablo.typeexplorer.viewer.state

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import org.jpablo.typeexplorer.viewer.state.VisibleNodes.given
import org.jpablo.typeexplorer.viewer.formats.{CSV, Dot}

case class ViewerState(
    initialSource: String,
    renderDot:     String => Signal[SvgDotDiagram]
):
  given owner: Owner = OneTimeOwner(() => ())

  val source: Var[String] = Var(initialSource)

  val fullGraph: Signal[ViewerGraph] =
    source.signal.map(parseSource)

  private def parseSource(source: String): ViewerGraph =
    val csv = CSV.fromString(source)
    if csv.isEmpty then ViewerGraph.from(csv)
    else ViewerGraph.from(csv)

  val appConfigDialogOpenV = Var(false)

  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))

  val diagramOptionsV: Var[DiagramOptions] =
    project.page.zoom(_.diagramOptions)((p, s) => p.copy(diagramOptions = s))

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.nodeIds)

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  private val diagramSelectionV = Var(Set.empty[NodeId])

  val diagramSelection =
    DiagramSelectionOps(diagramSelectionV)
  // -------------------------------
  val visibleNodesV: Var[VisibleNodes] =
    project.page.zoom(_.visibleNodes)((p, s) => p.copy(visibleNodes = s))

  val visibleNodes =
    VisibleNodesOps(visibleNodesV, fullGraph, diagramSelectionV)
  // -------------------------------

  val svgDiagram: Signal[SvgDotDiagram] =
    fullGraph
      .combineWith(project.page.signal.distinct)
      .flatMapSwitch: (graph, page) =>

        renderDot(Dot.fromViewerGraph(graph.subgraph(page.visibleNodes.keySet)).toString)

  // ---- storage ----
  private def persistableEvents: Signal[(VisibleNodes, String)] =
    visibleNodesV.signal.combineWith(source.signal)

  // --
  private def restoreState() =
    val ss = storedString("viewer.state", initial = "{}")
    val (nodes0, source0) = readFromString(ss.signal.observe.now())
    visibleNodesV.set(nodes0)
    source.set(source0)
    for a <- persistableEvents do ss.set(writeToString(a))

  restoreState()

end ViewerState
