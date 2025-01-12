package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.macwire.*
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
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

  val undoEvent: EventBus[Unit] = EventBus()
  val redoEvent: EventBus[Unit] = EventBus()

  val sourceText = sourceFlow.sourceText
  val fullGraph = sourceFlow.fullGraph
  private val visibleDOT = sourceFlow.visibleDOT
  val visibleGraph = sourceFlow.visibleGraph

  // ---- SvgDotDiagram ----
  val startNode = Var[Option[(models.NodeId | models.Arrow, Point2d[Double])]](None)
  val endPos = Var[Point2d[Double]]((0, 0))
  val isDragging = Var(false)

  val mouse = MouseInteraction

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  val diagramSelection = DiagramSelectionOps()
  // -------------------------------


  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val rawSVG: Signal[SVGSVGElement] = 
    visibleDOT.flatMapSwitch(_.toSvg)

  // val svgDiagramElement: Signal[ReactiveSvgElement[SVGSVGElement]] =
  //   visibleDOT
  //     .flatMapSwitch(_.toSvg)
  //     .map { svg =>
  //       withLog("[svgDiagramElement]:step 2 (svgWithTransform)"):
  //         SvgDotDiagram.svgWithTransform(
  //           transform,
  //           startNode.signal.map(_.collect { case (id: models.NodeId, pos) => (id, pos) }),
  //           endPos.signal,
  //           isDragging.signal,
  //           mouse.selectionRect.signal,
  //           diagramSelection
  //         )(svg)
  //     }

  private val hiddenNodes = HiddenNodesOps(project.hiddenNodes)

  val hiddenNodesS = hiddenNodes.signal

  // -------------- UI state -----------------
  val leftPanelVisible = Var(true)
  val leftPanelTabIndex = Var(0)
  val shortcutsModalOpen = Var(false)

  // -------- Public API -----------
  def resetView(): Unit =
    zoomValue.set(0.90)
    translateXY.set(SvgUnit.origin)

  def showAllNodes() =
    hiddenNodes.clear()

  def isNodeVisible(id: NodeId) = hiddenNodesS.map(ids => id notIn ids)

  def isEdgeVisible(id: NodeId) =
//    dom.console.log(s"[isEdgeVisible]: $id")
    visibleGraph.map(graph => id in graph.allArrowIds)

  def isSelected(id: NodeId) =
