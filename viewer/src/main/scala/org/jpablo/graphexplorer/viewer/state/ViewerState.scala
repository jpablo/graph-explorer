package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import com.softwaremill.macwire.*
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.DotText.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Attributes, NodeId}
import org.scalajs.dom.{KeyboardEvent, SVGPoint, SVGSVGElement}
import upickle.default.*

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

  private val sourceFlow = SourceFlow(initialSource, project.hiddenNodes.signal, resetView)

  val sourceText = sourceFlow.sourceText
  val fullAST = sourceFlow.fullAST
  val fullGraph = sourceFlow.fullGraph
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

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  val diagramSelection = DiagramSelectionOps()
  // -------------------------------

  private val hiddenNodes = HiddenNodesOps(project.hiddenNodes)

  val hiddenNodesS = hiddenNodes.signal

  // -------------- UI state -----------------
  val leftPanelVisible = Var(true)
  val leftPanelTabIndex = Var(0)

  // -------- Public API -----------
  def resetView(): Unit =
    zoomValue.set(0.90)
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

  // Note: explore just adding the edge to the source directly (a -> b)
  def addEdge(from: NodeId, to: NodeId): Unit =
    sourceFlow.sourceAST.update(_.addEdge(from, to))

  def handleMouseDown(endNodeId: NodeId, clientCoords: Point2d[Double]): Unit =
    Var.set(
      startNode -> Some(endNodeId, clientCoords),
      endPos    -> clientCoords
    )

  def handleMouseMove(clientCoords: Point2d[Double], buttons: Int): Unit =
    // Check if the left mouse button is pressed
    if buttons == 1 && startNode.now().isDefined then
      isDragging.set(true)
      endPos.set(clientCoords)

  def handleMouseUp(endNodeId: Option[NodeId]): Unit =
    if isDragging.now() then
      endNodeId.foreach: nodeId =>
        startNode.now().map(_._1)
          .filter(_ != nodeId)
          .foreach(startNodeId => addEdge(startNodeId, nodeId))
      Var.set(
        startNode  -> None,
        isDragging -> false
      )

  // -------- Attribute management -----------
  // top level attributes
  val graphTargetAttributes =
    sourceFlow.sourceAST
      .zoom(_.getDiagramAttributes(AttributeTarget.graph))(
        _.updateDiagramAttributes(AttributeTarget.graph)(_)
      )

  val nodeTargetAttributes =
    sourceFlow.sourceAST.zoom(_.getDiagramAttributes(AttributeTarget.node))(
      _.updateDiagramAttributes(AttributeTarget.node)(_)
    )

  val edgeTargetAttributes =
    sourceFlow.sourceAST.zoom(_.getDiagramAttributes(AttributeTarget.edge))(
      _.updateDiagramAttributes(AttributeTarget.edge)(_)
    )

  // individual node attributes
  def nodesAttributes(nodeIds: Set[String]): Var[Map[Path, Path]] =
    def astToMap = visibleGraph.observe.now().attributesById(nodeIds).values
    sourceFlow.sourceAST.zoom(_ => astToMap): (ast, attrs) =>
      val sg = ast.asSubgraph.updateTopLevelAttributes(nodeIds, Attributes(attrs))
      DotAST(ast.tpe, sg.children, sg.id)

  // -------- Diagram actions -----------
  val eventHandlers = wire[EventHandlers]

  def hideSelection() =
    project.hiddenNodes.update(_ ++ diagramSelection.now())

  def deleteSelection() =
    sourceFlow.sourceAST.update(_.attachInternalAttributes.removeNodes(diagramSelection.now()))

  def groupSelection() =
    sourceFlow.sourceAST.update(_.groupNodes(diagramSelection.now()))

  def addEdge() =
    sourceFlow.sourceAST.update: ast =>
      val selection = diagramSelection.now()
      if selection.isEmpty then
        ast.addRandomNode()
      else
        ast.addNodeAndEdge(selection.head)

  def handleKeyDown(ke: KeyboardEvent): Unit =
    ke.key match
      case "Backspace" => deleteSelection()
      case "a"         => addEdge()
      case "g"         => groupSelection()
      case _           => ()

  // -------- storage ------------

  private val persistedState: Var[PersistedState] =
    ProjectStorage.loadProjectPersistedState(projectId)

  private def restoreState() =
    val state0 = persistedState.now()
    // Restore ViewerState <~ PersistedStage (which comes from local storage)
    sourceText.set(state0.source)
    project.name.set(state0.projectName)
    project.hiddenNodes.set(state0.hiddenNodes)
    leftPanelVisible.set(state0.leftPanelVisible)
    leftPanelTabIndex.set(state0.sideBarTabIndex)
    // synchronize ViewerState ~> PersistedStage
    project.hiddenNodes.signal
      .combineWith(
        project.name.signal,
        sourceText.signal,
        leftPanelVisible.signal,
        leftPanelTabIndex.signal
      )
      .map(PersistedState.apply)
      .foreach(persistedState.set)
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
