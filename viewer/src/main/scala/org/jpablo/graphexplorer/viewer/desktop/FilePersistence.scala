package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.library.DesktopLibrary
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.{DiagramPersistence, DiagramSnapshot, DocumentSessionId, SaveResult}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** The store of a diagram on the desktop: a file (§7, ADR 0001).
  *
  * The file is the only copy of the source. §7.1 keeps each edit of the source
  * in memory until the person asks for a save. A save is then a
  * compare-and-swap on the revision that the page holds. A keystroke is not
  * the correct moment for that decision, because a different tool can edit
  * the same file (§7.3).
  *
  * The metadata is different. It is not in the file, so a change to it cannot
  * conflict with the file. Each change of the metadata goes to the entry of the
  * file in the library at once (`DesktopLibrary.writeMetadata`). The first
  * change makes the entry, and that puts the diagram in the library, as
  * `gx hide` does. An outside change to the entry, for example from `gx hide`,
  * comes back through [[external]].
  *
  * `library` is `None` when the desktop has no library (a test, or a desktop
  * whose library did not load). Then the metadata stays in memory.
  *
  * The layout (ADR 0004) is in a side file next to the file:
  * `<base name>.layout.json`. The store reads the side file when the view
  * opens ([[externalLayout]]). The read goes through the shell, so it ends
  * after the view opens. Until it ends, [[layoutReadPending]] is true, and the
  * view holds its first drawing. Thus the first drawing shows the stored
  * positions, and not the automatic layout. The read is the source of the
  * layout and of its revision. The library keeps a copy of each side file
  * for its cards, but this store does not use it: that copy can be older
  * than the file on disk.
  *
  * A change of the layout stays in memory until a save, as an edit of the
  * source does, and the view is dirty until then ([[savedLayout]]). A save
  * writes the side file with a compare-and-swap on its revision. A layout
  * with no content removes the side file.
  */