//    dom.console.log(s"[isSelected]: $id")
    diagramSelection.signal.map(ids => id in ids)

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

  def addEdge(from: NodeId, to: NodeId): Unit =
    sourceFlow.fullGraphV.update(_.addEdge(from, to))

  // def handleSvgClick(event: dom.MouseEvent): Unit =
  //   dom.console.log("-------- handleSvgClick --------", event.target)
  //   findSelectableElement(event) match
  //     case None                            => clear()
  //     case Some((nodeId: NodeId, metaKey)) => handleClickOnNode(nodeId)(metaKey)
  //     case Some((Some(arrow), metaKey))    => handleClickOnArrow(arrow)(metaKey)
  //     case _                               => ()



  // def handleMouseDown(event: dom.MouseEvent): Unit =
  //   val clientCoords = (event.clientX, event.clientY)
  //   Var.set(
  //     selectionRect -> Some(SelectionRect(event.clientX, event.clientY, event.clientX, event.clientY)),
  //     isDragging -> false,
  //     endPos     -> clientCoords
  //   )
  //   findSelectableElement(event).foreach:
  //     case (nodeId: NodeId, _) =>
  //       // 1. show node bbox
  //       startNode.set(Some(nodeId, clientCoords))
  //     case (arrow: models.Arrow, _) =>
  //       // 1. show node bbox
  //       startNode.set(Some(arrow, clientCoords))
  //     case _ =>
  //       diagramSelection.clear()

  // def handleMouseMove(event: dom.MouseEvent): Unit =
  //   val clientCoords = (event.clientX, event.clientY)
  //   val buttons = event.buttons
  //   if buttons == 1 then
  //     // only update endX, endY if selectionRect is defined
  //     selectionRect.update(_.map(_.copy(endX = event.clientX, endY = event.clientY)))
  //   else
  //     selectionRect.set(None)

  //   // Check if the left mouse button is pressed
  //   if buttons == 1 && startNode.now().isDefined then
  //     Var.set(
  //       isDragging -> true,
  //       endPos     -> clientCoords
  //     )

  // def handleMouseUp(event: dom.MouseEvent): Unit =
  //   selectionRect.set(None)
  //   val startNodeId = startNode.now().map(_._1)
  //   val endNodeId =
  //     findSelectableElement(event).map(_._1) match
  //       case Some(id: NodeId) => Some(id)
  //       case _                => None

  //   (startNodeId, endNodeId) match
  //     case (None, _)                 => ()
  //     case (Some(startNodeId: NodeId), Some(endNodeId)) if startNodeId != endNodeId =>
  //       addEdge(startNodeId, endNodeId)
  //     case _ =>
  //       // 1. select node
  //       // 2. show node attributes
  //       // 3. keep node bbox

  //   Var.set(
  //     startNode  -> None,
  //     isDragging -> false
  //   )

  // -------- Attribute management -----------
  // top level attributes
  val graphTargetAttributes: Var[Map[String, AttrValue]] =
    sourceFlow.fullGraphV
      .zoom(_.getRootAttributes(AttributeTarget.graph))(
        { (graph, attrs) =>
          graph.updateRootAttributes(AttributeTarget.graph)(attrs)
        }
      )

  val nodeTargetAttributes =
    sourceFlow.fullGraphV.zoom(_.getRootAttributes(AttributeTarget.node))(
      _.updateRootAttributes(AttributeTarget.node)(_)
    )

  val edgeTargetAttributes =
    sourceFlow.fullGraphV.zoom(_.getRootAttributes(AttributeTarget.edge))(
      _.updateRootAttributes(AttributeTarget.edge)(_)
    )

  // individual node attributes
  def nodesAttributes(nodeIds: Set[NodeId]) =
    sourceFlow.fullGraphV.zoom(_.getAttributesById(nodeIds).values): (graph, attrs) =>
      graph.updateAttributes(nodeIds, Attributes(attrs))

  // -------- Diagram actions -----------
  val eventHandlers = wire[EventHandlers]

  def hideSelection() =
    project.hiddenNodes.update(_ ++ diagramSelection.now())

  def deleteSelection() =
    sourceFlow.fullGraphV.update: fullGraph =>
      fullGraph.removeNodes(diagramSelection.now())

  def groupSelection() =
    sourceFlow.fullGraphV.update: fullGraph =>
      fullGraph.addToNewGroup(diagramSelection.now())

  def addNode() =
    sourceFlow.fullGraphV.update: fullGraph =>
      val selection = diagramSelection.now()
      if selection.isEmpty then
        fullGraph.addRandomNode()
      else
        fullGraph.addNodeAndEdgeFrom(selection.head)

  // def addEdgeFromSelection() =
  //   val selection = diagramSelection.now()
  //   if selection.nonEmpty then
  //     sourceFlow.fullGraphV.update: fullGraph =>
  //       val source = selection.head
  //       val targets = selection - source
  //       targets.foldLeft(fullGraph)((g, target) => g.addEdge(source, target))

  def handleKeyDown(ke: KeyboardEvent): Unit =
    ke.key match
      case "Backspace" => deleteSelection()
      case "n"         => addNode()
      case "g"         => groupSelection()
      case "z"         => undoEvent.emit(())
      case "Escape"    => diagramSelection.clear()
      case "h"         => hideSelection()
      // case "e"         => addEdgeFromSelection()
      case _           => ()

  // -------- storage ------------

  private val persistedState: Var[PersistedState] =
    ProjectStorage.loadProjectPersistedState(projectId)

  private def restoreState() =
    val state0 = persistedState.now()
    // Restore ViewerState <~ PersistedStage (which comes from local storage)
    dom.console.debug("restoreState()")
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
  )(wEv: dom.WheelEvent, svgDiagram: dom.SVGSVGElement) =
    val clientHeight = dom.window.innerHeight.max(1)
    val clientWidth = dom.window.innerWidth.max(1)

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        (z - wEv.deltaY / clientHeight).max(0.001)
    else
      val viewBox = svgDiagram.viewBox.baseVal
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
