package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.router.Route

/** Why a view is being left, and therefore what happens once the person
  * answers the unsaved-changes question (§7.4).
  *
  * One dialog serves both. The question is identical — this text is not on
  * disk, what now — and only the last step differs.
  */
enum LeaveIntent derives CanEqual:
  case Navigate(route: Route)
  case CloseWindow
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopIpc}
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.{ElementIds, GroupId}
import org.scalajs.dom
import upickle.default.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait Persistence:
  this: ViewerState =>

  /** This viewer's store, chosen by its target (§6).
    *
    * The choice is made once, in `DiagramPersistence.forTarget`, and that
    * function is total over `ViewTarget`. Before this, every viewer opened a
    * library handle keyed by a `DiagramId`, and the only way to keep an example
    * out of the library was to remember not to call for one. A loose file could
    * not be kept out at all.
    */
  protected val persistence: DiagramPersistence =
    DiagramPersistence.forTarget(target, initialSource)

  /** This viewer's session, while it shows a loose file (§7.3).
    *
    * A Signal rather than a lookup, so a dirty marker and a conflict banner
    * follow the file instead of waiting for the next render. §10 asks for
    * exactly this: the subscription is owner-scoped, so Laminar teardown ends
    * it when the view goes.
    */
  // `lazy`, because a trait's vals initialize before `ViewerState.sourceText`
  // does. Forcing them at first read is what keeps this off a null.
  lazy val documentSession: Signal[Option[DesktopDocumentRegistry.Session]] =
    target match
      case ViewTarget.LooseFile(session) => DesktopDocumentRegistry.signal(session)
      case _                             => Signal.fromValue(None)

  /** The editor holds an edit that the file does not have (§7.3, `local` vs `base`).
    *
    * It is false for a diagram, but a diagram can change. A diagram saves on
    * each keystroke, so it is never behind. Only a file can be in the
    * "unsaved" state.
    */
  lazy val documentDirty: Signal[Boolean] =
    target match
      case ViewTarget.LooseFile(_) =>
        documentSession
          .combineWith(sourceText.signal)
          .map((session, text) => session.exists(_.sourceText != text))
          .distinct // the same comparison `documentIsDirty` makes, in Signal form
      case _ => Signal.fromValue(false)

  /** What this view is holding up, while the person answers (§7.4).
    *
    * The destination is kept rather than re-derived: the dialog must do what
    * was asked, and by the time it is answered the click — or the close
    * request — is long gone.
    */
  val pendingLeave: Var[Option[LeaveIntent]] = Var(None)

  /** The text this viewer last took from its session.
    *
    * Viewer-local, and that is the point of it. The decision below used to live
    * in `DesktopBridge` against a PROCESS-GLOBAL "current viewer", so every file
    * event had to ask "is this still the right view?" and every navigation had
    * to remember to say goodbye. A viewer that knows what it last adopted can
    * answer §7.3 from its own state.
    */
  private var lastAdopted: String = ""

  /** Follow this viewer's own session (§7.3, §10).
    *
    * Owner-scoped: Laminar teardown ends it, so a view that leaves the screen
    * stops listening without anything having to detach it.
    *
    * The rule, from the viewer's own two facts — what it last adopted, and what
    * is in the editor now:
    *
    *   - the editor matches what was adopted: no edit to lose, take the file.
    *   - the editor moved AND the file moved: both versions matter, so keep the
    *     edit on screen and record the conflict. §7.3 forbids replacing dirty
    *     text silently.
    *   - the editor moved and the file did not: nothing happened.
    */
  private def followDocumentSession(): Unit =
    target match
      case ViewTarget.LooseFile(session) =>
        lastAdopted = persistence.initial.source
        documentSession.changes.foreach:
          case None => ()
          case Some(current) =>
            val editorMoved = sourceText.now() != lastAdopted
            val fileMoved   = current.sourceText != lastAdopted
            if !editorMoved then
              lastAdopted = current.sourceText
              replaceSourceDetectingFormat(current.sourceText)
            else if fileMoved then
              DesktopDocumentRegistry.markConflict(session, current.revision, current.sourceText)
      case _ => ()

  /** Follow the diagram when a different writer changes it (D7.3).
    *
    * Before, this return path did not exist. `restorePersistedState` reads
    * `persistence.initial` ONCE. For a long time, that was the only read. The
    * library set its `Var` when a diagram changed under an open view, and
    * nothing used the value. A `gx set`, or a pull from an origin file, went
    * into the diagram correctly. It did not show until the view opened again.
    *
    * That also broke the two resolution buttons of §8. "Take the file's
    * version" wrote the diagram, and the strip went away because the state
    * was resolved. But the person still saw the old text.
    *
    * This method adopts only the SOURCE. Two gates are before it, and neither
    * gate is in this method. `DiagramPersistence.external` removes the writes
    * of this viewer. `createDiagramPersistence` does not set its `Var` while
    * this view has a write that waits, and it warns. Thus an event here comes
    * from a different writer, at a time when the person did not edit.
    */
  private def followDiagram(): Unit =
    target match
      case ViewTarget.Diagram(_) =>
        persistence.external.foreach: next =>
          if sourceText.now() != next.source then
            replaceSourceDetectingFormat(next.source)
      case _ => ()

  /** Listen to the origin of this diagram, from the moment that it opens (§8).
    *
    * This is the other half of [[followDocumentSession]]. A loose file comes
    * with its text and a session. A diagram comes with a BINDING, and nothing
    * listens to the file behind it. Phase 3 decided what to do with an origin
    * change, but never asked for one. Thus the divergence strip showed only
    * after `gx open` watched the file.
    *
    * The call does not wait for a result. The reply holds the bytes, and the
    * method discards them. The watch sends a document event. That event is
    * the one route that reconciles against the diagram, and does not
    * overwrite it.
    *
    * The watch stays after this view closes, and that is intentional (see
    * `DesktopIpc.openDocument`). A watch has the path as its key, and
    * `gx watch` shares it. A release on unmount can stop a watch that this
    * page did not start. The cost is one watch for each bound diagram that
    * the person opens in a session.
    */
  private def watchOrigin(): Unit =
    target match
      case ViewTarget.Diagram(id) =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Library.originPathOf(id).foreach(DesktopIpc.openDocument)
      case _ => ()

  /** True when a file is behind this document: a loose file, or a diagram
    * with a binding to a file (§8).
    *
    * A canvas edit writes the whole graph again, and the file then takes that
    * text. Thus `InternalPhases` refuses a canvas edit that removes content
    * that the graph does not model. This is a `def`, because a diagram can get
    * or lose its binding while it is open.
    */
  def isFileBacked: Boolean =
    target match
      case ViewTarget.LooseFile(_)       => true
      case ViewTarget.Diagram(id)        => Library.originPathOf(id).isDefined
      case ViewTarget.Example(_, _)      => false

  /** The same question as [[documentDirty]], answered now rather than observed.
    *
    * A navigation guard has to decide inside the click that asks for it (§7.4),
    * and a Signal cannot be read at that moment. ONE comparison, used by both,
    * so a guard and a marker can never disagree.
    */
  def documentIsDirty: Boolean =
    target match
      case ViewTarget.LooseFile(session) =>
        DesktopDocumentRegistry.get(session).exists(_.sourceText != sourceText.now())
      case _ => false

  /** The file changed under an edit, and both versions are kept (§7.3). */
  lazy val documentConflict: Signal[Option[DesktopDocumentRegistry.Conflict]] =
    documentSession.map(_.flatMap(_.conflict)).distinct

  /** Resolve this viewer's loose-file conflict.
    *
    * Both answers accept the file's revision as the new save base. The viewer
    * must also advance `lastAdopted` before the registry emits that new base.
    * Otherwise the active session follower compares the registry update with
    * the old base and immediately creates the same conflict again.
    *
    * @param adoptRemoteText
    *   true replaces the editor with the file's text; false keeps the local
    *   edit, which stays dirty until it is saved.
    */
  def resolveDocumentConflict(adoptRemoteText: Boolean): Unit =
    target match
      case ViewTarget.LooseFile(session) =>
        DesktopDocumentRegistry.get(session).flatMap(_.conflict).foreach: remote =>
          lastAdopted = remote.text
          DesktopDocumentRegistry.acceptRemote(session)
          if adoptRemoteText then replaceSourceDetectingFormat(remote.text)
      case _ => ()

  /** ViewerSettings below is deliberately NOT branched by the target: theme,
    * panel widths and the like are app-wide preferences, and losing a theme
    * change because it was made while looking at an example would be its own bug.
    */
  private val viewerSettings: Var[ViewerSettings] =
    Library.loadViewerSettings()

  /** Restores the persisted state values to the current ViewerState. */
  private def restorePersistedState(): Unit =
    val restoredDiagramState   = persistence.initial
    val restoredViewerSettings = viewerSettings.now()

    project.hiddenElements.set(restoredDiagramState.hiddenElements)
    project.collapsedGroups.set(restoredDiagramState.collapsedGroups)
    restoredDiagramState.format
      .flatMap(format => Try(DiagramFormat.valueOf(format)).toOption)
      .foreach(formatSelection.set)
    // Do this after the format, not with it. When auto-detect goes on, it gets
    // the language from the text again, and it must do that over the RESTORED
    // format. `None` means "saved before the mode existed", and those diagrams
    // also get the default. Only an explicit `Some(false)` keeps it off. This
    // is safe for old diagrams, because auto-detect changes the format only on
    // evidence. Only a source that clearly declares a different language
    // overrules a stored format, and that diagram had the wrong format before.
    autoDetectFormat.set(restoredDiagramState.autoDetectFormat.getOrElse(true))

    Var.set(
      project.name -> restoredDiagramState.projectName,
      // app settings
      leftPanelVisible -> restoredViewerSettings.leftPanelVisible,
      rightPanelActiveSection -> Try(RightPanelSection.fromOrdinal(restoredViewerSettings.rightPanelTabIndex)).getOrElse(
        RightPanelSection.none
      ),
      currentTheme -> restoredViewerSettings.currentTheme,
      promptLabelBeforeNewNode -> restoredViewerSettings.promptLabelBeforeNewNode,
      // Re-clamp on restore: a width saved on a wide monitor would otherwise swallow the
      // canvas when the same library is opened in a narrow window.
      rightPanelWidth -> ViewerSettings.clampRightPanelWidth(
        restoredViewerSettings.rightPanelWidth.getOrElse(ViewerSettings.defaultRightPanelWidth),
        dom.window.innerWidth
      ),
      wrapSourceLines -> restoredViewerSettings.wrapSourceLines,
      elementsPinned -> restoredViewerSettings.elementsPinned,
      view3D -> restoredViewerSettings.view3D,
      enable3D -> restoredViewerSettings.enable3D,
      layout3D -> restoredViewerSettings.layout3D.getOrElse(
        org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D.id
      ),
      nav3DTrackpad -> restoredViewerSettings.nav3DTrackpad,
      label3DBillboard -> restoredViewerSettings.label3DBillboard
    )

  /** Sets up bidirectional synchronization between ViewerState and persisted storage. */
  private def setupStateSynchronization(): Unit =
    // synchronize ViewerState ~> PersistedState
    Signal
      .combine(
        project.hiddenElements.signal,
        project.collapsedGroups.signal,
        project.name.signal,
        sourceText.signal,
        formatSelection.signal,
        autoDetectFormat.signal
      )
      .changes
      .distinct
      .foreach: _ =>
        persistence.update(snapshot())

    // synchronize ViewerState ~> ViewerSettings
    Signal
      .combine(
        leftPanelVisible.signal,
        rightPanelActiveSection.signal,
        currentTheme.signal,
        promptLabelBeforeNewNode.signal,
        rightPanelWidth.signal,
        wrapSourceLines.signal,
        elementsPinned.signal,
        view3D.signal,
        layout3D.signal
      )
      // combineWith rather than a 10th combine slot: Signal.combine tops out
      // at 9, and tuplez stops flattening at 10 (T9+scalar) — so the second
      // combineWith NESTS: the value is ((ten settings), enable3D), and the
      // pattern below mirrors that shape.
      .combineWith(nav3DTrackpad.signal)
      .combineWith(enable3D.signal)
      .combineWith(label3DBillboard.signal)
      .changes
      .distinct
      .foreach:
        case (
              (leftVisible, tabIndex, theme, promptBeforeNewNode, panelWidth, wrapLines, pinned, in3D, layout3DId,
                navTrackpad),
              en3D,
              billboard3D
            ) =>
          // copy, not a fresh ViewerSettings: fields this page does not own (the library's
          // view mode) must survive a detail-page sync instead of resetting to defaults.
          viewerSettings.update(
            _.copy(
              leftPanelVisible = leftVisible,
              rightPanelTabIndex = tabIndex.ordinal,
              currentTheme = theme,
              promptLabelBeforeNewNode = promptBeforeNewNode,
              rightPanelWidth = Some(panelWidth),
              wrapSourceLines = wrapLines,
              elementsPinned = pinned,
              view3D = in3D,
              layout3D = Some(layout3DId),
              nav3DTrackpad = navTrackpad,
              enable3D = en3D,
              label3DBillboard = billboard3D,
              schemaVersion = ViewerSettings.currentSchemaVersion
            )
          )

  /** This diagram as it stands now.
    *
    * One construction, read by the change sync above and by [[save]] below. Two
    * constructions would drift, and a ⌘S that wrote a different shape from the
    * autosave is the kind of difference nobody sees until a field goes missing.
    */
  def snapshot(): PersistedDiagramState =
    PersistedDiagramState(
      hiddenElements = project.hiddenElements.now(),
      collapsedGroups = project.collapsedGroups.now(),
      projectName = project.name.now(),
      source = sourceText.now(),
      format = Some(formatSelection.now().toString),
      autoDetectFormat = Some(autoDetectFormat.now())
    )

  /** Save this diagram, because the person asked (§11).
    *
    * This is a typed operation on the viewer, so ⌘S goes to the store of THIS
    * diagram. Before, the keyboard handler used
    * `window.__graphExplorerDesktopBridge` to find a destination for the whole
    * process: one shared reference, for the viewer on the screen at that time.
    * Thus an autosave could change one diagram while ⌘S wrote a different file.
    *
    * The method has no destination to check now. `persistence` belongs to this
    * viewer, and the target of this viewer selected it. Thus a save cannot
    * name a different store.
    */
  def save()(using ExecutionContext): Future[SaveResult] =
    persistence.saveNow(snapshot())

  /** Initializes persistence by restoring state and setting up synchronization. */
  def initializePersistence(): Unit =
    restorePersistedState()
    setupStateSynchronization()
    followDocumentSession()
    followDiagram()
    watchOrigin()

  /** Release the store when the view goes away (§10).
    *
    * The view calls this from its unmount, beside the owner it kills. Every
    * store today releases nothing, and the method still exists: a store that
    * holds a handle must have somewhere to give it back, and adding the call
    * later means finding every unmount again.
    */
  def closePersistence(): Unit = persistence.close()

