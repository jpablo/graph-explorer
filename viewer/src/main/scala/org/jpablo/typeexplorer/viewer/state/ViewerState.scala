package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.{Point2d, SvgDotDiagram, SvgUnit}
import org.jpablo.typeexplorer.viewer.formats.CSV
import org.jpablo.typeexplorer.viewer.formats.dot.Dot
import org.jpablo.typeexplorer.viewer.formats.dot.Dot.*
import org.jpablo.typeexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import upickle.default.*

enum InputFormats:
  case csv, dot

case class PersistedState(
    visibleNodes:    VisibleNodes,
    source:          String,
    sideBarVisible:  Boolean = false,
    sideBarTabIndex: Int = 0
) derives ReadWriter

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
  private val fullGraphWithSource: Signal[(Option[DiGraphAST], ViewerGraph)] =
    source.signal.map(parseSource(InputFormats.dot))

  val fullGraph: Signal[ViewerGraph] =
    fullGraphWithSource.map(_._2)

  private def parseSource(format: InputFormats)(source: String): (Option[DiGraphAST], ViewerGraph) =
    format match
      case InputFormats.csv =>
        (None, CSV(source).toViewerGraph)
      case InputFormats.dot =>
        val ast = Dot(source).buildAST.headOption
        (ast, ast.map(_.toViewerGraph).getOrElse(ViewerGraph.empty))

  // 2. transform graph to SVG using visible nodes
  val visibleDOT: Signal[Dot] =
    fullGraphWithSource
      .combineWith(project.page.signal.distinct)
      .map: (originalDotAST, fullGraph, page) =>
        // Reuse the initial DotAST value and *remove* invisibleNodes from the AST
        // and use it to render the SVG.
        // This works as long as we don't change the style via the UI. In that case
        // we might need to import the full AST into an internal model (currently not implemented).
        val nodesNotVisible = fullGraph.nodes.map(_.id) -- page.visibleNodes.keySet
        val modifiedDot =
          originalDotAST
            .map(_.removeNodes(nodesNotVisible.map(_.value)))
            .map(_.toDot)
        lazy val newDot =
          fullGraph.subgraph(page.visibleNodes.keySet).toDot
        // The original AST is used to render the SVG.
        // If it is not available, build a new Dot from scratch.
        modifiedDot.getOrElse(newDot)

  val translateXY = Var(SvgUnit.origin)
  val zoomValue = Var(1.0)
  val transform =
    zoomValue.signal.combineWith(translateXY.signal).map: (z, p) =>
      s"scale($z) translate(${p.x} ${p.y})"

  val svgDiagramElement =
    visibleDOT
      .flatMapSwitch(_.toSvgDiagram)
      .map(_.ref)
      .map(SvgDotDiagram.withTransform(transform))

  val svgDotDiagram: Signal[SvgDotDiagram] =
    svgDiagramElement.map(svg => SvgDotDiagram(svg.ref))

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
  // -------------- UI state -----------------
  val sideBarVisible = Var(false)
  val sideBarTabIndex = Var(0)

  // -------------------------------

  // ---- storage ----
  private def persistableEvents: Signal[PersistedState] =
    visibleNodesV.signal
      .combineWith(source.signal, sideBarVisible.signal, sideBarTabIndex.signal)
      .map(PersistedState.apply)

  // --
  private def restoreState() =
    val ss = storedString("viewer.state", initial = "{\"visibleNodes\":{},\"source\":\"\", \"sideBarVisible\":false}")
    val state0 = read[PersistedState](ss.signal.observe.now())
    source.set(state0.source)
    visibleNodesV.set(state0.visibleNodes)
    sideBarVisible.set(state0.sideBarVisible)
    sideBarTabIndex.set(state0.sideBarTabIndex)
    for a <- persistableEvents do ss.set(write(a))

  restoreState()

end ViewerState
