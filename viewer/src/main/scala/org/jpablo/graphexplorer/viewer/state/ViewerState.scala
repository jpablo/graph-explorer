package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{
  ArrowId,
  AttributesUpdates,
  ElementId,
  ElementIds,
  GroupId,
  NodeId,
  ViewerNode
}
import org.jpablo.graphexplorer.viewer.utils.SvgPoint
import org.scalajs.dom.SVGRect
import upickle.default.*

import scala.util.Try

case class ViewerState(
    projectId:     ProjectId,
    writeText:     String => Any = _ => (),
    initialSource: String = ""
):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(projectId)))

  val translateXY = Var(SvgPoint.origin)
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  val sourceFlow = SourceFlow(initialSource, project.hiddenElements.signal, resetView)

  val undoEvent: EventBus[Unit] = EventBus()
  val redoEvent: EventBus[Unit] = EventBus()

  val sourceText = sourceFlow.sourceText
  val fullGraph = sourceFlow.fullGraph
  private val visibleDOT = sourceFlow.visibleDOT
  val visibleGraph = sourceFlow.visibleGraph

  // -------------------------------
  // this should be a subset of visibleNodesV keys
  val diagramSelection = DiagramSelectionOps()
  // -------------------------------

  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val rawSVG: Signal[dom.SVGSVGElement] =
    visibleDOT.flatMapSwitch(_.toSvg)

  val hiddenNodes = HiddenNodesOps(project.hiddenElements)

  val hiddenNodesS = hiddenNodes.signal

  // -------------- UI state -----------------
  val rightPanelVisible = Var(true)
  val rightPanelTabIndex = Var(0)
  val shortcutsModalOpen = Var(false)
  val leftPanelVisible = Var(true)

  // -------- Attribute management -----------

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram

  // --- top level attributes ---
  def rootTargetAttributesUpdates(target: AttributeTarget): Var[AttributesUpdates] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(target).toUpdates): (graph, updates) =>
        graph.updateRootAttributes(target)(updates.applyUpdatesTo)

  // individual node attributes
  def elementAttributes(elementIds: ElementIds): Var[AttributesUpdates] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getAttributesById(elementIds))((graph, updates) => graph.updateAttributes(elementIds, updates))

  // 6. SVG with extra elements: selection rect, etc.
  val finalSVG: Signal[ReactiveSvgElement[dom.SVGSVGElement]] =
    rawSVG.map: svg =>
      def getRankdir =
        sourceFlow.fullGraphV.now().data.root.attributes
          .get(Rankdir.attrId)
          .map(_.value.toString)
          .map(str => Try(Rankdir.valueOf(str)).getOrElse(Rankdir.default))
          .getOrElse(Rankdir.default)
      SvgCanvas(svg, transform, diagramSelection, addNode, () => getRankdir)

  // -------- Public API -----------

  def getNodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    val nodes = fullGraph.observe().now().nodeById
    ids.flatMap(id => nodes.get(id))

  def resetView(): Unit =
    Var.set(
      zoomValue   -> 0.90,
      translateXY -> SvgPoint.origin
    )

  def showAllNodes() =
    hiddenNodes.clear()

  def isNodeVisible(id: NodeId) = hiddenNodesS.map(ids => id notIn ids)

  def isEdgeVisible(id: ArrowId) =
    visibleGraph.map(graph => id in graph.allArrowIds)

  def isSelected(id: ElementId) =
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
    sourceFlow.fullGraphV.update: g =>
      val (g2, a) = g.addEdge(from, to)
      diagramSelection.set(ElementIds.from(a.id))
      g2

  def updateHiddenFromSelection(f: (HiddenElements, ElementIds, ViewerGraph) => HiddenElements) =
    project.hiddenElements.update(f(_, diagramSelection.now(), sourceFlow.fullGraph.now()))

  def hideSelection() =
    project.hiddenElements.update(_ ++ diagramSelection.now())

