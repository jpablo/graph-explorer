package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat, DiagramLanguageInfo, DiagramRenderInputs, RenderState, Renderer}
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
import org.jpablo.graphexplorer.gxcore.command.SourceCommand
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.zoomLens
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** No clipboard was wired in — the default `readText` of a bare [[ViewerState]]. */
object ClipboardUnavailable extends Exception("Clipboard is not available")

case class ViewerState(
    /** What this viewer shows, and thus how it saves (§2). This is one field,
      * not a `DiagramId` and an `exampleName` flag, because the two could
      * disagree. A file also fits neither: it had to use a `DiagramId` that
      * named no diagram.
      */
    target:                   ViewTarget,
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
    /** The text for a store that has no text yet. A stored diagram keeps its
      * own text, and a file takes the text of its session.
      */
    initialSource:            Option[String] = None,
    initialRightPanelSection: RightPanelSection = RightPanelSection.none,
    /** The visibility of the Library panel. The diagram workspace gives its
      * own Var, so a switch of diagrams keeps the panel as it is. A view with
      * no workspace gets a Var of its own, and the restore sets it from the
      * stored viewer settings.
      */
    leftPanelVisible:         Var[Boolean] = Var(false),
    clientSize:               ClientSize = Normal,
    logLevel:                 Level = Level.None,
    /** True for a view inside a different page, for example an item of a
      * board. Such a view reads the viewer settings of the window one time,
      * and it does not write them. Thus a change of its panels, for example
      * the Source panel that a parse error opens, stays in this state.
      */
    embedded:                 Boolean = false
)(
    // All the subscriptions of this state use this owner. Give an owner that you
    // can kill (for example, a ManualOwner that unmount kills). Then a navigation
    // releases the whole instance, and the session does not keep it. The earlier
    // leak kept one full ViewerState for each diagram visit.
    using val owner: Owner = unsafeWindowOwner
) extends SvgTransformOps,
      SourceCommandRunner,
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

  /** The id of this view, for the display and for a share link.
    *
    * It is NOT a key for storage. `DiagramPersistence.forTarget` decides where
    * the app stores a diagram, and it reads the target, not this value. The
    * spellings are the same as before the target existed, so a share URL
    * keeps its identity.
    */
  lazy val diagramId: DiagramId =
    target match
      case ViewTarget.Diagram(id)      => id
      case ViewTarget.Example(slug, _) => DiagramId(s"example-$slug")
      case ViewTarget.File(session)    => DiagramId(session.value)

  /** The built-in example this state shows, if it shows one.
    *
    * The value comes from the target, so the kind of the view and the name of
    * the example cannot disagree. An example has no stored name, and a DOT
    * graph id like `logo` is not a declared title. Thus `displayTitle` uses
    * this name when it has no other name.
    */
  lazy val exampleName: Option[String] =
    target match
      case ViewTarget.Example(_, name) => Some(name)
      case _                           => None

  /** The metadata of this diagram: ONE `Var`, and typed views over it.
    *
    * It starts empty, and `restorePersistedState` sets it from the store. A
    * file has its metadata in its library entry, if it has one. An example has
    * no metadata in the library, and its metadata stays here, in memory.
    */
  lazy val metadata: MetadataOps =
    MetadataOps(Var(DiagramMetadata.empty))

  /** The stored name of a browser diagram (the web app), or the name of an
    * example. `restorePersistedState` sets it from the store.
    *
    * It is not metadata. A file has no stored name, because its name is the
    * title in its source, or else its file name (CONTEXT.md). Thus the value
    * stays empty for a file. A browser diagram has no file, so the web app
    * keeps a name for it (`DiagramSnapshot.name`).
    */
  lazy val storedName: Var[String] = Var("")

  val undoEvent: EventBus[Unit]        = EventBus()
  val redoEvent: EventBus[Unit]        = EventBus()
  val editorNotice: Var[Option[EditorNotice]] = Var(None)

  // Open the sources panel only for genuine errors. Info notices (render-only diagram
  // kinds) are expected — yanking the panel open for them would punish normal usage.
  editorNotice.signal.changes.filter(_.exists(_.isError))
    .foreach(_ => rightPanelActiveSection.set(RightPanelSection.sources))

  // The store gives the start text. `initialSource` only seeds a store that has
  // no text yet: a new browser diagram, or an example. It must not replace a
  // stored diagram. A share URL keeps its `?dot=` text in the browser history.
  // Before, a Back to that URL opened the stored diagram with the old text of
  // the link, and the next change wrote that text over the stored diagram.
  val source = persistence.initial.source

  // A file keeps its text, also an empty text: a 0-byte file opens empty and
  // clean. Before, the empty text became the DOT template. The view was then
  // dirty at once, and a Save wrote the template into the file. Each other
  // target starts an empty source with the template.
  private val phasesSource = if source.isEmpty && !isFileBacked then None else Some(source)

  // Registry of the extractors, the printers, and the renderers. InternalPhases depends only on this abstraction, not on a concrete part.
  // It is public, so a view of OTHER diagrams (the library sidebar) can ask this registry
  // for their display titles. Without it, that view needs a second copy of the rule.
  val languages = DefaultDiagramLanguages(graphviz)

  val phases = InternalPhases(
    languages = languages,
    initialSource = phasesSource,
    hiddenNodes = metadata.hiddenElements.signal,
    collapsedGroups = metadata.collapsedGroups.signal,
    autoDetectFormat = metadata.autoDetectFormat,
    editorNotice = editorNotice,
    logLevel = logLevel,
    keepTextWhole = () => isFileBacked,
    // The root `Var`, because its `now()` is always fresh.
    sourceLocked = () => metadata.now().locked,
    refusedEdits = errorBus.writer
  )

  val sourceText      = phases.sourceText
  val fullGraph       = phases.fullGraph
  val visibleText     = phases.visibleText
  val visibleGraph    = phases.visibleGraph
  val currentFormat   = phases.currentFormat
  val formatSelection = phases.formatSelection
  val loadStatus      = phases.loadStatus

  /** True when the source is a render-only kind of diagram, which has no graph.
    *
    * The load status gives the answer. Each parse sets the status to Loading
    * first. Thus while the status is Loading, the signal keeps its last answer,
    * and the renderer does not change for a moment at each parse. Before the
    * first parse ends, the extractor of the language reads the kind from the
    * text, so that the first drawing also comes from the correct renderer.
    */
  private val sourceHasNoGraph: Signal[Boolean] =
    def answer(status: DiagramLoadStatus, whileLoading: => Boolean): Boolean = status match
      case DiagramLoadStatus.RenderOnly(_) => true
      case DiagramLoadStatus.Loading       => whileLoading
      case _                               => false
    loadStatus.signal
      .combineWith(currentFormat, sourceText.signal)
      .scanLeft((status, format, text) => answer(status, languages.extractor(format).declaresRenderOnlyKind(text))):
        (last, next) => answer(next._1, last)
      .distinct

  /** The renderer that draws the diagram now (ADR 0002).
    *
    * It is the renderer that the person selected for this diagram, or else the
    * renderer of the language of the source. An id that this build does not
    * have counts as no selection.
    *
    * A selected renderer draws the graph of the diagram. A render-only kind (a
    * Mermaid sequence diagram) has no graph, so only the renderer of its
    * language can draw it, from the source. Thus the selection does not apply
    * to that kind.
    */
  val currentRenderer: Signal[Renderer] =
    currentFormat
      .combineWithFn(metadata.renderer.signal, sourceHasNoGraph): (format, selected, noGraph) =>
        val ofLanguage = languages.renderer(format)
        if noGraph then ofLanguage
        else selected.flatMap(languages.rendererById).getOrElse(ofLanguage)
      .distinct

  /** The drawing comes from the renderer, so the strategy that reads its SVG does also. */
  val selectionStrategy: Signal[SelectableElementStrategy] = currentRenderer.map(_.selectionStrategy)

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
  /** Assert a language by hand — which also stops the document from choosing.
    *
    * The two cannot both be in charge, and the explicit instruction is the
    * newer one. Without this, auto-detect (on by default) would sit behind
    * every manual choice waiting to overrule it on the next keystroke, and the
    * "wrong language selected" notice could never appear at all: the state it
    * describes would be corrected before anyone could read it.
    */
  def setDiagramFormat(format: DiagramFormat): Unit =
    // The selection is the extractor of the language, in the metadata (ADR
    // 0002). Thus the next open of the diagram uses it. The flag and the
    // extractor are two fields of one `Var`, so one value sets the two.
    val selected = metadata.now().copy(autoDetectFormat = Some(false), extractor = Some(languages.extractor(format).id))
    Var.set(metadata.metadata -> selected, formatSelection -> format)

  /** Let the document select its language again, and remove the selected extractor. */
  def detectDiagramFormat(): Unit =
    metadata.metadata.update(_.copy(autoDetectFormat = Some(true), extractor = None))

  val autoDetectFormat: Var[Boolean] = metadata.autoDetectFormat

  /** The language that the metadata selects: the language of the selected
    * extractor. It is `None` while auto-detect is on, and for an extractor
    * that this build does not have.
    */
  private[state] def selectedFormat(m: DiagramMetadata): Option[DiagramFormat] =
    if m.autoDetectFormat.getOrElse(true) then None
    else m.extractor.flatMap(languages.extractorById).map(_.format)

  // A different writer can select the extractor, for example a second window
  // on the same file. Then the language of this view follows the selection.
  metadata.extractor.signal.changes.foreach: _ =>
    selectedFormat(metadata.now()).filter(_ != formatSelection.now()).foreach(formatSelection.set)

  /** True when the person locked the diagram (CONTEXT.md, "Locked"). The app
    * then does not change the source: not from the editor, not from the
    * canvas, and not with a save. A change of the metadata stays possible.
    */
  val sourceLocked: Signal[Boolean] = metadata.locked.signal

  /** The format selector's three options as ONE string, since a `<select>` has
    * one value: the sentinel, or a format's name.
    */
  object formatOption:
    val auto = "Auto"

    val selected: Signal[String] =
      autoDetectFormat.signal.combineWithFn(formatSelection.signal): (isAuto, format) =>
        if isAuto then auto else format.toString

    /** What Auto currently resolves to, for the option's own label — "Auto" alone
      * says the app decided, not what it decided.
      */
    val autoLabel: Signal[String] =
      currentFormat.map(format => s"$auto — ${formatInfo(format).selectorLabel}")

    /** Picking a language by hand turns Auto off — [[setDiagramFormat]] owns
      * that rule, so every path that asserts a language obeys it.
      */
    def select(value: String): Unit =
      if value == auto then detectDiagramFormat()
      else setDiagramFormat(DiagramFormat.valueOf(value))

  /** The options of the renderer selector as ONE string, as [[formatOption]]
    * does: the sentinel, or the id of a renderer.
    */
  object rendererOption:
    val auto = "Auto"

    val available: List[Renderer] = languages.renderers

    /** An id that this build does not have shows as Auto, and it stays in the
      * metadata until the person selects a renderer.
      */
    val selected: Signal[String] =
      metadata.renderer.signal.map(_.filter(id => languages.rendererById(id).isDefined).getOrElse(auto))

    /** The renderer that Auto gives now, for the label of the option. */
    val autoLabel: Signal[String] =
      currentFormat.map(format => s"$auto — ${languages.renderer(format).label}")

    def select(value: String): Unit =
      metadata.renderer.set(Option.when(value != auto)(value))

  /** The title that the app shows for this diagram.
    *
    * A file (desktop) has no stored name. Its name is the title that its source
    * declares (the Mermaid frontmatter or `title` line, or the DOT graph label),
    * or else its file name with no extension (CONTEXT.md). The file name comes
    * from the session, so a rename shows at once.
    *
    * A diagram of the browser library has a stored name. The title is that
    * name. While the diagram has no name, it is the title that the diagram
    * declares. This change is only for the display. The stored name changes
    * only when the person renames the diagram. Thus a rename always wins, and
    * a change to the source title shows immediately.
    */
  val displayTitle: Signal[String] =
    target match
      case ViewTarget.File(_) =>
        documentSession
          .map(_.fold("")(session => LibraryEntry.nameOf(session.path)))
          .combineWithFn(sourceText.signal, formatSelection.signal): (fileName, source, format) =>
            languages.extractor(format).extractTitle(source).getOrElse(fileName)
      case _ =>
        storedName.signal.combineWithFn(sourceText.signal, formatSelection.signal): (name, source, format) =>
          if name.trim.nonEmpty && name != DiagramSnapshot.defaultDiagramName then name
          else languages.extractor(format).extractTitle(source).getOrElse(name)

  /** Presentation metadata for every available format, in display order (drives the selector UI). */
  lazy val availableFormats: List[(DiagramFormat, DiagramLanguageInfo)] =
    languages.extractors.map(extractor => extractor.format -> extractor.info)

  /** Presentation metadata for a single format. */
  def formatInfo(format: DiagramFormat): DiagramLanguageInfo =
    languages.extractor(format).info

  private def elementExists(graph: ViewerGraph, id: ElementId): Boolean =
    id match
      case nodeId: NodeId   => graph.nodes.contains(nodeId)
      case groupId: GroupId => graph.groups.contains(groupId)
      case arrowId: ArrowId => graph.arrows.contains(arrowId)

  // Prune selected ids that no longer exist in the graph. Uses keepOnly (a Var.update)
  // so the filter runs when ITS transaction executes: a selection.set made inside the
  // same graph update (e.g. combineIntoRecord selecting the new record) has already
  // landed by then and is preserved. A snapshot-based remove() here clobbered it.
  //
  // The selection holds the ids of the CANVAS. The box of a collapsed group and an
  // arrow to that box are in the visible graph, not in the full graph. Thus an id
  // stays when the full graph or the visible graph has it. When the filter runs,
  // the visible graph already shows the new full graph.
  fullGraph.changes.foreach { graph =>
    selection.keepOnly(id => elementExists(graph, id) || elementExists(visibleGraphNow(), id))
  }

  val mouseAction = MouseActionVar()

  // 5. Render visible content to SVG with position data.
  // Each renderer owns its render policy (DOT: synchronous from visibleText; Mermaid: async from
  // sourceText, validated), so the format dispatch lives in the registry, not here.

  // Paced. `visibleText` already is, by way of the parse behind it, but a
  // Mermaid diagram with nothing hidden renders straight from the SOURCE
  // (MermaidBackend picks that branch when the view does not differ), which
  // would leave that one path re-rendering on every keystroke. This is the
  // RENDER's view of the text, never the document — persistence keeps
  // reading `sourceText` itself, unpaced. Built ONCE: the pacer is stateful.
  private val pacedSourceText = EditorPacing.paceSignal(sourceText.signal)

  private val viewDiffersFromSource =
    metadata.hiddenElements.signal
      .combineWithFn(metadata.collapsedGroups.signal)((hidden, collapsed) => hidden.nonEmpty || collapsed.nonEmpty)
      .distinct

  /** The render inputs for ONE renderer, with `visibleText` serialized in THAT
    * renderer's language rather than in whichever one is current.
    *
    * The shared `phases.visibleText` is derived from `currentFormat`, and so is
    * the renderer selection below — two paths out of one source, and
    * `flatMapSwitch` makes no promise to swap the renderer before the new text
    * reaches the outgoing one. It did not: replacing a DOT document with a
    * Mermaid one handed the still-subscribed Graphviz renderer a Mermaid
    * serialization of the very same graph, which it duly failed to parse.
    * Deriving per renderer makes that mismatch unrepresentable rather than
    * merely unlikely — whatever a renderer receives here, it is its own.
    */
  private def renderInputsFor(sourceInItsLanguage: Boolean) =
    DiagramRenderInputs(
      visibleGraph = visibleGraph,
      sourceText = pacedSourceText,
      // A renderer cannot draw a source in a different language. Then it must
      // always draw the text of the visible graph.
      viewDiffersFromSource = if sourceInItsLanguage then viewDiffersFromSource else Val(true)
    )

  /** The state of the render of the current renderer: a render that is not
    * complete, a drawing, or no drawing with its cause. A board reads it for an
    * item, so that it can tell a render that waits from a render that failed.
    */
  val renderState: Signal[RenderState] =
    currentRenderer
      .combineWithFn(currentFormat)((renderer, sourceFormat) => (renderer, renderer.format == sourceFormat))
      .distinct
      .flatMapSwitch((renderer, sourceInItsLanguage) => renderer.render(renderInputsFor(sourceInItsLanguage)))
      .distinct

  private[state] val svgWithPositions: Signal[Option[SvgWithPositions]] =
    // .distinct matters: the Mermaid renderer HOLDS its previous value while an
    // async render is pending (see MermaidRenderer.render), and re-emitting the
    // same instance would re-amend the mounted svg — duplicate binders and
    // event listeners piling up on every render cycle. A Pending state holds
    // the previous drawing, and this .distinct drops that repeated drawing.
    renderState.map(_.drawing).distinct

  /** The drawing alone, with no behavior of the canvas. Only one place can
    * show the element, and that place is not the canvas of this viewer
    * ([[finalSVG]]).
    */
  val drawing: Signal[Option[ReactiveSvgElement[SVG]]] = svgWithPositions.map(_.map(_.svg))

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
            transform = canvasTransform,
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
            collapsedCounts = fullGraphNow().collapsedMemberCounts(metadata.collapsedGroups.now()),
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

  /** Creates a new group from the current selection, prompting for its label. */
  def createGroupMaybePrompt(elementIds: ElementIds): Unit =
    pendingNewGroupV.set(Some(PendingNewGroup(elementIds)))

  /** Creates a new group with the specified elements and label.
    *
    * `elementIds` has the ids of the canvas, so [[editTargets]] turns a box into
    * its group. The edit goes through the command vocabulary, as `ungroup` does.
    * Thus the menu and a socket client run one operation.
    */
  def createGroupWithLabel(elementIds: ElementIds, label: String): Unit =
    runSourceCommand(
      SourceCommand.Group(editTargets(elementIds).ids.toSet, label),
      // Select the new group. A refused edit does not call this.
      onApplied = (before, after) =>
        (after.groupIds -- before.groupIds).headOption.foreach(groupId => selection.set(ElementIds.from(groupId)))
    )

  // -------- storage ------------
  initializePersistence()

  def allNodeIds(): Set[NodeId] =
    fullGraphNow().nodeIds

  def allArrowIds(): Set[ArrowId] =
    fullGraphNow().arrowIds

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an edge. If the
    * selected element is a group/cluster, the new node will be added to that group. The new node will become the only selected element
    * after creation. A selected box is its group. The new node then goes into the collapsed group, and the box stays selected.
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
    val applies = phases.graphEditApplies
    phases.fullGraphV.update: fullGraph =>
      // A box gives its group. Then the new node goes into that group, as for the
      // expanded group, and no arrow ends on the box.
      val selectedElementId        = selection.targets().ids.headOption
      val (newGraph, newNodeId, _) = fullGraph.addNodeWithSmartConnection(selectedElementId, attributes, direction)
      // A node in a collapsed group has no shape on the canvas. Then its box stays selected.
      if applies && !selectedElementId.exists(hasBox) then selection.set2(newNodeId)
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
  ) =
    arrowEndRefusal(from, "add").orElse(arrowEndRefusal(to, "add")) match
      case Some(message) => errorBus.emit(message)
      case None =>
        val applies = phases.graphEditApplies
        phases.fullGraphV.update: g =>
          val (g1, fromPort) = recordCells.resolvePortIn(g, from, fromCell)
          val (g2, toPort)   = recordCells.resolvePortIn(g1, to, toCell)
          val (g3, _)        = g2.addArrow(from, to, fromPort, toPort)
          if applies then selection.set(ElementIds.from(from))
          g3

  def moveArrowEndpoint(arrowId: ArrowId, newEndpoint: ArrowEndpointId, cell: Option[List[Int]] = None) =
    val endpointNode = newEndpoint match
      case ArrowEndpointId.SourceId(id) => id
      case ArrowEndpointId.TargetId(id) => id
    val refusal =
      // An arrow to a box is in the visible graph only. It stands for one or
      // more arrows of the full graph, so a move of its end has no one meaning.
      if !fullGraphNow().arrows.contains(arrowId) then
        Some(
          if visibleGraphNow().arrows.contains(arrowId) then
            "The viewer did not move the arrow, because it ends on a collapsed group. Expand the group, and then move the arrow."
          else "The viewer did not move the arrow, because the diagram does not have it."
        )
      else arrowEndRefusal(endpointNode, "move")
    refusal match
      case Some(message) => errorBus.emit(message)
      case None =>
        val applies = phases.graphEditApplies
        phases.fullGraphV.update: g =>
          val (g1, port)       = recordCells.resolvePortIn(g, endpointNode, cell)
          val (g2, newArrowId) = g1.moveArrowEndpoint(arrowId, newEndpoint, port)
          if applies then selection.set(ElementIds.from(newArrowId))
          g2

  /** Why the node `id` cannot be one end of a new arrow or of a moved arrow
    * end, or `None` when it can.
    *
    * The id comes from the canvas. A box is a collapsed group, and a DOT edge
    * cannot end on a cluster. The viewer does not select a member of the group
    * in place of the box, so it refuses the arrow and tells the person why.
    */
  private def arrowEndRefusal(id: NodeId, verb: String): Option[String] =
    editTargets(ElementIds.from(id)).ids.headOption match
      case Some(_: NodeId) => None
      case Some(group: GroupId) =>
        val name = fullGraphNow().groups.get(group).map(_.label.toString).filter(_.nonEmpty).getOrElse(group.value)
        Some(
          s"The viewer did not $verb the arrow, because an arrow cannot end on the collapsed group \"$name\". " +
            "Expand the group, and then connect a node in it."
        )
      case _ => Some(s"The viewer did not $verb the arrow, because the diagram does not have the node \"${id.value}\".")

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
    * exists in the full graph is `GroupId(g)`. Anything that READS the graph
    * through a selection must translate first. A graph EDIT uses
    * [[editTargets]], which calls this and also removes unknown ids:
    * `updateAttributes` mints a node for an unknown NodeId
    * (`nodes.getOrElse(id, nodeWithDefaults(id))`). Selection itself
    * deliberately keeps the proxy id — the SVG element is a node, and
    * highlighting resolves it as one.
    */
  def resolveCollapsed(ids: ElementIds): ElementIds =
    visibleGraphNow().resolveProxies(ids)

  /** The ids of a graph edit, from the ids of the canvas: the ONE step between
    * the two.
    *
    * Each graph edit that takes ids from the selection, from a canvas gesture or
    * from a live-view command reads them through this step. The step does two
    * things:
    *   - It turns a box into the group that the box shows ([[resolveCollapsed]]).
    *     Thus an edit of a box is the edit of the expanded group.
    *   - It removes each id that the full graph does not have, for example a
    *     stale ref from a socket client, or an arrow to a box. `updateAttributes`
    *     adds a node for an unknown NodeId, and an arrow or a membership with an
    *     unknown id puts a phantom node into the text. After this step, an edit
    *     cannot do that.
    */
  def editTargets(ids: ElementIds): ElementIds =
    val graph = fullGraphNow()
    resolveCollapsed(ids).filter(id => elementExists(graph, id))

  /** True when `id` is a group that the canvas shows as a box. */
  private def hasBox(id: ElementId): Boolean =
    id match
      case group: GroupId => visibleGraphNow().proxyForGroup.contains(group)
      case _              => false

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
    metadata.collapsedGroups.set(fullGraphNow().groupIds - ViewerGraphElements.defaultRootId)

  def expandAllGroups(): Unit =
    metadata.collapsedGroups.set(Set.empty)

  /** Fold or unfold ONE group. Deliberately independent of the selection, unlike
    * `selection.toggleCollapse()`: the Elements panel acts on the row you click,
    * and making a row's control depend on what happens to be selected elsewhere
    * would be its own bug.
    */
  def toggleGroupCollapsed(groupId: GroupId): Unit =
    metadata.collapsedGroups.update(gs => if gs.contains(groupId) then gs - groupId else gs + groupId)

  /** Whether `groupId` is folded IN ITS OWN RIGHT. A group nested inside a
    * folded ancestor is not reported here — it draws no box of its own (see
    * `CollapseOps.effectiveCollapsed`), so unfolding it alone would change
    * nothing on the canvas until the ancestor unfolds, and a marker promising
    * otherwise would lie.
    */
  def isGroupCollapsed(groupId: GroupId): Signal[Boolean] =
    metadata.collapsedGroups.signal.map(_.contains(groupId))

  def elementAttributesUpdates(elementIds: ElementIds): Var[AttributeUpdates] =
    phases.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(editTargets(elementIds)))

  // Theme management
  // The value starts with the stored theme. A view that the app makes inside a
  // transaction (for example, a new route) gets the restored settings only after
  // the transaction. With `None` here, the subscription below wrote the default
  // theme first, and the change to the stored theme then started the CSS color
  // transitions of the UI.
  lazy val currentTheme: Var[Option[String]] = Var(storedTheme)

  currentTheme.signal.foreach: themeName =>
    setTheme(resolveTheme(themeName))

end ViewerState
