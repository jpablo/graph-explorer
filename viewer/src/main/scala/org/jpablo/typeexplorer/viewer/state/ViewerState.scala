package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.formats.CSV
import org.jpablo.typeexplorer.viewer.formats.dot.Dot
import org.jpablo.typeexplorer.viewer.formats.dot.Dot.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import upickle.default.*

case class ViewerState(initialSource: String = ""):
  given owner: Owner = OneTimeOwner(() => ())

  val source: Var[String] = Var(initialSource)

  val fullGraph: Signal[ViewerGraph] =
    source.signal.flatMapSwitch(parseSource(InputFormats.dot))

  // TODO: selection of format should be explicit
  private def parseSource(format: InputFormats)(source: String): Signal[ViewerGraph] =
    format match
      case InputFormats.csv => Signal.fromValue(CSV(source).toViewerGraph)
      case InputFormats.dot => Signal.fromValue(Dot(source).toViewerGraph)

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
        graph
          .subgraph(page.visibleNodes.keySet)
          .toDot
          .toSvgDiagram

  // ---- storage ----
  private def persistableEvents: Signal[(VisibleNodes, String)] =
    visibleNodesV.signal.combineWith(source.signal)

  // --
  private def restoreState() =
    val ss = storedString("viewer.state", initial = "{}")
    val (nodes0, source0) = read[(VisibleNodes, String)](ss.signal.observe.now())
    visibleNodesV.set(nodes0)
    source.set(source0)
    for a <- persistableEvents do ss.set(write(a))

  restoreState()

end ViewerState

enum InputFormats:
  case csv, dot
