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
import org.jpablo.typeexplorer.viewer.formats.dot.ast.DiGraph
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import upickle.default.*

case class ViewerState(initialSource: String = ""):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))

  val diagramOptionsV: Var[DiagramOptions] =
    project.page.zoom(_.diagramOptions)((p, s) => p.copy(diagramOptions = s))

  // --------------------------------

  // 0: initial source
  val source: Var[String] = Var(initialSource)

  // 1. parse source and create a graph
  val fullGraph: Signal[ViewerGraph] =
    source.signal.map(parseSource(InputFormats.dot)(_)._2)

  val fullGraphWithSource: Signal[(Option[DiGraph], ViewerGraph)] =
    source.signal.map(parseSource(InputFormats.dot))

  private def parseSource(format: InputFormats)(source: String): (Option[DiGraph], ViewerGraph) =
    format match
      case InputFormats.csv =>
        (None, CSV(source).toViewerGraph)
      case InputFormats.dot =>
        // Dot => ViewerGraph discards existing format
        val ast: DiGraph = Dot(source).buildAST.head
        (Some(ast), ast.toViewerGraph)
//        Dot(source).toViewerGraph

  // 2. transform graph to SVG using visible nodes
  val svgDiagram: Signal[SvgDotDiagram] =
    fullGraphWithSource
      .combineWith(project.page.signal.distinct)
      .flatMapSwitch: (originalDotAST, fullGraph, page) =>
        // Reuse the initial DotAST value and *remove* invisibleNodes from the AST
        // and use it to render the SVG
        val nodesNotVisible = fullGraph.nodes.map(_.id) -- page.visibleNodes.keySet
        val modifiedDot =
          originalDotAST
            .map(_.removeNodes(nodesNotVisible.map(_.value)))
            .map(_.toDot)
        lazy val newDot =
          fullGraph.subgraph(page.visibleNodes.keySet).toDot
        // The original AST is used to render the SVG.
        // If it is not available, build a new Dot from scratch.
        modifiedDot.getOrElse(newDot).toSvgDiagram

  val svgDiagram1: Signal[SvgDotDiagram] =
    fullGraph
      .combineWith(project.page.signal.distinct)
      .flatMapSwitch: (graph, page) =>
        val invisible = graph.nodes.map(_.id) -- page.visibleNodes.keySet
        graph
          .subgraph(page.visibleNodes.keySet)
          .toDot
          .toSvgDiagram

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.nodeIds)

  val appConfigDialogOpenV = Var(false)

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
