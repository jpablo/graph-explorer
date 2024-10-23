package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import com.raquo.laminar.nodes.ReactiveSvgElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.{SvgDotDiagram, SvgUnit}
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.Dot
import org.jpablo.graphexplorer.viewer.formats.dot.Dot.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement
import upickle.default.*

case class PersistedState(
    hiddenNodes:      Set[NodeId],
    source:           String,
    leftPanelVisible: Boolean,
    sideBarTabIndex:  Int = 0
) derives ReadWriter

object PersistedState:
  val empty = PersistedState(Set.empty, "", false, 0)

case class ViewerState(initialSource: String = ""):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))

  val translateXY = Var(SvgUnit.origin)
  val zoomValue = Var(1.0)
  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  // 0: initial source
  val source: Var[String] = Var(initialSource)

  // 1. parse source
  // String ~> Dot ~> DiGraphAST
  private val fullAST: Signal[DiGraphAST] =
    source.signal.map: src =>
      Dot(src).buildAST.headOption
        .map(_.attachIds)
        .getOrElse(DiGraphAST.empty)

  // 2. DiGraphAST ~> ViewerGraph
  // Arrows are assigned consecutive ids starting from 1
  val fullGraph: Signal[ViewerGraph] =
    fullAST.map(_.toViewerGraph)

  // 3. Remove hidden nodes from Dot AST
  // DiGraphAST ~[removeNodes]~> DiGraphAST
  private val visibleAST: Signal[DiGraphAST] =
    fullAST
      .combineWith(project.hiddenNodesV.signal)
      .tapEach(_ => resetView())
      .map((fullAST, hiddenNodes) => fullAST.removeNodes(hiddenNodes.map(_.value)))

  // 4. transform visible AST back to Visible Dot
  // DiGraphAST ~> Dot
  private val visibleDOT: Signal[Dot] =
    visibleAST.map(_.toDot)

  private val visibleGraph: Signal[ViewerGraph] =
    visibleAST.map(_.toViewerGraph)

  // ---- SvgDotDiagram ----

  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val svgDiagramElement: Signal[ReactiveSvgElement[SVGSVGElement]] =
    visibleDOT
      .flatMapSwitch(_.toSvg)
      .map(SvgDotDiagram.svgWithTransform(transform))

  private val svgDotDiagram: Signal[SvgDotDiagram] =
    svgDiagramElement.map(SvgDotDiagram.apply)

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.allNodeIds)

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
  private def updateHiddenNodes[E <: dom.Event](
      ep: EventProp[E]
  )(f: (HiddenNodes, Set[NodeId], ViewerGraph) => HiddenNodes) =
    ep(_.sample(fullGraph.combineWith(diagramSelectionV))) --> { (g: ViewerGraph, selection: Set[NodeId]) =>
      project.hiddenNodesV.update(f(_, selection, g))
    }

  // -------------- UI state -----------------
  val leftPanelVisible = Var(true)
  val leftPanelTabIndex = Var(0)

  // -------- Public API -----------

  def resetView() =
    zoomValue.set(1.0)
    translateXY.set(SvgUnit.origin)

  def showAllNodes() =
    hiddenNodes.clear()

  def isNodeVisible(id: NodeId) = hiddenNodesS.map(ids => id notIn ids)

  def isEdgeVisible(id: NodeId) = visibleGraph.map(graph => id in graph.allArrowIds)

  def isSelected(id: NodeId) = diagramSelection.signal.map(ids => id in ids)

  def toggleNode(id: NodeId) =
    hiddenNodes.toggle(id)
    diagramSelection.toggle(id)

  def filterByNodeId(nodeIdFilter: Signal[String]): Signal[ViewerGraph] =
    fullGraph
      .combineWith(nodeIdFilter)
      .map(_.filterByNodeId(_))

  def hideNodes(ids: Set[NodeId]) =
    hiddenNodes.add(ids)

  def showNodes(ids: Set[NodeId]) =
    hiddenNodes.remove(ids)

  object eventHandlers:
    extension [E <: dom.Event](ev: EventProp[E])
      def hideSelectedNodes =
        updateHiddenNodes(ev)((hidden, sel, _) => hidden ++ sel)

      def hideNonSelectedNodes =
        updateHiddenNodes(ev)((hidden, sel, g) => hidden ++ (g.allNodeIds -- sel))

      def showAllSuccessors =
        updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.allSuccessorsGraph(sel).allNodeIds)

      def showDirectSuccessors =
        updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.directSuccessorsGraph(sel).allNodeIds)

      def showAllPredecessors =
        updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.allPredecessorsGraph(sel).allNodeIds)

      def showDirectPredecessors =
        updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.directPredecessorsGraph(sel).allNodeIds)

      def selectSuccessors =
        ev(_.sample(fullGraph, hiddenNodesS)) --> diagramSelection.selectSuccessors.tupled

      def selectPredecessors =
        ev(_.sample(fullGraph, hiddenNodesS)) --> diagramSelection.selectPredecessors.tupled

      def selectDirectSuccessors =
        ev(_.sample(fullGraph, hiddenNodesS)) --> diagramSelection.selectDirectSuccessors.tupled

      def selectDirectPredecessors =
        ev(_.sample(fullGraph, hiddenNodesS)) --> diagramSelection.selectDirectPredecessors.tupled

      def copyAsFullDiagramSVG(writeText: String => Any): Base =
        ev(_.sample(svgDotDiagram)) --> { svgDiagram => writeText(svgDiagram.toSVGText) }

      def copyAsDOT(writeText: String => Any) =
        ev(_.sample(visibleDOT)) --> { dot => writeText(dot.value) }

      def copyAsJSON(writeText: String => Any) =
        ev(_.sample(visibleAST)) --> { ast => writeText(writeJs(ast).toString) }

      def keepRootsOnly =
        updateHiddenNodes(ev)((_, _, g) => g.allNodeIds -- g.roots)

      def hideAllNodes =
        ev(_.sample(allNodeIds).map(_.toSeq)) --> (hiddenNodes.extend(_))

  // -------- storage ------------

  private def persistableEvents: Signal[PersistedState] =
    project.hiddenNodesV.signal
      .combineWith(source.signal, leftPanelVisible.signal, leftPanelTabIndex.signal)
      .map(PersistedState.apply)

  private def restoreState() =
    val ss = storedString("viewer.state", initial = "{\"hiddenNodes\":[],\"source\":\"\", \"leftPanelVisible\":true}")
    val state0 =
      try read[PersistedState](ss.signal.observe.now())
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e")
          PersistedState.empty
    source.set(state0.source)
    project.hiddenNodesV.set(state0.hiddenNodes)
    leftPanelVisible.set(state0.leftPanelVisible)
    leftPanelTabIndex.set(state0.sideBarTabIndex)
    for a <- persistableEvents do ss.set(write(a))

  restoreState()

end ViewerState
