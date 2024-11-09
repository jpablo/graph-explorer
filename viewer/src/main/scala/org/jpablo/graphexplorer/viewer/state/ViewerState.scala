package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.Dot
import org.jpablo.graphexplorer.viewer.formats.dot.Dot.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState.handleWheel
import org.scalajs.dom.{SVGPoint, SVGSVGElement}
import upickle.default.*

class SourceFlow(initialSource: String, hiddenNodesV: Signal[Set[NodeId]]):
  val source: Var[String] = Var(initialSource)

  // 1. parse source
  // String ~> Dot ~> DiGraphAST
  val fullAST: Signal[DiGraphAST] =
    source.signal.map: src =>
      Dot(src).buildAST.headOption
        .map(_.attachInternalAttributes)
        .getOrElse(DiGraphAST.empty)

  // 2. DiGraphAST ~> ViewerGraph
  // Arrows are assigned consecutive ids starting from 1
  val fullGraph: Signal[ViewerGraph] =
    fullAST.map(_.toViewerGraph)

  // 3. Remove hidden nodes from Dot AST
  // DiGraphAST ~[removeNodes]~> DiGraphAST
  val visibleAST: Signal[DiGraphAST] =
    fullAST
      .combineWith(hiddenNodesV.signal)
      .map: (fullAST, hiddenNodes) =>
        fullAST
          .removeNodes(hiddenNodes.map(_.value))
          .setDefaultTheme

  // 4. transform visible AST back to Visible Dot
  // DiGraphAST ~> Dot
  val visibleDOT: Signal[Dot] =
    visibleAST.map(_.toDot)

  val visibleGraph: Signal[ViewerGraph] =
    visibleAST.map(_.toViewerGraph)

end SourceFlow

case class ViewerState(projectId: ProjectId, initialSource: String = ""):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(projectId)))

  private val translateXY = Var(SvgUnit.origin)
  val zoomValue = Var(1.0)
  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  private val sourceFlow = SourceFlow(initialSource, project.hiddenNodes.signal)

  val source = sourceFlow.source
  val fullAST = sourceFlow.fullAST
  val fullGraph = sourceFlow.fullGraph
  private val visibleAST = sourceFlow.visibleAST.tapEach(_ => resetView())
  private val visibleDOT = sourceFlow.visibleDOT
  private val visibleGraph = sourceFlow.visibleGraph

  // ---- SvgDotDiagram ----
  val startNode = Var[Option[(models.NodeId, Point2d[Double])]](None)
  val endPos = Var[Point2d[Double]]((0, 0))
  val isDragging = Var(false)

  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val svgDiagramElement: Signal[ReactiveSvgElement[SVGSVGElement]] =
    visibleDOT
      .flatMapSwitch(_.toSvg)
      .map(SvgDotDiagram.svgWithTransform(transform, startNode.signal, endPos.signal, isDragging.signal))

  private val svgDotDiagram: Signal[SvgDotDiagram] =
    svgDiagramElement.map(SvgDotDiagram.apply)

  val allNodeIds: Signal[Set[NodeId]] =
    fullGraph.map(_.allNodeIds)

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  val diagramSelection = DiagramSelectionOps()
  // -------------------------------

  private val hiddenNodes = HiddenNodesOps(project.hiddenNodes)

  val hiddenNodesS = hiddenNodes.signal

  /** Modify `hiddenNodes` based on the given function `f`
    */
  private def updateHiddenNodes[E <: dom.Event](
      ep: EventProp[E]
  )(f: (HiddenNodes, Set[NodeId], ViewerGraph) => HiddenNodes) =
    ep(_.sample(fullGraph.combineWith(diagramSelection.signal))) --> { (g: ViewerGraph, selection: Set[NodeId]) =>
      project.hiddenNodes.update(f(_, selection, g))
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

  def addEdge(fullAST: DiGraphAST, from: NodeId, to: NodeId): Unit =
    val ast2 = fullAST.addEdge(from, to)
    source.set(ast2.render(false))

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

      def copySelectionAsSVG(writeText: String => Any) =
        ev(_.sample(svgDotDiagram, diagramSelection.signal)) --> { (svgDiagram: SvgDotDiagram, canvasSelection) =>
          writeText(svgDiagram.toSVGTextWithIds(canvasSelection))
        }

      def copyAsDOT(writeText: String => Any) =
        ev(_.sample(visibleDOT)) --> { dot => writeText(dot.value) }

      def copyAsJSON(writeText: String => Any) =
        ev(_.sample(visibleAST)) --> { ast => writeText(writeJs(ast).toString) }

      def keepRootsOnly =
        updateHiddenNodes(ev)((_, _, g) => g.allNodeIds -- g.roots)

      def hideAllNodes =
        ev(_.sample(allNodeIds).map(_.toSeq)) --> (hiddenNodes.extend(_))

      def updateTranslate(using E <:< dom.WheelEvent): Base =
        ev(_.withCurrentValueOf(svgDiagramElement)) --> (handleWheel(zoomValue, translateXY)(_, _))

  // -------- storage ------------

  private val persistedStateVar: Var[PersistedState] = ProjectStorage.projectPersistedState(projectId)

  private def restoreState() =
    val state0 = persistedStateVar.now()
    // Restore state from storage
    source.set(state0.source)
    project.name.set(state0.projectName)
    project.hiddenNodes.set(state0.hiddenNodes)
    leftPanelVisible.set(state0.leftPanelVisible)
    leftPanelTabIndex.set(state0.sideBarTabIndex)
    // Set up persistence of state changes
    project.hiddenNodes.signal
      .combineWith(
        project.name.signal,
        source.signal,
        leftPanelVisible.signal,
        leftPanelTabIndex.signal
      )
      .map(PersistedState.apply)
      .foreach(persistedStateVar.set)
  end restoreState

  restoreState()

end ViewerState

case class PersistedState(
    hiddenNodes:      Set[NodeId] = Set.empty,
    projectName:      String = "",
    source:           String = "",
    leftPanelVisible: Boolean = true,
    sideBarTabIndex:  Int = 0
) derives ReadWriter

object PersistedState:
  private val minimalGraphText = "digraph G {\n}"
  val empty =
    PersistedState(
      hiddenNodes      = Set.empty,
      projectName      = "Untitled",
      source           = minimalGraphText,
      leftPanelVisible = true,
      sideBarTabIndex  = 0
    )

object ViewerState:
  def handleWheel(
      zoomValue:   Var[Double],
      translateXY: Var[Point2d[SvgUnit]]
  )(wEv: dom.WheelEvent, svgDiagram: ReactiveSvgElement[dom.SVGSVGElement]) =
    val clientHeight = dom.window.innerHeight.max(1)
    val clientWidth = dom.window.innerWidth.max(1)

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        (z - wEv.deltaY / clientHeight).max(0.001)
    else
      val viewBox = svgDiagram.ref.viewBox.baseVal
      val z = zoomValue.now()
      val scale = (viewBox.width / clientWidth).max(viewBox.height / clientHeight)
      val svgDelta = (SvgUnit(wEv.deltaX * scale / z), SvgUnit(wEv.deltaY * scale / z))
      translateXY.update(_ - svgDelta)

  def toSVGCoords(
      clientX:    Double, // px
      clientY:    Double, // px
      svgElement: SVGSVGElement
  ): SVGPoint =
    val point = svgElement.createSVGPoint()
    point.x = clientX
    point.y = clientY
    val ctm = svgElement.getScreenCTM()
    point.matrixTransform(ctm.inverse())

end ViewerState
