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
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Attributes, NodeId}
import org.scalajs.dom.{KeyboardEvent, SVGSVGElement}
import upickle.default.*
import org.jpablo.graphexplorer.viewer.domUtils.DOMPoint
import org.scalajs.dom.SVGMatrix
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import scala.scalajs.js
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

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  val diagramSelection = DiagramSelectionOps()
  // -------------------------------


  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val rawSVG: Signal[SVGSVGElement] =
    visibleDOT.flatMapSwitch(_.toSvg)

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
    sourceFlow.fullGraphV.update{ g =>
      val (g2, a) = g.addEdge(from, to)
      diagramSelection.set(Set(a.id))
      g2
    }

  // -------- Attribute management -----------
  // top level attributes
  val graphTargetAttributes: Var[Map[String, AttrValue]] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(AttributeTarget.graph))(
        { (graph, attrs) =>
          graph.updateRootAttributes(AttributeTarget.graph)(attrs)
        }
      )

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram
  val nodeTargetAttributes =
    sourceFlow.fullGraphV.zoomLazy(_.getRootAttributes(AttributeTarget.node))(
      _.updateRootAttributes(AttributeTarget.node)(_)
    )

  val edgeTargetAttributes =
    sourceFlow.fullGraphV.zoomLazy(_.getRootAttributes(AttributeTarget.edge))(
      _.updateRootAttributes(AttributeTarget.edge)(_)
    )

  // individual node attributes
  def nodesAttributes(nodeIds: Set[NodeId]): Var[Map[String, AttrValue]] =
    sourceFlow.fullGraphV.zoomLazy( graph =>
      graph.getAttributesById(nodeIds).values
    ): (graph, attrs) =>
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
      val (newGraph, newNodeId) = if selection.isEmpty then
        fullGraph.addRandomNode()
      else
        fullGraph.addNodeAndEdgeFrom(selection.head)
      diagramSelection.set(Set(newNodeId))
      newGraph

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


  val selectionRect: Var[Option[SelectionRect]] = Var(None)

  def startSelection(pos: Point2d[Double], shift: Boolean, action: Action): Unit =
    selectionRect.set(Some(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift, action)))

  def updateSelection(pos: Point2d[Double], shift: Boolean): Unit =
    selectionRect.update(_.map(_.copy(endX = pos.x, endY = pos.y, shift = shift)))

  def endSelection(): Unit =
    selectionRect.set(None)

  // Add a docstring AI!
  def handleSelectionRectangleUpdate(rect: SelectionRect, selectableElements: Seq[SelectableElement], elements: js.Array[dom.Element]) =
    rect.action match
      case Action.Selection =>
        val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.nodeId).toSet
        if nodesInRect.nonEmpty then
          if rect.shift then
            diagramSelection.add(nodesInRect)
          else
            diagramSelection.set(nodesInRect)
        else if !rect.shift then
            diagramSelection.clear()

      case Action.Edge(start) =>
        // Make sure only start or (start,end) nodes are selected when creating a new edge
        findNode(rect, elements) match
          case Some(end) => diagramSelection.set(Set(start.nodeId, end))
          case None      => diagramSelection.set(Set(start.nodeId))

  /**
   * Finds the node ID at the given selection rectangle's end point
   */
  private def findNode(rect: SelectionRect, elements: js.Array[dom.Element]): Option[NodeId] =
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest("g.node, g.edge")))
      .distinct
      .map(SelectableElement.fromDomElement)
      .collectFirst { case Some(n @ NodeElement(_)) => n.nodeId }


  /** Checks if a selectable element intersects with a selection rectangle
   *
   * @param elem The selectable element to check
   * @param rect The selection rectangle in client coordinates
   * @return true if the element's bounding box intersects with the selection rectangle
   *
   * The method:
   * 1. Gets the element's bounding box in client coordinates
   * 2. Normalizes the selection rect coordinates to handle any direction of dragging
   * 3. Uses a standard rectangle intersection test
   */
  private def isNodeInRect(elem: SelectableElement, rect: SelectionRect): Boolean =
    val bbox = elem.get.getBoundingClientRect()
    val normalizedRect = (
      x = rect.startX.min(rect.endX),
      y = rect.startY.min(rect.endY),
      width = math.abs(rect.endX - rect.startX),
      height = math.abs(rect.endY - rect.startY)
    )
    !(bbox.right < normalizedRect.x ||
      bbox.left > normalizedRect.x + normalizedRect.width ||
      bbox.bottom < normalizedRect.y ||
      bbox.top > normalizedRect.y + normalizedRect.height)



  def handleMouseUp(ev: dom.MouseEvent): Unit =
    val rectOpt = selectionRect.now()
    selectionRect.set(None)
    for rect <- rectOpt do
      rect.action match
        case Action.Edge(start) =>
          val sel = diagramSelection.now()
          diagramSelection.clear()
          // TODO: finish this using findNode
          val mouseOverInitialNode = isNodeInRect(start, rect)
          if sel.size == 1 && mouseOverInitialNode then
            addEdge(start.nodeId, start.nodeId)
          else if sel.size == 2 then
            addEdge(start.nodeId, (sel - start.nodeId).head)
        case _ => ()


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

  /** Converts client (screen) coordinates to SVG coordinates by applying the inverse of the SVG element's transformation matrix.
   * @param clientX The x-coordinate in client (screen) space
   * @param clientY The y-coordinate in client (screen) space
   * @param svgElement The SVG element to transform coordinates relative to
   * @return An SVGPoint containing the transformed coordinates in SVG space
   */
  def toSVGCoords(
      clientX:    Double, // px
      clientY:    Double, // px
      screenCtm: SVGMatrix
  ): DOMPoint =
    val point = new DOMPoint(clientX, clientY)
    point.matrixTransform(screenCtm.inverse())

  // def toSVGCoords(
  //     rect:    dom.SVGRect, // px
  //     svgElement: SVGSVGElement
  // ): dom.SVGRect =
  //   val rect = svgElement.createSVGRect()
  //   val p0 = toSVGCoords(rect.x, rect.y, svgElement)
  //   val p1 = toSVGCoords(rect.width, rect.height, svgElement)
  //   rect.x = p0.x
  //   rect.y = p0.y
  //   rect.width = p1.x
  //   rect.height = p1.y
  //   rect

end ViewerState