//  extension (ids: Set[? <: ElementId])
//    def --(a: Set[? <: ElementId]) = (ElementIds(ids) -- ElementIds(a)).ids

  def hideNonSelectedNodes() =
    updateHiddenFromSelection((h, sel, g) => h ++ (g.allNodeIds -- sel.nodeIds))

  def showAllSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allSuccessorsGraph(sel.nodeIds).allNodeIds)

  def showDirectSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directSuccessorsGraph(sel.nodeIds).allNodeIds)

  def showAllPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allPredecessorsGraph(sel.nodeIds).allNodeIds)

  def showDirectPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directPredecessorsGraph(sel.nodeIds).allNodeIds)

  def selectSuccessors() =
    diagramSelection.selectSuccessors(sourceFlow.fullGraph.now(), hiddenNodes.now())

  def selectPredecessors() =
    diagramSelection.selectPredecessors(sourceFlow.fullGraph.now(), hiddenNodes.now())

  def selectDirectSuccessors() =
    diagramSelection.selectDirectSuccessors(sourceFlow.fullGraph.now(), hiddenNodes.now())

  def selectDirectPredecessors() =
    diagramSelection.selectDirectPredecessors(sourceFlow.fullGraph.now(), hiddenNodes.now())

  def groupSelectedNodes() =
    sourceFlow.fullGraphV.update(_.addToNewGroup(diagramSelection.now()))

  def addSelectionToGroup() =
    val classified = diagramSelection.now().classify
    for groupNodeId <- classified.clusters.headOption do
      sourceFlow.fullGraphV.update(_.addToGroup(groupNodeId, classified.nodes.toSeq))

  def ungroupSelection() =
    sourceFlow.fullGraphV.update(_.ungroupSelection(diagramSelection.now()))

  def selectGroupMembers() =
    val selection = diagramSelection.now()
    val classified = selection.classify

    // If we have clusters/groups in the selection, find their members
    if classified.clusters.nonEmpty then
      val groupIds = classified.clusters
      val fullGraphSnapshot = sourceFlow.fullGraph.now()

      // Get all node ids that are members of the selected groups
      val memberNodeIds = fullGraphSnapshot.data.getAllChildren(groupIds)

      // Keep the original groups/clusters in the selection and add all members
      diagramSelection.set(selection ++ memberNodeIds)

  def clearSelection() =
    diagramSelection.clear()

  def keepRootsOnly() =
    project.hiddenElements.update(_ ++ (sourceFlow.fullGraph.now().allNodeIds -- sourceFlow.fullGraph.now().roots))

  def hideAllNodes() =
    project.hiddenElements.update(_ ++ sourceFlow.fullGraph.now().allNodeIds)

  def selectAllVisibleNodes() =
    val visibleNodes = sourceFlow.visibleGraph.observe().now().allNodeIds
    diagramSelection.set(visibleNodes)

  def selectAllVisibleArrows() =
    val visibleArrows = sourceFlow.visibleGraph.observe().now().allArrowIds
    diagramSelection.set(visibleArrows)

  def selectAllVisibleGroups() =
    val visibleGraph = sourceFlow.visibleGraph.observe().now()
    val groupIds = visibleGraph.data.groups.keys
      .filter(_ != visibleGraph.data.rootId) // Exclude the root group
      .toSet
    diagramSelection.set(groupIds)

  def selectAll() =
    val visibleGraph = sourceFlow.visibleGraph.observe().now()
    val nodes = visibleGraph.allNodeIds
    val edges = visibleGraph.allArrowIds
    val groups = visibleGraph.data.groups.keys
      .filter(_ != visibleGraph.data.rootId) // Exclude the root group
      .toSet
    diagramSelection.set(nodes ++ edges ++ groups)

  def showOnlyGroup() =
    selectGroupMembers()
    hideNonSelectedNodes()
    clearSelection()

  def copyAsFullDiagramSVG(): Unit =
    for html <- finalSVG.map(_.ref.outerHTML) do
      writeText(html)

  def copySelectionAsSVG(): Unit =
    for svgElem <- finalSVG do
      writeText(SvgElementOps(svgElem.ref).toSVGTextWithIds(diagramSelection.now()))

  def copyAsDOT(): Unit =
    for dot <- visibleDOT do
      writeText(dot.value)

  def copyAsJSON(): Unit =
    for ast <- sourceFlow.visibleAST do
      writeText(writeJs(ast).toString)

  def deleteSelection() =
    sourceFlow.fullGraphV.update: fullGraph =>
      fullGraph.removeElements(diagramSelection.now())

  /** Duplicates the currently selected nodes. Creates new nodes with the same attributes as the selected nodes and
    * places them in the same groups. The newly created nodes become the selected elements after duplication.
    */
  def duplicateSelection() =
    sourceFlow.fullGraphV.update: fullGraph =>
      val selection: SelectedNodes = diagramSelection.now()
      if selection.isEmpty then
        fullGraph
      else
        // Filter out any non-node elements (like edges)
        val classified = selection.classify
        val nodesToDuplicate = classified.nodes
        if nodesToDuplicate.isEmpty then
          fullGraph
        else
          // Create a new graph with the duplicated nodes
          val (newGraph, newNodeIds) = nodesToDuplicate.foldLeft((fullGraph, Set.empty[NodeId])) {
            case ((graph, newIds), originalId) =>
              // Get the original node's attributes and group
              val originalNode = graph.data.nodes(originalId)
              val groupId = Some(graph.data.getMembership(originalId))
              // Create a new node with a random ID
              val (updatedGraph, newNodeId) = graph.addRandomNode(groupId)
              // Update the new node with the original node's attributes
              val finalGraph =
                updatedGraph.updateAttributes(ElementIds.from(newNodeId), originalNode.attributes.toUpdates)
              // Add the new node ID to our collection
              (finalGraph, newIds + newNodeId)
          }

          // Select the newly created nodes
          diagramSelection.set(newNodeIds)
          newGraph

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an
    * edge. If the selected element is a group/cluster, the new node will be added to that group. The new node will
    * become the only selected element after creation.
    */
  def addNode() =
    sourceFlow.fullGraphV.update: fullGraph =>
      val selection = diagramSelection.now()
      val (newGraph, newNodeId) =
        if selection.isEmpty then
          fullGraph.addRandomNode()
        else
          val source = selection.head
          // Only proceed if selected ID is a valid node in the graph
          source match
            case id: NodeId  => fullGraph.addNodeAndEdgeFrom(id)
            case id: GroupId => fullGraph.addRandomNode(Some(id))
            case _: ArrowId  => fullGraph.addRandomNode()
      diagramSelection.set(newNodeId)
      newGraph

  def handleMouseUp(ev: dom.MouseEvent): Unit =
    val lineAction = diagramSelection.selectionRectLine.now()
    diagramSelection.endSelectionArea()
    diagramSelection.endSelectionLine()
    for action <- lineAction do
      val start = action.start
      val sel = diagramSelection.now()
      diagramSelection.clear()

      // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
      val bbox = start.get.getBoundingClientRect()
      val mouseReleasePoint = (ev.clientX, ev.clientY)
      val isMouseInsideSourceNode =
        mouseReleasePoint._1 >= bbox.left &&
          mouseReleasePoint._1 <= bbox.right &&
          mouseReleasePoint._2 >= bbox.top &&
          mouseReleasePoint._2 <= bbox.bottom

      if sel.size == 1 && isMouseInsideSourceNode then
        start.nodeId.foreach(nodeId => addEdge(nodeId, nodeId))
      else if sel.size == 2 then
        (sel - start.elementId).head.asNodeId.foreach(end => addEdge(start.nodeId.get, end))

  // -------- storage ------------

  private val persistedState: Var[PersistedState] =
    ProjectStorage.loadProjectPersistedState(projectId)

  private def restoreState() =
    val state0 = persistedState.now()
    // Restore ViewerState <~ PersistedStage (which comes from local storage)
    dom.console.debug("restoreState()")
    sourceText.set(state0.source)
    project.name.set(state0.projectName)
    project.hiddenElements.set(state0.hiddenNodes)
    rightPanelVisible.set(state0.rightPanelVisible)
    rightPanelTabIndex.set(state0.sideBarTabIndex)
    leftPanelVisible.set(state0.leftPanelVisible)
    // synchronize ViewerState ~> PersistedStage
    project.hiddenElements.signal
      .combineWith(
        project.name.signal,
        sourceText.signal,
        rightPanelVisible.signal,
        rightPanelTabIndex.signal,
        leftPanelVisible.signal
      )
      .map(PersistedState.apply)
      .foreach(persistedState.set)
  end restoreState

  restoreState()

end ViewerState

case class PersistedState(
    hiddenNodes:       HiddenElements = ElementIds(),
    projectName:       String = "",
    source:            String = "",
    rightPanelVisible: Boolean = true,
    sideBarTabIndex:   Int = 0,
    leftPanelVisible:  Boolean = true
) derives ReadWriter

object PersistedState:
  private val minimalGraphText = "digraph G {\n}"
  val empty =
    PersistedState(
      hiddenNodes       = ElementIds(),
      projectName       = "Untitled",
      source            = minimalGraphText,
      rightPanelVisible = true,
      sideBarTabIndex   = 0,
      leftPanelVisible  = true
    )

object ViewerState:

  def handleWheel(
      zoomValue:   Var[Double],
      translateXY: Var[SvgPoint]
  )(wEv: dom.WheelEvent, viewBox: SVGRect) =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth = dom.window.innerWidth max 1

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        z - wEv.deltaY / clientHeight max 0.001
    else
      val z = zoomValue.now()
      val scale = viewBox.width / clientWidth max viewBox.height / clientHeight
      val delta = SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z)
      translateXY.update(_ - delta)

end ViewerState