case class PersistedDiagramState(
    hiddenElements: HiddenElements = ElementIds(),
    // Folded groups, like hiddenElements: a view setting, saved with the page.
    collapsedGroups: Set[GroupId] = Set.empty,
    projectName:    String = "",
    source:         String = "",
    format:         Option[String] = None,
    // None for each diagram that the app saved before the mode existed.
    // `restorePersistedState` reads None as on.
    autoDetectFormat: Option[Boolean] = None
) derives ReadWriter

object PersistedDiagramState:
  val minimalGraphText = "digraph G {\n}"

  /** The name of a diagram that the person did not rename. The display uses the title
    * that the diagram declares in place of this name (ViewerState.displayTitle,
    * Library.diagramCardInfo). The stored name changes only when the person renames the
    * diagram, so a rename always wins.
    */
  val defaultProjectName = "Untitled"

  val empty = minimal()

  def minimal(source: Option[String] = None) =
    PersistedDiagramState(
      hiddenElements = ElementIds(),
      collapsedGroups = Set.empty,
      projectName = defaultProjectName,
      source = source.getOrElse(minimalGraphText),
      format = None
    )

case class ViewerSettings(
    leftPanelVisible:   Boolean = true,
    rightPanelTabIndex: Int = 0,
    currentTheme:       Option[String] = None,
    promptLabelBeforeNewNode: Boolean = true,
    // None = never dragged, resolved to defaultRightPanelWidth on restore. Option rather than
    // a plain Int so "never chosen" stays distinguishable from a width that happens to equal
    // the default — the restore path treats the two differently.
    rightPanelWidth:    Option[Int] = None,
    wrapSourceLines:    Boolean = false,
    // Palette-first Elements list: false = floating card, true = docked in the panel.
    elementsPinned:     Boolean = false,
    // Experimental 3D canvas (three.js scene instead of the engine's SVG).
    view3D:             Boolean = false,
    // Feature gate for 3D, set in Preferences: until true, the 3D toggle and
    // its controls are absent from the toolbar entirely. Separate from view3D
    // so disabling the feature does not erase which mode the user was in.
    enable3D:           Boolean = false,
    // 3D layout algorithm by Layout3D.id. Stored loosely like librarySort:
    // None = default (force), and an unknown id degrades to the default
    // instead of costing the user every other setting.
    layout3D:           Option[String] = None,
    // 3D navigation idiom: true = trackpad (scroll orbits, no click), false = mouse (wheel zooms).
    nav3DTrackpad:      Boolean = true,
    // 3D labels turn to face the camera (billboards). Off = fixed sheets.
    label3DBillboard:   Boolean = false,
    // Library page: false = thumbnail cards, true = compact rows.
    libraryListMode:    Boolean = false,
    // Library page: the sort column, and the format the list is filtered to.
    // Stored as enum NAMES rather than the enums themselves, and tolerated
    // loosely on read: a ViewerSettings that fails to parse falls back to
    // `empty` and the sync then WRITES that back, so one stale name here would
    // cost the user every other setting. None = never chosen, matching
    // rightPanelWidth above (no filter / the default sort).
    librarySort:         Option[String] = None,
    libraryFormatFilter: Option[String] = None,
    schemaVersion:      Int = ViewerSettings.currentSchemaVersion // Add default for loading potentially older states
) derives ReadWriter

