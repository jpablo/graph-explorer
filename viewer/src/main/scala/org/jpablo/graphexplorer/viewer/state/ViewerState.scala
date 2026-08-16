package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat, DiagramLanguageInfo, DiagramRenderInputs}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, SvgWithPositions}
import org.jpablo.graphexplorer.viewer.components.resolveTheme
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.graph.{AttributesOps, ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.logging.{Level, withLog}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ClientSize.Normal
import org.jpablo.graphexplorer.viewer.state.mouseActions.{AddNewArrowOps, ExtendSelectionOps, MouseActionVar, MoveArrowEndpointOps}
import org.jpablo.graphexplorer.zoomLens
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** No clipboard was wired in — the default `readText` of a bare [[ViewerState]]. */
object ClipboardUnavailable extends Exception("Clipboard is not available")

case class ViewerState(
    projectId:                ProjectId,
    graphviz:                 Graphviz,
    writeText:                String => Any = _ => (),
    /** The system clipboard, read side. Injected like `writeText` so the paste
      * command is testable without a real `navigator.clipboard` — and so the
      * default is an honest failure rather than a silent empty string.
      */
    readText:                 () => Future[String] = () => Future.failed(ClipboardUnavailable),
    setTheme:                 String => Unit = _ => (),
    errorBus:                 EventBus[String] = EventBus(),
    infoBus:                  EventBus[String] = EventBus(),
    initialSource:            Option[String] = None,
    initialRightPanelSection: RightPanelSection = RightPanelSection.none,
    initialLeftPanelVisible:  Boolean = false,
    clientSize:               ClientSize = Normal,
    logLevel:                 Level = Level.None,
    /** The built-in example this state is showing, if it is showing one.
      *
      * ONE field rather than an `ephemeral` flag beside a name, because the two
      * could never legitimately disagree: being an example is what makes the
      * state ephemeral (nothing reaches localStorage or the library directory,
      * see [[Persistence]]) AND what supplies the title — an example has no
      * project name of its own, and a DOT graph id like `logo` is not a
      * declared title, so `displayTitle` had nothing to fall back to.
      */
    exampleName:              Option[String] = None
)(
    // All of this state's subscriptions hang off this owner. Pass a killable owner
    // (e.g. ManualOwner killed on unmount) so navigating away releases the whole
    // instance instead of pinning it for the session (previous leak: one full
    // ViewerState retained per project visit).
    using val owner: Owner = unsafeWindowOwner
) extends SvgTransformOps,
      DiagramSelectionOps,
      RecordCellOps,
      KeyboardNavOps,
      LayoutStabilityOps,
      VisibilityOps,
      ExportOps,
      ImportOps,
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
  // Public so views that present OTHER projects (the library sidebar) can ask the same
  // registry for their display titles instead of growing a second copy of the rule.
  val languages = DefaultDiagramLanguages(graphviz)

  val phases = InternalPhases(
    languages = languages,
    initialSource = if source.isEmpty then None else Some(source),
    hiddenNodes = project.hiddenElements.signal,
    collapsedGroups = project.collapsedGroups.signal,
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

  // Paced. `visibleText` already is, by way of the parse behind it, but a
  // Mermaid diagram with nothing hidden renders straight from the SOURCE
  // (MermaidBackend picks that branch when the view does not differ), which
  // would leave that one path re-rendering on every keystroke. This is the
  // RENDER's view of the text, never the document — persistence keeps
  // reading `sourceText` itself, unpaced. Built ONCE: the pacer is stateful.
  private val pacedSourceText = EditorPacing.paceSignal(sourceText.signal)

  private val viewDiffersFromSource =
    project.hiddenElements.signal
      .combineWithFn(project.collapsedGroups.signal)((hidden, collapsed) => hidden.nonEmpty || collapsed.nonEmpty)
      .distinct

  /** The render inputs for ONE backend, with `visibleText` serialized in THAT
    * backend's language rather than in whichever one is current.
    *
    * The shared `phases.visibleText` is derived from `currentFormat`, and so is
    * the backend selection below — two paths out of one source, and
    * `flatMapSwitch` makes no promise to swap the backend before the new text
    * reaches the outgoing one. It did not: replacing a DOT document with a
    * Mermaid one handed the still-subscribed Graphviz renderer a Mermaid
    * serialization of the very same graph, which it duly failed to parse.
    * Deriving per backend makes that mismatch unrepresentable rather than
    * merely unlikely — whatever a backend receives here, it is its own.
    */
  private def renderInputsFor(format: DiagramFormat) =
    DiagramRenderInputs(
      visibleText = visibleGraph.map: graph =>
        withLog("4. [visibleGraph -> visibleText]", level = phases.logLevel) {
          languages.forFormat(format).graphToText(graph, omitInternal = false)
        },
      sourceText = pacedSourceText,
      viewDiffersFromSource = viewDiffersFromSource
    )

  private[state] val svgWithPositions: Signal[Option[SvgWithPositions]] =
    // .distinct matters: the Mermaid backend HOLDS its previous value while an
    // async render is pending (see MermaidBackend.render), and re-emitting the
    // same instance would re-amend the mounted svg — duplicate binders and
    // event listeners piling up on every render cycle.
    phases.currentFormat
      .flatMapSwitch(format => languages.forFormat(format).render(renderInputsFor(format)))
      .distinct

  // Extract just the SVG for compatibility
  // 6. SVG with extra elements: selection rect, etc.
  // svgWithPositions ~> finalSVG
  lazy val finalSVG: Signal[Option[ReactiveSvgElement[SVG]]] =
    svgWithPositions.combineWith(selectionStrategy).map: (svgOpt, strategy) =>
      svgOpt.map: svgWithPos =>
        withLog("5. [visibleText -> SVG]", level = phases.logLevel) {
          beforeLayoutSwap(strategy) // the OLD svg is still mounted here
          SvgCanvas(
            rawSvg = svgWithPos.svg,
            transform = transform,
            viewerOps = this,
            mouseAction = mouseAction,
            edgePositions = svgWithPos.edgePositions,
            strategy = strategy,
            // consistent with THIS svg: any change to graph/hidden/collapsed re-renders.
            // Computed on the COLLAPSE-APPLIED view, so a box whose members have
            // concealed outside neighbors wears the badge itself.
            concealedCounts = concealedCountsNow(),
            onToggleConcealed =
              (n, succSide) => if succSide then toggleSuccessors(Set(n)) else togglePredecessors(Set(n)),
            collapsedCounts = fullGraphNow().collapsedMemberCounts(project.collapsedGroups.now()),
            onToggleCollapsed = { n =>
              selection.set2(n)
              selection.toggleCollapse()
            },
            onCollapseGroup = { g =>
              selection.set2(g)
              selection.toggleCollapse()
            },
            onRendered = afterLayoutSwap(_, strategy)
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

  /** `fromCell`/`toCell` name record CELLS on either end: their ports (minted
    * into the label when the cell has none) become the arrow's ports — mint
    * and arrow land in ONE update, so undo reverts them together.
    */
  def addArrow(
      from:     NodeId,
      to:       NodeId,
      fromCell: Option[List[Int]] = None,
      toCell:   Option[List[Int]] = None
  )(using name: sourcecode.FullName) =
    phases.fullGraphV.update: g =>
      val (g1, fromPort) = recordCells.resolvePortIn(g, from, fromCell)
      val (g2, toPort)   = recordCells.resolvePortIn(g1, to, toCell)
      val (g3, _)        = g2.addArrow(from, to, fromPort, toPort)
      selection.set(ElementIds.from(from))
      g3

  def moveArrowEndpoint(arrowId: ArrowId, newEndpoint: ArrowEndpointId, cell: Option[List[Int]] = None) =
    phases.fullGraphV.update: g =>
      val endpointNode = newEndpoint match
        case ArrowEndpointId.SourceId(id) => id
        case ArrowEndpointId.TargetId(id) => id
      val (g1, port)       = recordCells.resolvePortIn(g, endpointNode, cell)
      val (g2, newArrowId) = g1.moveArrowEndpoint(arrowId, newEndpoint, port)
      selection.set(ElementIds.from(newArrowId))
      g2

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

  /** A collapsed group renders as a proxy NODE carrying the group's id string,
    * so a click on the box selects `NodeId(g)` — but the element that actually
    * exists in the full graph is `GroupId(g)`. Anything that READS OR WRITES the
    * graph through a selection must translate first: `updateAttributes` mints a
    * node for an unknown NodeId (`nodes.getOrElse(id, nodeWithDefaults(id))`),
    * so editing a collapsed box would otherwise create a phantom node instead of
    * restyling the group. Selection itself deliberately keeps the proxy id — the
    * SVG element is a node, and highlighting resolves it as one.
    */
  def resolveCollapsed(ids: ElementIds): ElementIds =
    visibleGraphNow().resolveProxies(ids)

  /** The id the CANVAS uses for `id` — the exact inverse of [[resolveCollapsed]].
    *
    * A collapsed group is drawn as a proxy NODE carrying the group's id string,
    * and selection deliberately holds THAT id because the rendered element is a
    * node. So anything that names a group from the model side — "select all
    * groups", a group row in the Elements panel — must translate, or a folded
    * group is silently skipped by the very command that claims to include it.
    *
    * Only the OUTERMOST folded groups draw a box; one nested inside another
    * folded group renders nothing at all, so it keeps its GroupId and simply has
    * nothing to highlight either way.
    */
  def renderedId(id: ElementId): ElementId =
    visibleGraphNow().renderedId(id)

  /** Every group folds to its box (nesting resolves to the outermost ones at
    * render time); the inverse restores the full structure.
    */
  def collapseAllGroups(): Unit =
    project.collapsedGroups.set(fullGraphNow().groupIds - ViewerGraphElements.defaultRootId)

  def expandAllGroups(): Unit =
    project.collapsedGroups.set(Set.empty)

  /** Fold or unfold ONE group. Deliberately independent of the selection, unlike
    * `selection.toggleCollapse()`: the Elements panel acts on the row you click,
    * and making a row's control depend on what happens to be selected elsewhere
    * would be its own bug.
    */
  def toggleGroupCollapsed(groupId: GroupId): Unit =
    project.collapsedGroups.update(gs => if gs.contains(groupId) then gs - groupId else gs + groupId)

  /** Whether `groupId` is folded IN ITS OWN RIGHT. A group nested inside a
    * folded ancestor is not reported here — it draws no box of its own (see
    * `CollapseOps.effectiveCollapsed`), so unfolding it alone would change
    * nothing on the canvas until the ancestor unfolds, and a marker promising
    * otherwise would lie.
    */
  def isGroupCollapsed(groupId: GroupId): Signal[Boolean] =
    project.collapsedGroups.signal.map(_.contains(groupId))

  def elementAttributesUpdates(elementIds: ElementIds): Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(resolveCollapsed(elementIds)))

  // Theme management
  lazy val currentTheme: Var[Option[String]] = Var(None)

  currentTheme.signal.foreach: themeName =>
    setTheme(resolveTheme(themeName))

end ViewerState
