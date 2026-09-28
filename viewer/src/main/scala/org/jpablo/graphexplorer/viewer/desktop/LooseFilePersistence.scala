package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.state.{
  DiagramPersistence,
  DocumentSessionId,
  PersistedDiagramState,
  SaveResult
}

import scala.concurrent.{ExecutionContext, Future}

/** A loose file: the file itself is the store (§7).
  *
  * The file is authoritative (§2). There is no record, so there is nothing to
  * autosave into, and §7.1 keeps every edit in memory until the person asks for
  * a save. A save is then a compare-and-swap on the revision the page holds.
  *
  * Direct autosave is NOT inherited from library persistence. A write to a file
  * that another tool also edits needs the conflict answer that §7.3 defines,
  * and a keystroke is not a moment to make that decision.
  */
final class LooseFilePersistence(session: DocumentSessionId) extends DiagramPersistence:

  /** The text the shell read, and the file name as the title.
    *
    * §7.2 lists what a loose file cannot keep: hidden elements, collapsed
    * groups, tags, notes, a folder. Those start empty here and stay in memory.
    * The UI labels them as not saved, and Phase 2 item 6 adds that label.
    */
  lazy val initial: PersistedDiagramState =
    DesktopDocumentRegistry.get(session) match
      case Some(open) =>
        PersistedDiagramState.minimal(Some(open.sourceText)).copy(projectName = open.baseName)
      case None =>
        // The route named a session the registry does not hold. A viewer opens
        // on an empty diagram rather than on the text of some other file, and
        // `saveNow` below refuses, because there is no path to write to.
        PersistedDiagramState.minimal()

  private var current = initial

  /** In memory, and nowhere else (§7.1). */

  /** Empty: a loose file follows its SESSION, not this store (§7.3).
    *
    * `Persistence.followDocumentSession` already watches the session, and it
    * carries the rule a record does not need — an external change must not
    * replace a dirty editor silently. Emitting here as well would give one
    * viewer two sources for the same text.
    */
  def external: com.raquo.airstream.core.EventStream[PersistedDiagramState] =
    com.raquo.airstream.core.EventStream.empty

  def update(next: PersistedDiagramState): Unit = current = next

  /** §7.1: a compare-and-swap write of this session's path.
    *
    * The revision comes from the registry and not from a field of this object,
    * because a save advances it and an external change replaces it. A copy held
    * here would go stale, and a stale base revision turns every second save
    * into a conflict.
    */
  def saveNow(next: PersistedDiagramState)(using ExecutionContext): Future[SaveResult] =
    update(next)
    DesktopDocumentRegistry.get(session) match
      case None =>
        Future.successful(SaveResult.Failed("this document session is no longer open"))
      case Some(open) =>
        DesktopIpc
          .saveDocument(open.path, next.source, open.revision)
          .map:
            case DesktopIpc.SaveOutcome.Saved(path, revision) =>
              // The registry advances, so the NEXT save compares against what
              // this one wrote.
              DesktopDocumentRegistry.record(path, revision, next.source)
              SaveResult.Saved
            case DesktopIpc.SaveOutcome.Conflict(_) =>
              SaveResult.Conflict("The file changed on disk. Reload it and try again.")
            case DesktopIpc.SaveOutcome.Failed(message) =>
              SaveResult.Failed(message)
            case DesktopIpc.SaveOutcome.Unavailable =>
              SaveResult.Unsupported("Saving a file needs the desktop app")

  /** The session stays. A person can navigate to Home and back, and §12 keeps
    * the watch separate from the view, so closing a view must not end a session
    * the shell still follows.
    */
  def close(): Unit = ()

  private[desktop] def latest: PersistedDiagramState = current