// Add a default empty state for ViewerSettings
object ViewerSettings:
  val currentSchemaVersion = 2 // Define the current version

  /** Right panel width in px. 320 = the 20rem this panel was fixed at before it could be dragged. */
  val defaultRightPanelWidth = 320

  /** Narrow enough to tuck the panel away, wide enough that the toolbar's controls still fit. */
  val minRightPanelWidth = 240

  /** A drag can only ever be as wide as this share of the window: the canvas is the point of
    * the app, so the panel must not be draggable over it. Recomputed per drag rather than
    * stored, since the window can be resized between sessions.
    */
  val maxRightPanelWidthFraction = 0.6

  /** Clamp a dragged width to something usable. Kept here, next to the bounds it enforces,
    * so the drag handler cannot drift from the persisted default.
    *
    * A window reporting no width at all — a backgrounded tab, a pane that has not painted yet —
    * caps nothing. Capping against 0 collapsed the panel to the minimum on restore, and the
    * settings sync then WROTE that back: one restore in a hidden window permanently shrank a
    * width the user had chosen.
    */
  def clampRightPanelWidth(px: Double, viewportWidth: Double): Int =
    val upper =
      if viewportWidth > 0 then math.max(minRightPanelWidth.toDouble, viewportWidth * maxRightPanelWidthFraction)
      else Double.MaxValue
    math.round(math.min(math.max(px, minRightPanelWidth.toDouble), upper)).toInt

  val empty = ViewerSettings()
