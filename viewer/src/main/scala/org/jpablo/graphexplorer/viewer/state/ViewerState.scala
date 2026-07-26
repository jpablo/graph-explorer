package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat, DiagramLanguageInfo, DiagramRenderInputs}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, SvgWithPositions}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.graph.{AttributesOps, ViewerGraph}
import org.jpablo.graphexplorer.viewer.logging.{Level, withLog}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ClientSize.Normal
import org.jpablo.graphexplorer.viewer.state.mouseActions.{AddNewArrowOps, ExtendSelectionOps, MouseActionVar, MoveArrowEndpointOps}
import org.jpablo.graphexplorer.zoomLens
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext.Implicits.global

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
)(
    // All of this state's subscriptions hang off this owner. Pass a killable owner
    // (e.g. ManualOwner killed on unmount) so navigating away releases the whole
    // instance instead of pinning it for the session (previous leak: one full
    // ViewerState retained per project visit).
    using val owner: Owner = unsafeWindowOwner
) extends SvgTransformOps,
      DiagramSelectionOps,
      VisibilityOps,
      ExportOps,
      AddNewArrowOps,
      MoveArrowEndpointOps,
      ExtendSelectionOps,
      UIState,
      Persistence:

  lazy val project =
    ProjectOps(Var(Project(projectId)))

  val undoEvent: EventBus[Unit]        = EventBus()
  val redoEvent: EventBus[Unit]        = EventBus()
  val editorNotice: Var[Option[EditorNotice]] = Var(None)

  // Open the sources panel only for genuine errors. Info notices (render-only diagram
  // kinds) are expected — yanking the panel open for them would punish normal usage.
  editorNotice.signal.changes.filter(_.exists(_.isError))
    .foreach(_ => rightPanelActiveSection.set(RightPanelSection.sources))

  // persisted source can be overridden by passing a non-empty initialSource
  val source = initialSource.getOrElse(persistedDiagramState.now().source)

  // Registry of diagram backends. InternalPhases depends only on this abstraction, not on concrete backends.
  private val languages = DefaultDiagramLanguages(graphviz)

  val phases = InternalPhases(
    languages = languages,
    initialSource = if source.isEmpty then None else Some(source),
    hiddenNodes = project.hiddenElements.signal,
    resetView = resetView,
    autoFit = autoFit.now,
    editorNotice = editorNotice,
    logLevel = logLevel
  )

  val sourceText      = phases.sourceText
  val fullGraph       = phases.fullGraph
  val visibleText     = phases.visibleText
  val visibleGraph    = phases.visibleGraph
  val currentFormat   = phases.currentFormat
  val formatSelection = phases.formatSelection
  val selectionStrategy = phases.selectionStrategy

  // Shared read-once handles. Signal#observe allocates a PERMANENT owner-bound
  // subscription per call, so ad-hoc `.observe.now()` in per-event handlers leaked
  // unboundedly (one subscription per mouse-move). Read through these instead.
  private lazy val fullGraphObs         = fullGraph.observe
  private lazy val visibleGraphObs      = visibleGraph.observe
  private lazy val selectionStrategyObs = selectionStrategy.observe
  private lazy val graphRankDirObs      = graphRankDir.observe
  private lazy val currentFormatObs     = currentFormat.observe
  def fullGraphNow(): ViewerGraph         = fullGraphObs.now()
  def visibleGraphNow(): ViewerGraph      = visibleGraphObs.now()
  def selectionStrategyNow(): SelectableElementStrategy = selectionStrategyObs.now()
  def graphRankDirNow(): Rankdir          = graphRankDirObs.now()
  def currentFormatNow(): DiagramFormat   = currentFormatObs.now()
  def setDiagramFormat(format: DiagramFormat): Unit =
    formatSelection.set(format)

  /** The title shown for this project: the user's chosen name, or — while the project is
    * still unnamed — the diagram's own declared title (Mermaid frontmatter / `title` line,
    * DOT graph label). Display-only substitution: the stored name changes only when the
    * user renames, so a rename always wins and a source-title change follows live.
    */
  val displayTitle: Signal[String] =
    project.name.signal.combineWithFn(sourceText.signal, formatSelection.signal): (name, source, format) =>
      if name.trim.nonEmpty && name != PersistedDiagramState.defaultProjectName then name
      else languages.forFormat(format).extractTitle(source).getOrElse(name)

  /** Presentation metadata for every available format, in display order (drives the selector UI). */
  lazy val availableFormats: List[(DiagramFormat, DiagramLanguageInfo)] =
    languages.all.map(backend => backend.format -> backend.info)

  /** Presentation metadata for a single format. */
  def formatInfo(format: DiagramFormat): DiagramLanguageInfo =
    languages.forFormat(format).info

  private def elementExists(graph: ViewerGraph, id: ElementId): Boolean =
    id match
      case nodeId: NodeId   => graph.nodes.contains(nodeId)
      case groupId: GroupId => graph.groups.contains(groupId)
      case arrowId: ArrowId => graph.arrows.contains(arrowId)

  // Prune selected ids that no longer exist in the graph. Uses keepOnly (a Var.update)
  // so the filter runs when ITS transaction executes: a selection.set made inside the
  // same graph update (e.g. combineIntoRecord selecting the new record) has already
  // landed by then and is preserved. A snapshot-based remove() here clobbered it.
  fullGraph.changes.foreach { graph =>
    selection.keepOnly(id => elementExists(graph, id))
  }

  val mouseAction = MouseActionVar()

  // 5. Render visible content to SVG with position data.
  // Each backend owns its render policy (DOT: synchronous from visibleText; Mermaid: async from
  // sourceText, validated), so the format dispatch lives in the registry, not here.
  private val renderInputs = DiagramRenderInputs(
    visibleText = visibleText,
    sourceText = sourceText.signal,
    hasHiddenElements = project.hiddenElements.signal.map(_.nonEmpty).distinct
  )

  private[state] val svgWithPositions: Signal[Option[SvgWithPositions]] =
    phases.currentFormat.flatMapSwitch(languages.forFormat(_).render(renderInputs))

  // Extract just the SVG for compatibility
  // 6. SVG with extra elements: selection rect, etc.
  // svgWithPositions ~> finalSVG
  lazy val finalSVG: Signal[Option[ReactiveSvgElement[SVG]]] =
    svgWithPositions.combineWith(selectionStrategy).map: (svgOpt, strategy) =>
      svgOpt.map: svgWithPos =>
        withLog("5. [visibleText -> SVG]", level = phases.logLevel) {
          SvgCanvas(
            rawSvg = svgWithPos.svg,
            transform = transform,
            viewerOps = this,
            mouseAction = mouseAction,
            edgePositions = svgWithPos.edgePositions,
            strategy = strategy
          )
        }

  // One-shot read of the current SVG (for exports). A permanent handle rather than
  // per-call observe; previously each copy-click subscribed forever, so every later
  // render silently overwrote the clipboard.
  private lazy val finalSVGObs = finalSVG.observe
  def finalSVGNow(): Option[ReactiveSvgElement[SVG]] = finalSVGObs.now()

  // ------------- App settings -------------
  // If true, prompt for label before creating a new node (default: true)
  val promptLabelBeforeNewNode: Var[Boolean] = Var(true)

  // If true, prompt for label before creating a new group (default: true)
  val promptLabelBeforeNewGroup: Var[Boolean] = Var(true)

  // ------------- New node flow -------------
  case class PendingNewNode(attributes: Attributes, direction: ArrowDirection)
  val pendingNewNodeV: Var[Option[PendingNewNode]] = Var(None)

  // ------------- New group flow -------------
  case class PendingNewGroup(elementIds: ElementIds)
  val pendingNewGroupV: Var[Option[PendingNewGroup]] = Var(None)

  /** Creates a new node, optionally prompting for the label before creation based on settings. */
  def createNodeMaybePrompt(
      attributes: Attributes = Attributes.empty,
      direction:  ArrowDirection = ArrowDirection.forward
  ): Unit =
    if promptLabelBeforeNewNode.now() then
      pendingNewNodeV.set(Some(PendingNewNode(attributes, direction)))
    else
      addNodeWithSmartConnection(attributes, direction)

  /** Creates a new group from the current selection, optionally prompting for the label before creation based on settings. */
  def createGroupMaybePrompt(elementIds: ElementIds): Unit =
    if promptLabelBeforeNewGroup.now() then
      pendingNewGroupV.set(Some(PendingNewGroup(elementIds)))
    else
      createGroupWithLabel(elementIds, "")

  /** Creates a new group with the specified elements and label. */
  def createGroupWithLabel(elementIds: ElementIds, label: String): Unit =
    phases.fullGraphV.update(_.moveToNewGroup(elementIds, label))
    // Select the newly created group
    val updatedGraph = fullGraphNow()
    val memberIds = elementIds.memberIds
    memberIds.headOption.flatMap(updatedGraph.membership).foreach { groupId =>
      selection.set(ElementIds.from(groupId))
    }

  // -------- storage ------------
  initializePersistence()

  def nodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    ids.flatMap(fullGraphNow().getNode)

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

  // lazy vals (not defs): one signal chain per state instead of a fresh chain per call
  lazy val graphAttributes: Signal[Attributes] =
    fullGraph.map(_.elements.graphAttributes)

  lazy val graphLayout: Signal[Layout] =
    graphAttributes.map(_.getAs(Layout))

  lazy val graphRankDir: Signal[Rankdir] =
    graphAttributes.map(_.getAs(Rankdir))

  def updateLabel(elementId: ElementId, label: String): Unit =
    elementAttributesUpdates(ElementIds.from(elementId)).set:
      AttributeUpdates.of(Label -> TextUtils.escape(label))

  def diagramAttributesUpdates: Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.diagramAttributesUpdates)

  def elementAttributesUpdates(elementIds: ElementIds): Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(elementIds))

  // Theme management
  lazy val currentTheme: Var[Option[String]] = Var(None)

  currentTheme.signal.foreach: themeName =>
    themeName.foreach(setTheme)

end ViewerState