final class FilePersistence(session: DocumentSessionId, library: Option[DesktopLibrary]) extends DiagramPersistence:

  /** The text that the shell read, its language, and the metadata of the
    * entry. A file with no entry starts with empty metadata.
    *
    * The language is the language that the text declares, or else the
    * language of the extension (`DiagramFormat.ofFile`). Before, the
    * format was `None`, and the viewer took `DiagramFormat.detect`, which
    * falls back to DOT. A `.mmd` file with no header line opened as DOT.
    */
  lazy val initial: DiagramSnapshot =
    DesktopDocumentRegistry.get(session) match
      case Some(open) =>
        DiagramSnapshot(
          source = open.sourceText,
          format = DiagramFormat.ofFile(open.path, open.sourceText).map(_.toString),
          metadata = library.flatMap(_.metadataOf(open.path)).getOrElse(DiagramMetadata.empty)
        )
      case None =>
        // The route named a session the registry does not hold. A viewer opens
        // on an empty diagram rather than on the text of some other file, and
        // `saveNow` below refuses, because there is no path to write to.
        DiagramSnapshot.minimal()

  private var current = initial

  /** The side file as this store knows it: the path of its diagram, the layout, the revision of its bytes (`None`:
    * no side file), and the reason when this build cannot read it.
    */
  private final case class DiskLayout(
      path:       String,
      layout:     DiagramLayout,
      revision:   Option[String],
      unreadable: Option[String] = None
  ) derives CanEqual

  /** `None` until the read of the side file ends, and always with no shell. */
  private val disk: Var[Option[DiskLayout]] = Var(None)

  /** The layout of each read of the side file. A save does not come through here. */
  private val loadedLayouts = EventBus[DiagramLayout]()

  override def externalLayout: EventStream[DiagramLayout] = loadedLayouts.events

  override def savedLayout: Signal[Option[DiagramLayout]] = disk.signal.map(_.map(_.layout))

  override def savedLayoutNow: Option[DiagramLayout] = disk.now().map(_.layout)

  /** The session whose side file this store reads when it opens. `None` with no shell, or with no session. */
  private val readOnOpen: Option[DesktopDocumentRegistry.Session] =
    DesktopDocumentRegistry.get(session).filter(_ => DesktopIpc.available)

  private var readPending: Boolean = readOnOpen.isDefined

  override def layoutReadPending: Boolean = readPending

  // The read of the side file goes through the shell, so it ends after the
  // view opens. The view holds its first drawing until then, and it takes the
  // layout before that drawing (`Persistence.followLayout`). Each end of the
  // read sends a layout, so the view never waits for a read that ended.
  // A failed read counts as no side file: a later save then expects no side
  // file, and the shell refuses it if one exists.
  locally:
    import scala.concurrent.ExecutionContext.Implicits.global
    for open <- readOnOpen do
      DesktopFiles
        .readLayout(open.path)
        .map:
          case None => DiskLayout(open.path, DiagramLayout.empty, None)
          case Some(onDisk) =>
            DiagramLayout.parse(onDisk.text) match
              case Right(layout) => DiskLayout(open.path, layout, Some(onDisk.revision))
              case Left(message) =>
                org.scalajs.dom.console.warn(s"The layout file of ${open.path} does not load: $message")
                DiskLayout(open.path, DiagramLayout.empty, Some(onDisk.revision), Some(message))
        .recover:
          case error =>
            org.scalajs.dom.console.warn(s"The layout file of ${open.path} does not load: ${error.getMessage}")
            DiskLayout(open.path, DiagramLayout.empty, None)
        .foreach: read =>
          readPending = false
          disk.set(Some(read))
          loadedLayouts.emit(read.layout)

  /** Write the side file when the layout of `next` is not the layout on disk. */
  private def writeLayout(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    (DesktopDocumentRegistry.get(session), disk.now()) match
      case (None, _) => Future.successful(SaveResult.Failed("this document session is no longer open"))
      // No shell, or the read did not end: the store knows no side file, so it writes none.
      case (_, None) => Future.successful(SaveResult.Saved)
      case (Some(open), Some(known)) =>
        // After a rename, the side file of the new path is a different file.
        val base = if known.path == open.path then known.revision else None
        known.unreadable match
          case Some(message) if known.path == open.path =>
            Future.successful(SaveResult.Failed(s"The app did not write the layout file, because it cannot read it: $message"))
          case _ if known.path == open.path && known.layout == next.layout => Future.successful(SaveResult.Saved)
          case _ =>
            val text = Option.unless(next.layout == DiagramLayout.empty)(DiagramLayout.render(next.layout))
            if text.isEmpty && base.isEmpty then
              disk.set(Some(DiskLayout(open.path, next.layout, None)))
              Future.successful(SaveResult.Saved)
            else
              DesktopFiles
                .writeLayout(open.path, text, base)
                .map: revision =>
                  disk.set(Some(DiskLayout(open.path, next.layout, revision)))
                  SaveResult.Saved
                .recover:
                  case DesktopIpc.IpcFailure("LAYOUT_CONFLICT", _) =>
                    SaveResult.Conflict("The layout file changed on disk. Reload the diagram and try again.")
                  case error => SaveResult.Failed(s"The app did not write the layout file: ${error.getMessage}")

  /** A locked file saves its layout, and not its source (ADR 0004). */
  override def saveLayoutNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    update(next)
    writeLayout(next)

  /** The metadata of the entry, after each change from a different writer.
    *
    * The stream follows the PATH of the session, because a rename moves the
    * file and its entry. It sends the metadata of each new value of the entry.
    * A write of this viewer comes back too. The viewer adopts a value only
    * when it differs, and an adoption writes nothing, because the entry
    * already holds what the adoption gives (`DesktopLibrary.writeMetadata`).
    *
    * The source does NOT come through here. The file follows its SESSION
    * (§7.3, `DesktopDocumentRegistry.receive`), which has the rule that an
    * outside change must not replace a dirty editor silently.
    */
  def external: EventStream[DiagramMetadata] =
    library match
      case None => EventStream.empty
      case Some(lib) =>
        DesktopDocumentRegistry
          .signal(session)
          .map(_.map(_.path))
          .distinct
          .flatMapSwitch:
            case None       => Signal.fromValue(Option.empty[LibraryEntry])
            case Some(path) => lib.entrySignal(path)
          .changes
          .collect { case Some(entry) => entry.metadata }

  /** Keep the edit in memory, and give the metadata to the library, with the
    * metadata from before this edit. The library writes only a change of the
    * metadata of the viewer, so an edit of the source alone writes nothing.
    */
  def update(next: DiagramSnapshot): Unit =
    val before = current
    current = next
    for
      lib  <- library
      open <- DesktopDocumentRegistry.get(session)
    do lib.writeMetadata(open.path, before.metadata, next.metadata)

  /** §7.1: a compare-and-swap write of this session's path.
    *
    * The revision comes from the registry and not from a field of this object,
    * because a save advances it and an external change replaces it. A copy held
    * here would go stale, and a stale base revision turns every second save
    * into a conflict.
    *
    * The registry knows that the save is in flight (`beginSave`). The shell
    * sends the event of the save BEFORE its reply, and the person can type
    * between the two. The event then moves the base, and the editor keeps the
    * new keys. Before, the page saw the event as a write of a different tool,
    * and it showed a conflict with its own save. The reply moves the base only
    * when no version of the file came after the save started (`endSave`).
    *
    * V-04: the save keeps the line endings of the file and its end (a final
    * newline, or none), as `gx` does (`LineEnding.savedOver`). A canvas edit
    * prints LF with no final newline. Before, a Save wrote that text as it
    * was, and one change to one attribute changed each line of a CRLF file.
    * Each save of the desktop app comes through this method.
    *
    * One save of a session is in flight at a time. A save sends the base
    * revision of the session, and the base moves only after the shell writes
    * the save. Thus a second save before that write sends the old base, and
    * the shell refuses it with DOCUMENT_CONFLICT: the person saw "The file
    * changed on disk" for a write of their own. Now a save that starts while
    * a save is in flight waits for it, and then it writes with the new base.
    * One save waits at most. A save that starts while one waits joins it, and
    * the waiting save writes the snapshot of the last request.
    */
  def saveNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    update(next)
    FilePersistence.inFlight.get(session) match
      case None => start(next)
      case Some(Some(waiting)) =>
        waiting.snapshot = next
        waiting.result.future
      case Some(None) =>
        val waiting = FilePersistence.Waiting(next)
        FilePersistence.inFlight = FilePersistence.inFlight.updated(session, Some(waiting))
        waiting.result.future

  /** Send one save of `next`. When it ends, start the save that waits for it. */
  private def start(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    FilePersistence.inFlight = FilePersistence.inFlight.updated(session, None)
    // A throw here must also end the save. Else the session stays "in
    // flight", and each later save waits forever.
    val result =
      try write(next)
      catch case NonFatal(error) => Future.failed(error)
    result.onComplete: _ =>
      FilePersistence.inFlight.get(session).flatten match
        case Some(waiting) => waiting.result.completeWith(start(waiting.snapshot))
        case None          => FilePersistence.inFlight = FilePersistence.inFlight.removed(session)
    result

  /** The compare-and-swap write of `next`, with the base of the session now.
    *
    * It does not call [[update]]. A save that waited starts after the
    * request, and the editor can hold newer text then. An `update` here
    * would move `current` back to the older snapshot.
    */
  private def write(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    DesktopDocumentRegistry.get(session) match
      case None =>
        Future.successful(SaveResult.Failed("this document session is no longer open"))
      case Some(open) =>
        val text = LineEnding.savedOver(open.sourceText, next.source)
        DesktopDocumentRegistry.beginSave(session, text)
        DesktopIpc
          .saveDocument(open.path, text, open.revision)
          .flatMap:
            case DesktopIpc.SaveOutcome.NotWatched(_) => saveWithNewWatch(open, text)
            case outcome                              => Future.successful(outcome)
          .map: outcome =>
            // Each outcome ends the save. Only a write moves the base, so the
            // NEXT save compares against what this one wrote. The base holds
            // the text that is in the file, so the next save keeps the same
            // line endings.
            val written = outcome match
              case DesktopIpc.SaveOutcome.Saved(_, revision) => Some(revision)
              case _                                         => None
            DesktopDocumentRegistry.endSave(session, text, written)
            outcome
          .flatMap: outcome =>
            // The side file of the layout is a different file. It gets its
            // write also when the write of the source fails.
            writeLayout(next).map(layoutResult => (outcome, layoutResult))
          .map: (outcome, layoutResult) =>
            val sourceResult = outcome match
              case DesktopIpc.SaveOutcome.Saved(_, _)         => SaveResult.Saved
              case DesktopIpc.SaveOutcome.Conflict(_)         =>
                SaveResult.Conflict("The file changed on disk. Reload it and try again.")
              case DesktopIpc.SaveOutcome.NotWatched(message) => SaveResult.Failed(message)
              case DesktopIpc.SaveOutcome.Failed(message)     => SaveResult.Failed(message)
              case DesktopIpc.SaveOutcome.Unavailable         => SaveResult.Unsupported("Saving a file needs the desktop app")
            if sourceResult == SaveResult.Saved then layoutResult else sourceResult

  /** The shell lost the watch of the file, so the save wrote nothing. Register
    * the watch again (`open_document`), and retry the save one time.
    *
    * A watch can go for many causes: `gx unwatch`, or an open that the screen
    * left before it showed the file. Without a watch, each save of the view
    * fails with "path is not currently watched".
    *
    * The retry sends the SAME base revision. The shell compares it with the
    * file on disk, so an outside change before the retry comes back as a
    * conflict and not as a write over that change. The new watch sends a
    * document event, and that event can end the wait for this save. Thus
    * `beginSave` comes again before the retry.
    */
  private def saveWithNewWatch(open: DesktopDocumentRegistry.Session, text: String)(using
      ExecutionContext
  ): Future[DesktopIpc.SaveOutcome] =
    DesktopIpc
      .openFile(open.path)
      .flatMap: _ =>
        DesktopDocumentRegistry.beginSave(session, text)
        DesktopIpc.saveDocument(open.path, text, open.revision)
      .recover:
        case error => DesktopIpc.SaveOutcome.Failed(s"the file has no watch, and a new watch failed: ${error.getMessage}")

  /** The session stays. A person can navigate to Home and back, and §12 keeps
    * the watch separate from the view, so closing a view must not end a session
    * the shell still follows.
    */
  def close(): Unit = ()

  private[desktop] def latest: DiagramSnapshot = current

object FilePersistence:

  /** A save that waits for the save in flight of its session. A request that
    * comes while it waits gives its snapshot, so the save writes the last one.
    */
  private final class Waiting(var snapshot: DiagramSnapshot):
    val result: Promise[SaveResult] = Promise()

  /** The sessions that have a save in flight, each with the save that waits
    * for it, if one waits.
    *
    * For each session, not for each view: the views of one file share one
    * base revision (`DesktopDocumentRegistry`), and the registry follows one
    * save of a session at a time (`beginSave`).
    */
  private var inFlight: Map[DocumentSessionId, Option[Waiting]] = Map.empty
