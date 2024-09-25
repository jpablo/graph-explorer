package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.{SvgDotDiagram, SvgUnit}
import org.jpablo.graphexplorer.viewer.formats.CSV
import org.jpablo.graphexplorer.viewer.formats.dot.Dot
import org.jpablo.graphexplorer.viewer.formats.dot.Dot.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement
import upickle.default.*

enum InputFormats:
  case csv, dot

case class PersistedState(
    hiddenNodes:     Set[NodeId],
    source:          String,
    sideBarVisible:  Boolean = false,
    sideBarTabIndex: Int = 0
) derives ReadWriter

object PersistedState:
  val empty = PersistedState(Set.empty, "", false, 0)

case class ViewerState(initialSource: String = ""):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))

  // --------------------------------

  // 0: initial source
  val source: Var[String] = Var(initialSource)

  // 1. parse source and create a graph
  private val fullGraphWithSourceAST: Signal[(ViewerGraph, Option[DiGraphAST])] =
    source.signal.map(parseSource(InputFormats.dot))

  val fullGraph: Signal[ViewerGraph] =
    fullGraphWithSourceAST.map(_._1)

  private def parseSource(format: InputFormats)(sourceStr: String): (ViewerGraph, Option[DiGraphAST]) =
    format match
      case InputFormats.csv =>
        (CSV(sourceStr).toViewerGraph, None)
      case InputFormats.dot =>
        val ast = Dot(sourceStr).buildAST.headOption
        (ast.map(_.toViewerGraph).getOrElse(ViewerGraph.empty), ast)

  // 2. transform graph to SVG using visible nodes
  val visibleDOT: Signal[Dot] =
    fullGraphWithSourceAST
      .combineWith(project.page.signal.distinct)
      .map: (fullGraph, sourceDotAST, page) =>
        // (Until ViewerGraph supports all DOT attributes, we need to keep the original DOT AST)
        // Idea: *remove* invisible nodes from the original AST and use it to render the SVG.
        // This works as long as we don't change the style via the UI. In that case
        // we might need to import the full AST into an internal model (currently not implemented).
        val visibleFromSourceDot =
          sourceDotAST
            .map(_.removeNodes(page.hiddenNodes.map(_.value)))
            .map(_.toDot)
        lazy val visibleSimpleDot =
          fullGraph.remove(page.hiddenNodes).toDot
        // The original AST is used to render the SVG.
        // If it is not available, build a new Dot from scratch.
        visibleFromSourceDot.getOrElse(visibleSimpleDot)

  // ---- SvgDotDiagram ----
  val translateXY = Var(SvgUnit.origin)
  val zoomValue = Var(1.0)
  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  val svgDiagramElement: Signal[ReactiveSvgElement[SVGSVGElement]] =
    visibleDOT
      .flatMapSwitch(_.toSvg)
      .map(SvgDotDiagram.withTransform(transform))

  val svgDotDiagram: Signal[SvgDotDiagram] =
    svgDiagramElement.map(SvgDotDiagram.apply)

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.nodeIds)

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  private val diagramSelectionV = Var(Set.empty[NodeId])

  val diagramSelection =
    DiagramSelectionOps(diagramSelectionV)
  // -------------------------------

  private val hiddenNodes =
    HiddenNodesOps(project.hiddenNodesV)

  val hiddenNodesS = hiddenNodes.signal

  /** Modify `hiddenNodes` based on the given function `f`
    */
  def updateHiddenNodes[E <: dom.Event](
      ep: EventProp[E]
  )(f: (HiddenNodes, Set[NodeId], ViewerGraph) => HiddenNodes) =
    ep(_.sample(fullGraph.combineWith(diagramSelectionV))) --> { (g: ViewerGraph, selection: Set[NodeId]) =>
      project.hiddenNodesV.update(f(_, selection, g))
    }

  // -------------- UI state -----------------
  val sideBarVisible = Var(false)
  val sideBarTabIndex = Var(0)

  // -------------------------------

  // -------- Public API -----------

  def hideSelectedNodes[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, _) => hidden ++ sel)

  def hideNonSelectedNodes[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, g) => hidden ++ (g.nodes.map(_.id) -- sel))

  def showAllSuccessors[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, g) => hidden -- g.allSuccessorsGraph(sel).nodeIds)

  def showDirectSuccessors[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, g) => hidden -- g.directSuccessorsGraph(sel).nodeIds)

  def showAllPredecessors[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, g) => hidden -- g.allPredecessorsGraph(sel).nodeIds)

  def showDirectPredecessors[E <: dom.Event](e: EventProp[E]) =
    updateHiddenNodes(e)((hidden, sel, g) => hidden -- g.directPredecessorsGraph(sel).nodeIds)

  def showAllNodes() =
    hiddenNodes.clear()

  def hideAllNodes[E <: dom.Event](event: EventProp[E]) =
    event(_.sample(allNodeIds).map(_.toSeq)) --> (hiddenNodes.extend(_))

  def copyAsSVG[E <: dom.Event](write: String => Any)(event: EventProp[E]) =
    event(_.sample(svgDotDiagram)) --> { diagram => write(diagram.toSVGText) }

  def copyAsDOT[E <: dom.Event](write: String => Any)(event: EventProp[E]) =
    event(_.sample(visibleDOT)) --> { diagram => write(diagram.value) }

  def keepRootsOnly() =
    ()

  def isVisible(id: NodeId) = hiddenNodesS.map(!_.contains(id))

  def toggleNode(id: NodeId) =
    hiddenNodes.toggle(id)
    diagramSelection.toggle(id)
  // -------------------------------

  // ---- storage ----
  private def persistableEvents: Signal[PersistedState] =
    project.hiddenNodesV.signal
      .combineWith(source.signal, sideBarVisible.signal, sideBarTabIndex.signal)
      .map(PersistedState.apply)

  // --
  private def restoreState() =
    val ss = storedString("viewer.state", initial = "{\"hiddenNodes\":[],\"source\":\"\", \"sideBarVisible\":false}")
    val state0 =
      try read[PersistedState](ss.signal.observe.now())
      catch
        (e: Throwable) =>
          dom.console.error(s"Error reading state: $e")
          PersistedState.empty
    source.set(state0.source)
    project.hiddenNodesV.set(state0.hiddenNodes)
    sideBarVisible.set(state0.sideBarVisible)
    sideBarTabIndex.set(state0.sideBarTabIndex)
    for a <- persistableEvents do ss.set(write(a))

  restoreState()

end ViewerState
