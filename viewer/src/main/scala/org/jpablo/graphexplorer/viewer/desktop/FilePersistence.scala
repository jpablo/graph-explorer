package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.core.{EventStream, Signal}
import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.library.DesktopLibrary
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.{DiagramPersistence, DiagramSnapshot, DocumentSessionId, SaveResult}

import scala.concurrent.{ExecutionContext, Future}

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
    */
  def saveNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    update(next)
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
            outcome match
              case DesktopIpc.SaveOutcome.Saved(_, _)         => SaveResult.Saved
              case DesktopIpc.SaveOutcome.Conflict(_)         =>
                SaveResult.Conflict("The file changed on disk. Reload it and try again.")
              case DesktopIpc.SaveOutcome.NotWatched(message) => SaveResult.Failed(message)
              case DesktopIpc.SaveOutcome.Failed(message)     => SaveResult.Failed(message)
              case DesktopIpc.SaveOutcome.Unavailable         => SaveResult.Unsupported("Saving a file needs the desktop app")

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
