package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Transaction
import com.raquo.airstream.ownership.Subscription
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.library.{FileRename, Library}
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
import org.jpablo.graphexplorer.gxcore.command.ElementRef
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
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
    * out of the library was to remember not to call for one. A file could not
    * be kept out at all.
    */
  protected val persistence: DiagramPersistence =
    DiagramPersistence.forTarget(target, initialSource)

  /** This viewer's session, while it shows a file (§7.3).
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
      case ViewTarget.File(session) => DesktopDocumentRegistry.signal(session)
      case _                        => Signal.fromValue(None)

  /** This viewer's session now, while it shows a file. */
  def documentSessionNow: Option[DesktopDocumentRegistry.Session] =
    target match
      case ViewTarget.File(session) => DesktopDocumentRegistry.get(session)
      case _                        => None

  /** The editor holds an edit that the file does not have (§7.3, `local` vs `base`).
    *
    * A text that differs from the file only in the line endings or in the
    * final newline is not an edit, because a save keeps those of the file
    * (V-04, `Session.holds`).
    *
    * It is false for a browser diagram, which saves on each keystroke, so it
    * is never behind. Only a file can be in the "unsaved" state.
    */
  lazy val documentDirty: Signal[Boolean] =
    target match
      case ViewTarget.File(_) =>
        documentSession
          .combineWith(sourceText.signal)
          .map((session, text) => session.exists(!_.holds(text)))
          .distinct // the same comparison `documentIsDirty` makes, in Signal form
      case _ => Signal.fromValue(false)

  /** What this view is holding up, while the person answers (§7.4).
    *
    * The destination is kept rather than re-derived: the dialog must do what
    * was asked, and by the time it is answered the click — or the close
    * request — is long gone.
    */
  val pendingLeave: Var[Option[LeaveIntent]] = Var(None)

  /** Give this view's editor to its session (§7.3, §10).
    *
    * The registry holds the §7.3 rule (`DesktopDocumentRegistry.receive`).
    * When a version of the file arrives, it reads the text of this editor, and
    * it decides from the base, the editor and the file. The view keeps no copy
    * of the base: the session holds it, and two copies could disagree.
    *
    * The registry calls `take` only for a clean editor. The editor then takes
    * the text of the file, with the language rule of [[adoptFileText]].
    *
    * Owner-scoped: when the owner of this view dies (the view unmounts), the
    * editor goes back. The session then has no edit, so a conflict ends, and
    * the file's version becomes the base.
    */
  private def attachToSession(): Unit =
    target match
      case ViewTarget.File(session) =>
        val detach = DesktopDocumentRegistry.attach(
          session,
          DesktopDocumentRegistry.Editor(text = () => sourceText.now(), take = adoptFileText)
        )
        new Subscription(owner, detach)
        ()
      case _ => ()

  /** Put the text of the file in the editor: a clean adoption, "Load the
    * file", or "Discard".
    *
    * The language is NOT read off the text with `DiagramFormat.detect`, which
    * falls back to DOT and ignores auto-detect off. Before, a language that
    * the person picked by hand was lost after each outside change. With
    * auto-detect off, the language stays. With auto-detect on, the language
    * is the language that the text declares, or else the language now.
    */
  private def adoptFileText(text: String): Unit =
    val format =
      if autoDetectFormat.now() then DiagramFormat.declared(text).getOrElse(formatSelection.now())
      else formatSelection.now()
    replaceSource(text, format)

  /** Follow the metadata when a different writer changes it (D7.3).
    *
    * A file on the desktop has its metadata in its library entry, and `gx` can
    * change that entry while the file is on the screen: `gx hide`, `gx tag`.
    * Before, the viewer read the metadata ONCE at mount. The change did not
    * show, and the viewer kept its old hidden set. The next edit in the viewer
    * then wrote that old set back, and the change from `gx` was lost with no
    * message (`FollowMetadataSpec`).
    *
    * The adoption writes nothing. After it, the metadata of this viewer is the
    * metadata of the entry, and the store writes only a change
    * (`DesktopLibrary.writeMetadata`). The adoption does not touch the source,
    * the selection or the viewport. The source follows the session
    * (`DesktopDocumentRegistry.receive`), and the other two are not metadata.
    */
  private def followMetadata(): Unit =
    persistence.external.foreach: next =>
      if metadata.now() != next then metadata.set(next)

  /** True when a file is behind this view (§8).
    *
    * A canvas edit writes the whole graph again, and the file then takes that
    * text on the next save. Thus `InternalPhases` refuses a canvas edit that
    * removes content that the graph does not model. Each diagram on the desktop
    * is a file. A diagram of the browser library and an example have no file.
    */
  def isFileBacked: Boolean =
    target match
      case ViewTarget.File(_)       => true
      case ViewTarget.Diagram(_)    => false
      case ViewTarget.Example(_, _) => false

  /** Rename this diagram.
    *
    * On the desktop, a diagram is a file, and a rename renames the FILE.
    * `newName` is then a file name, with no folder. A name with no extension
    * keeps the extension of the file. The library renames the file first, and
    * then moves its entry. A refusal, or a file that moved while its metadata
    * did not, goes to the person on `errorBus`.
    *
    * Each other view (a browser diagram, an example, or a file with no desktop
    * library) changes its stored name. A browser diagram saves that name.
    */
  def rename(newName: String)(using ExecutionContext): Future[Unit] =
    (target, Library.desktop) match
      case (ViewTarget.File(session), Some(library)) =>
        library.renameFile(session, newName).map:
          case FileRename.Done(_)                      => ()
          case FileRename.Refused(message)             => errorBus.emit(message)
          case FileRename.MetadataNotMoved(_, message) => errorBus.emit(message)
      case _ =>
        storedName.set(newName)
        Future.unit

  /** The same question as [[documentDirty]], answered now rather than observed.
    *
    * A navigation guard has to decide inside the click that asks for it (§7.4),
    * and a Signal cannot be read at that moment. ONE comparison, used by both,
    * so a guard and a marker can never disagree.
    */
  def documentIsDirty: Boolean =
    target match
      case ViewTarget.File(session) =>
        DesktopDocumentRegistry.get(session).exists(!_.holds(sourceText.now()))
      case _ => false

  /** The file changed under an edit, and both versions are kept (§7.3). */
  lazy val documentConflict: Signal[Option[DesktopDocumentRegistry.Conflict]] =
    documentSession.map(_.flatMap(_.conflict)).distinct

  /** Resolve this viewer's file conflict.
    *
    * Both answers accept the file's revision as the new save base, so the next
    * save's compare-and-swap succeeds.
    *
    * @param adoptRemoteText
    *   true replaces the editor with the file's text; false keeps the local
    *   edit, which stays dirty until it is saved.
    */
  def resolveDocumentConflict(adoptRemoteText: Boolean): Unit =
    target match
      case ViewTarget.File(session) =>
        DesktopDocumentRegistry.acceptRemote(session).foreach: resolved =>
          if adoptRemoteText then adoptFileText(resolved.sourceText)
      case _ => ()

  /** Drop the edit of this view, because the person chose "Discard" (§7.4).
    *
    * The editor takes the file: the newest version, if a conflict waits, and
    * else the base. The conflict ends. Before, "Discard" only left the view,
    * and the session kept its conflict. A return to the file then showed the
    * banner "Your edit is still here", with no edit.
    */
  def discardEdit(): Unit =
    target match
      case ViewTarget.File(session) =>
        DesktopDocumentRegistry.acceptRemote(session)
        DesktopDocumentRegistry.get(session).foreach(open => adoptFileText(open.sourceText))
      case _ => ()

  /** ViewerSettings below is deliberately NOT branched by the target: theme,
    * panel widths and the like are app-wide preferences, and losing a theme
    * change because it was made while looking at an example would be its own bug.
    */
  private val viewerSettings: Var[ViewerSettings] =
    Library.loadViewerSettings()

  /** Restores the persisted state values to the current ViewerState. */
  private def restorePersistedState(): Unit =
    val restoredDiagram        = persistence.initial
    val restoredViewerSettings = viewerSettings.now()

    restoredDiagram.format
      .flatMap(format => Try(DiagramFormat.valueOf(format)).toOption)
      .foreach(formatSelection.set)
    // Do this after the format, not with it. When auto-detect goes on, it gets
    // the language from the text again, and it must do that over the RESTORED
    // format. `None` means "saved before the mode existed", and those diagrams
    // also get the default. Only an explicit `Some(false)` keeps it off. This
    // is safe for old diagrams, because auto-detect changes the format only on
    // evidence. Only a source that clearly declares a different language
    // overrules a stored format, and that diagram had the wrong format before.
    //
    // The restore writes `Some(true)` in place of `None`, as the code did
    // before the metadata had one `Var`. A store reads the two values as the
    // same (`DesktopLibrary.writeMetadata`), so this alone writes nothing. The
    // view of the flag is distinct, so the restore does not always change it.
    // Thus `initializePersistence` applies auto-detect after the restore.
    val restoredMetadata = restoredDiagram.metadata
    metadata.set(restoredMetadata.copy(autoDetectFormat = Some(restoredMetadata.autoDetectFormat.getOrElse(true))))
    storedName.set(restoredDiagram.name)

    Var.set(
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
    // A write that the store refuses goes to the alert of the view (`ErrorAlert`).
    persistence.refusedWrites.foreach(errorBus.emit)

    // synchronize ViewerState ~> DiagramSnapshot
    Signal
      .combine(
        metadata.signal,
        storedName.signal,
        sourceText.signal,
        formatSelection.signal
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
  def snapshot(): DiagramSnapshot =
    DiagramSnapshot(
      source = sourceText.now(),
      format = Some(formatSelection.now().toString),
      metadata = metadata.now(),
      name = storedName.now()
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

  /** Initializes persistence by restoring state and setting up synchronization.
    *
    * The sync must start after the restore is complete. Before, the sync saw
    * each step of the restore as an edit when the view was built in a running
    * Airstream transaction. `Viewer` builds the view in the map of the route
    * signal, so that is the usual case. In a running transaction, each
    * `Var.set` of the restore makes a new transaction, and Airstream runs it
    * after the current transaction. The sync subscribed before that, so a visit
    * with no edit wrote the half-restored state, and then the restored state.
    * In the browser library, each write also moved `lastModified`.
    *
    * Thus auto-detect and the sync go in new transactions too. Airstream runs
    * the new transactions of a transaction in the order of their creation, and
    * it runs the transactions that each one makes before the next one. Thus
    * auto-detect sees the restored format and flag, and the sync subscribes
    * after the restore and after the change of auto-detect. Outside a
    * transaction, each new transaction runs at once, in the same order.
    */
  def initializePersistence(): Unit =
    restorePersistedState()
    Transaction(_ => phases.applyAutoDetectNow())
    Transaction(_ => setupStateSynchronization())
    attachToSession()
    followMetadata()

  /** Release the store when the view goes away (§10).
    *
    * The view calls this from its unmount, beside the owner it kills. Every
    * store today releases nothing, and the method still exists: a store that
    * holds a handle must have somewhere to give it back, and adding the call
    * later means finding every unmount again.
    */
  def closePersistence(): Unit = persistence.close()

/** One diagram as the viewer saves it: the source, the format, the metadata,
  * and the stored name.
  *
  * The metadata is the type of `gx-core`, so the viewer and the library have
  * one model of it. `format` is the name of a `DiagramFormat`. It is a string,
  * so an unknown format round-trips.
  *
  * `name` is the stored name of a browser diagram (the web app), or the name
  * of an example. It is not metadata. A file has no stored name, because its
  * name is the title in its source, or else its file name (CONTEXT.md). Thus
  * `name` is empty for a file, and `FilePersistence` does not read it.
  */
final case class DiagramSnapshot(
    source:   String,
    format:   Option[String],
    metadata: DiagramMetadata,
    name:     String = ""
) derives CanEqual

object DiagramSnapshot:
  val minimalGraphText = "digraph G {\n}"

  /** The name of a diagram that the person did not rename. The display uses the title
    * that the diagram declares in place of this name (ViewerState.displayTitle,
    * Library.diagramCardInfo). The stored name changes only when the person renames the
    * diagram, so a rename always wins. A file has no stored name: its name is the title
    * in its source, or else its file name.
    */
  val defaultDiagramName = "Untitled"

  val empty: DiagramSnapshot = minimal()

  def minimal(source: Option[String] = None): DiagramSnapshot =
    DiagramSnapshot(
      source = source.getOrElse(minimalGraphText),
      format = None,
      metadata = DiagramMetadata.empty,
      name = defaultDiagramName
    )

  /** The shape of a diagram in the browser library (`localStorage`).
    *
    * The shape is an API: each browser holds payloads in it, and
    * `LocalStorageMigration` reads it. Thus the field names, the types, the
    * order and the defaults must not change. upickle omits a field that is
    * equal to its default. `BrowserPayloadSpec` pins the JSON.
    *
    * `projectName` is the stored name ([[DiagramSnapshot.name]]). The browser
    * library has never stored tags or notes. A read gives their defaults, and
    * a write drops them. The sets are typed, so they
    * cannot hold a ref that this build does not parse. Only this viewer writes
    * the browser library, so such a ref does not occur there.
    */
  private final case class Stored(
      hiddenElements: ElementIds = ElementIds(),
      // Folded groups, like hiddenElements: a view setting, saved with the page.
      collapsedGroups: Set[GroupId] = Set.empty,
      projectName:    String = "",
      source:         String = "",
      format:         Option[String] = None,
      // None for each diagram that the app saved before the mode existed.
      // `restorePersistedState` reads None as on.
      autoDetectFormat: Option[Boolean] = None
  ) derives ReadWriter

  given ReadWriter[DiagramSnapshot] = readwriter[Stored].bimap[DiagramSnapshot](toStored, fromStored)

  private def toStored(snapshot: DiagramSnapshot): Stored =
    val m = snapshot.metadata
    Stored(
      hiddenElements = MetadataOps.hiddenIn(m),
      collapsedGroups = MetadataOps.collapsedIn(m),
      projectName = snapshot.name,
      source = snapshot.source,
      format = snapshot.format,
      autoDetectFormat = m.autoDetectFormat
    )

  private def fromStored(stored: Stored): DiagramSnapshot =
    DiagramSnapshot(
      source = stored.source,
      format = stored.format,
      metadata = DiagramMetadata(
        hiddenElements = MetadataOps.render(stored.hiddenElements),
        collapsedGroups = stored.collapsedGroups.map(ElementRef.render),
        autoDetectFormat = stored.autoDetectFormat
      ),
      name = stored.projectName
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
