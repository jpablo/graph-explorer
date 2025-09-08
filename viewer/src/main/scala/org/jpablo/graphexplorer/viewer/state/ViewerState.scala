package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, SvgWithPositions}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.graph.{AttributesOps, ViewerGraph}
import org.jpablo.graphexplorer.viewer.logging.{Level, withLog}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ClientSize.Normal
import org.jpablo.graphexplorer.viewer.state.mouseActions.{AddNewArrowOps, ExtendSelectionOps, MouseActionVar, MoveArrowEndpointOps}
import org.jpablo.graphexplorer.zoomLens
import org.scalajs.dom.svg.SVG

case class ViewerState(
    projectId:                ProjectId,
    graphviz:                 Graphviz,
    writeText:                String => Any = _ => (),
    setTheme:                 String => Unit = _ => (),
    errorBus:                 EventBus[String] = EventBus(),
    infoBus:                  EventBus[String] = EventBus(),
    initialSource:            Option[String] = None,
    initialRightPanelSection: RightPanelSection = RightPanelSection.none,
    initialLeftPanelVisible:  Boolean = false,
    clientSize:               ClientSize = Normal,
    logLevel:                 Level = Level.None
) extends SvgTransformOps,
      DiagramSelectionOps,
      VisibilityOps,
      ExportOps,
      AddNewArrowOps,
      MoveArrowEndpointOps,
      ExtendSelectionOps,
      UIState,
      Persistence:
  given owner: Owner = unsafeWindowOwner

  lazy val project =
    ProjectOps(Var(Project(projectId)))

  val undoEvent: EventBus[Unit]        = EventBus()
  val redoEvent: EventBus[Unit]        = EventBus()
  val editorError: Var[Option[String]] = Var(None)

  // open the sources panel if there is an editor error
  editorError.signal.changes.filter(_.isDefined)
    .foreach(_ => rightPanelActiveSection.set(RightPanelSection.sources))

  // persisted source can be overridden by passing a non-empty initialSource
  val source = initialSource.getOrElse(persistedDiagramState.now().source)

  val phases = InternalPhases(
    graphviz = graphviz,
    initialSource = if source.isEmpty then None else Some(source),
    hiddenNodes = project.hiddenElements.signal,
    resetView = resetView,
    autoFit = autoFit.now,
    editorError = editorError,
    logLevel = logLevel
  )

  val sourceText      = phases.sourceText
  val fullGraph       = phases.fullGraph
  def fullGraphNow()  = phases.fullGraph.observe.now()
  val visibleDOT      = phases.visibleDOT
  def visibleDOTNow() = phases.visibleDOT.observe.now()
  val visibleGraph    = phases.visibleGraph

  val mouseAction = MouseActionVar()

  // 5. Render visible Dot to SVG with position data
  // visibleDOT ~> SvgWithPositions
  private val svgWithPositions: Signal[Option[SvgWithPositions]] =
    visibleDOT.map: dotText =>
      graphviz
        .textToSvg(dotText)
        .toOption

  // Extract just the SVG for compatibility
  // 6. SVG with extra elements: selection rect, etc.
  // svgWithPositions ~> finalSVG
  lazy val finalSVG: Signal[Option[ReactiveSvgElement[SVG]]] =
    svgWithPositions.map(_.map: svgWithPos =>
      withLog("5. [visibleDOT -> SVG]", level = phases.logLevel) {
        SvgCanvas(
          rawSvg = svgWithPos.svg,
          transform = transform,
          viewerOps = this,
          mouseAction = mouseAction,
          edgePositions = svgWithPos.edgePositions
        )
      })

  // ------------- App settings -------------
  // If true, prompt for label before creating a new node (default: true)
  val promptLabelBeforeNewNode: Var[Boolean] = Var(true)

  // ------------- New node flow -------------
  case class PendingNewNode(attributes: Attributes, direction: ArrowDirection)
  val pendingNewNodeV: Var[Option[PendingNewNode]] = Var(None)

  /** Creates a new node, optionally prompting for the label before creation based on settings. */
  def createNodeMaybePrompt(
      attributes: Attributes = Attributes.empty,
      direction:  ArrowDirection = ArrowDirection.forward
  ): Unit =
    if promptLabelBeforeNewNode.now() then
      pendingNewNodeV.set(Some(PendingNewNode(attributes, direction)))
    else
      addNodeWithSmartConnection(attributes, direction)

  // -------- storage ------------
  initializePersistence()

  def nodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    ids.flatMap(fullGraph.observe.now().getNode)

  def allNodeIds(): Set[NodeId] =
    fullGraphNow().nodeIds

  def allArrowIds(): Set[ArrowId] =
    fullGraphNow().arrowIds

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an edge. If the
    * selected element is a group/cluster, the new node will be added to that group. The new node will become the only selected element
    * after creation.
    *
    * @param attributes
    *   The attributes to apply to the new node
    * @param direction
    *   The direction of the arrow when connecting to an existing node (From: existing -> new, To: new -> existing)
    * @return
    *   The result of the operation, which can be:
    *   - None if no action was taken
    *   - Some(NodeAdded) if a standalone node was added
    *   - Some(NodeAndArrowAdded) if a node and an arrow were added
    */
  def addNodeWithSmartConnection(
      attributes: Attributes = Attributes.empty,
      direction:  ArrowDirection = ArrowDirection.forward
  ): Unit =
    phases.fullGraphV.update: fullGraph =>
      val sel                      = selection.now()
      val selectedElementId        = if sel.isEmpty then None else Some(sel.head)
      val (newGraph, newNodeId, _) = fullGraph.addNodeWithSmartConnection(selectedElementId, attributes, direction)
      selection.set2(newNodeId)
      newGraph

  def addArrow(from: NodeId, to: NodeId)(using name: sourcecode.FullName) =
    phases.fullGraphV.update: g =>
      val (g2, _) = g.addArrow(from, to)
      selection.set(ElementIds.from(from))
      g2

  def moveArrowEndpoint(arrowId: ArrowId, newEndpoint: ArrowEndpointId) =
    phases.fullGraphV.update: g =>
      val (g1, newArrowId) = g.moveArrowEndpoint(arrowId, newEndpoint)
      selection.set(ElementIds.from(newArrowId))
      g1

  // -------- Attribute management -----------

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram

  // --- top level attributes ---

  def graphType: Var[GraphType] =
    phases.fullGraphV.zoomLazy(_.tpe)((g, tpe) => g.copy(tpe = tpe))

  def graphLayout: Signal[Layout] =
    graphAttributes.map(_.getAs(Layout))

  def graphRankDir: Signal[Rankdir] =
    graphAttributes.map(_.getAs(Rankdir))

  def graphAttributes: Signal[Attributes] =
    fullGraph.map(_.elements.graphAttributes)

  def nodeShape: Signal[Shape] =
    defaults(AttributeTarget.node).map(_.getAs(Shape))

  def updateLabel(elementId: ElementId, label: String): Unit =
    elementAttributesUpdates(ElementIds.from(elementId)).set:
      AttributeUpdates.of(Label -> TextUtils.escape(label))

  def defaults(target: AttributeTarget): Signal[Attributes] =
    fullGraph.map(_.getDefaultAttributes(target))

  def diagramAttributesUpdates: Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.diagramAttributesUpdates)

  def defaultAttributesUpdates(target: AttributeTarget): Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.defaultAttributesUpdates(target))

  def elementAttributesUpdates(elementIds: ElementIds): Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(elementIds))

  def resetDefaultAttributes(target: AttributeTarget): Unit =
    phases.fullGraphV.update: graph =>
      graph.modifyDefaultAttributes(target).setTo(Attributes.empty)

  // Theme management
  lazy val currentTheme: Var[Option[String]] = Var(None)

  currentTheme.signal.foreach: themeName =>
    themeName.foreach(setTheme)

end ViewerState
