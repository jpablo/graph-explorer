package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.domUtils.DOMPoint
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Attributes, GroupId, NodeId, ViewerNode}
import org.scalajs.dom.{SVGMatrix, SVGRect}
import upickle.default.*

case class ViewerState(
    projectId:     ProjectId,
    writeText:     String => Any = _ => (),
    initialSource: String = ""
):
  given owner: Owner = OneTimeOwner(() => ())

  val project =
    ProjectOps(Var(Project(projectId)))

  val translateXY = Var(SvgUnit.origin)
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  val sourceFlow = SourceFlow(initialSource, project.hiddenNodes.signal, resetView)

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

  val hiddenNodes = HiddenNodesOps(project.hiddenNodes)

  val hiddenNodesS = hiddenNodes.signal

  // -------------- UI state -----------------
  val rightPanelVisible = Var(true)
  val rightPanelTabIndex = Var(0)
  val shortcutsModalOpen = Var(false)
  val leftPanelVisible = Var(true)

  // -------- Attribute management -----------
  // top level attributes
  val graphTargetAttributes: Var[Attributes] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(AttributeTarget.graph))(
        { (graph, attrs) =>
          graph.setRootAttributes(AttributeTarget.graph)(attrs)
        }
      )

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram
  val nodeTargetAttributes =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(AttributeTarget.node))(_.setRootAttributes(AttributeTarget.node)(_))

  val edgeTargetAttributes =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(AttributeTarget.edge))(_.setRootAttributes(AttributeTarget.edge)(_))

  // individual node attributes
  def nodesAttributes(nodeIds: Set[NodeId]): Var[Attributes] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getAttributesById(nodeIds))((graph, attrs) => graph.updateAttributes(nodeIds, attrs))

  // 6. SVG with extra elements: selection rect, etc.
  val finalSVG: Signal[ReactiveSvgElement[dom.SVGSVGElement]] =
    rawSVG.map: svg =>
      SvgCanvas(svg, transform, diagramSelection, addNode, graphTargetAttributes)

  // -------- Public API -----------

  def getNodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    val nodes = fullGraph.observe().now().nodeById
    ids.flatMap(id => nodes.get(id))

  def resetView(): Unit =
    zoomValue.set(0.90)
    translateXY.set(SvgUnit.origin)

  def showAllNodes() =
    hiddenNodes.clear()

  def isNodeVisible(id: NodeId) = hiddenNodesS.map(ids => id notIn ids)

  def isEdgeVisible(id: NodeId) =
    visibleGraph.map(graph => id in graph.allArrowIds)

  def isSelected(id: NodeId) =
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
      diagramSelection.set(Set(a.id))
      g2

  def updateHiddenFromSelection(f: (HiddenNodes, Set[NodeId], ViewerGraph) => HiddenNodes) =
    project.hiddenNodes.update(f(_, diagramSelection.now(), sourceFlow.fullGraph.now()))

  def hideSelection() =
    project.hiddenNodes.update(_ ++ diagramSelection.now())

  def hideNonSelectedNodes() =
    updateHiddenFromSelection((h, sel, g) => h ++ (g.allNodeIds -- sel))

  def showAllSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allSuccessorsGraph(sel).allNodeIds)

  def showDirectSuccessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directSuccessorsGraph(sel).allNodeIds)

  def showAllPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.allPredecessorsGraph(sel).allNodeIds)

  def showDirectPredecessors() =
    updateHiddenFromSelection((h, sel, g) => h -- g.directPredecessorsGraph(sel).allNodeIds)

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

  case class IdsByKind(
      clusters: Seq[NodeId] = Seq.empty,
      nodes:    Seq[NodeId] = Seq.empty,
      arrows:   Seq[NodeId] = Seq.empty
  )

  def classifyNodes(mixed: Seq[NodeId]): IdsByKind =
    val (arrows, notArrows) = mixed.partition(NodeId.isArrowId)
    val (clusterIds, nodeIds) = notArrows.partition(NodeId.isClusterId)
    IdsByKind(
      clusters = clusterIds,
      nodes    = nodeIds,
      arrows   = arrows
    )

  def addSelectionToGroup() =
    val classified = classifyNodes(diagramSelection.now().toSeq)
    for groupNodeId <- classified.clusters.headOption do
      sourceFlow.fullGraphV.update(_.addToGroup(GroupId(groupNodeId.value), classified.nodes))

  def ungroupSelection() =
    sourceFlow.fullGraphV.update(_.ungroupSelection(diagramSelection.now()))

  def selectGroupMembers() =
    val selection = diagramSelection.now()
    val classified = classifyNodes(selection.toSeq)
    
    // If we have clusters/groups in the selection, find their members
    if classified.clusters.nonEmpty then
      val groupIds = classified.clusters.map(id => GroupId(id.value)).toSet
      val fullGraphSnapshot = sourceFlow.fullGraph.now()
      
      // Get all node ids that are members of the selected groups
      val memberNodeIds = fullGraphSnapshot.data.getDirectChildren(groupIds)
        .toSet.map(id => NodeId(id.value))
      
      // Keep the original groups/clusters in the selection and add all members
      diagramSelection.set(selection ++ memberNodeIds)

  def clearSelection() =
    diagramSelection.clear()

  def keepRootsOnly() =
    project.hiddenNodes.update(_ ++ (sourceFlow.fullGraph.now().allNodeIds -- sourceFlow.fullGraph.now().roots))

  def hideAllNodes() =
    project.hiddenNodes.update(_ ++ sourceFlow.fullGraph.now().allNodeIds)

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
      fullGraph.removeNodes(diagramSelection.now())

  /** Duplicates the currently selected nodes. Creates new nodes with the same attributes as the selected nodes and
    * places them in the same groups. The newly created nodes become the selected elements after duplication.
    */
  def duplicateSelection() =
    sourceFlow.fullGraphV.update: fullGraph =>
      val selection = diagramSelection.now()
      if selection.isEmpty then
        fullGraph
      else
        // Filter out any non-node elements (like edges)
        val nodesToDuplicate = selection.filter(id => id in fullGraph.data.nodes)
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
              val finalGraph = updatedGraph.updateAttributes(Set(newNodeId), originalNode.attributes)
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
      val (newGraph, newNodeId) = if selection.isEmpty then
        fullGraph.addRandomNode()
      else
        val source = selection.head
        // Only proceed if selected ID is a valid node in the graph
        if source in fullGraph.data.nodes then
          fullGraph.addNodeAndEdgeFrom(source)
        else
          fullGraph.addRandomNode(Some(GroupId(source.value)))
      diagramSelection.set(Set(newNodeId))
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
        addEdge(start.nodeId, start.nodeId)
      else if sel.size == 2 then
        addEdge(start.nodeId, (sel - start.nodeId).head)

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
    rightPanelVisible.set(state0.rightPanelVisible)
    rightPanelTabIndex.set(state0.sideBarTabIndex)
    leftPanelVisible.set(state0.leftPanelVisible)
    // synchronize ViewerState ~> PersistedStage
    project.hiddenNodes.signal
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
    hiddenNodes:       Set[NodeId] = Set.empty,
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
      hiddenNodes       = Set.empty,
      projectName       = "Untitled",
      source            = minimalGraphText,
      rightPanelVisible = true,
      sideBarTabIndex   = 0,
      leftPanelVisible  = true
    )

object ViewerState:

  def handleWheel(
      zoomValue:   Var[Double],
      translateXY: Var[Point2d[SvgUnit]]
  )(wEv: dom.WheelEvent, viewBox: SVGRect) =
    val clientHeight = dom.window.innerHeight.max(1)
    val clientWidth = dom.window.innerWidth.max(1)

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        (z - wEv.deltaY / clientHeight).max(0.001)
    else
      val z = zoomValue.now()
      val scale = (viewBox.width / clientWidth).max(viewBox.height / clientHeight)
      val svgDelta = (SvgUnit(wEv.deltaX * scale / z), SvgUnit(wEv.deltaY * scale / z))
      translateXY.update(_ - svgDelta)

  /** Converts client (screen) coordinates to SVG coordinates by applying the inverse of the SVG element's
    * transformation matrix.
    * @param clientX
    *   The x-coordinate in client (screen) space
    * @param clientY
    *   The y-coordinate in client (screen) space
    * @param svgElement
    *   The SVG element to transform coordinates relative to
    * @return
    *   An SVGPoint containing the transformed coordinates in SVG space
    */
  def toSVGCoords(
      clientX:   Double, // px
      clientY:   Double, // px
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
