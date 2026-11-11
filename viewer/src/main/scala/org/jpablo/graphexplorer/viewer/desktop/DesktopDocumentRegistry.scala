package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.viewer.state.DocumentSessionId

/** What the page knows about each open file (§5).
  *
  * The registry is the indirection that keeps the path out of the route. A
  * route names a [[DocumentSessionId]]. This registry maps that id to the
  * canonical path, to the revision the page last saw, and to the source text.
  *
  * Process-global, like [[DesktopBridge]] and [[DesktopOpenRequests]]: one
  * window holds these listeners, and a session outlives the view that shows it.
  * A person can navigate to Home and back, and the session must survive that.
  *
  * `FilePersistence` reads its session from here, and it aims each save with
  * the path and the revision of the session.
  *
  * §7.3 gives a session three values: `base`, `local` and `remote`. `base` is
  * [[Session.revision]] with [[Session.sourceText]]: the revision that the
  * page loaded, adopted or saved, and the text that goes with it. `remote` is
  * [[Session.conflict]]: the newest version of the file, while it disagrees
  * with an edit. `local` is the text of an editor. A view gives its editor to
  * the registry ([[attach]]), and the registry reads the text when a version
  * of the file arrives. The page holds all three texts, so it compares the
  * texts and needs no hash.
  *
  * The §7.3 rule has ONE place: [[receive]]. Each version of the file that the
  * shell reports goes through it, and only it decides if the base moves.
  */
object DesktopDocumentRegistry:

  /** The file changed on disk while the page held an edit (§7.3).
    *
    * Both versions are kept: the edit stays in the editor, and the file's text
    * waits here. §7.3 gives the rule that makes this necessary — dirty text is
    * never replaced silently.
    */
  case class Conflict(revision: String, text: String) derives CanEqual

  /** One open file.
    *
    * `path` is canonical. The shell canonicalizes it before it sends the
    * document event; the page has no file system and can canonicalize nothing.
    * Two spellings of one file would therefore make two sessions, which is why
    * the rule belongs to the shell.
    */
  case class Session(
      id:         DocumentSessionId,
      path:       String,
      revision:   String,
      sourceText: String,
      /** `remote`, and only while it disagrees with an edit (§7.3). It is
        * always the newest version of the file that the shell reported.
        */
      conflict:   Option[Conflict] = None
  ) derives CanEqual:

    /** The name to show for this file.
      *
      * §13 permits a base name where it does not permit the path. The split
      * accepts both separators, because the separator is a property of the path
      * that the shell sends, and not of the machine that runs this page.
      */
    def baseName: String =
      path.split("[/\\\\]").filter(_.nonEmpty).lastOption.getOrElse(path)

    /** True when the file has `text`: a save of `text` writes the same text
      * as a save of the file over itself (`LineEnding.sameOnDisk`).
      *
      * The two texts can differ in the line endings and in the final newline,
      * because a save keeps those of the file (V-04). A canvas edit prints LF
      * with no final newline, so an exact comparison called a CRLF file dirty
      * after each save of it. A file always holds its own text, also a file
      * with mixed line endings.
      */
    def holds(text: String): Boolean = LineEnding.savedOver(sourceText, text) == onDisk

    /** `LineEnding.sameOnDisk(sourceText, text)` computes this for each call.
      * It does not change for one session value, and each keystroke of a file
      * view calls [[holds]].
      */
    private lazy val onDisk = LineEnding.savedOver(sourceText, sourceText)

    /** The base moves to this version of the file, and a conflict ends. */
    def rebased(revision: String, text: String): Session =
      copy(revision = revision, sourceText = text, conflict = None)

  /** A view of a session, as the registry sees it.
    *
    * `text` reads the editor now (`local`). `take` puts the text of the file
    * in the editor, and the registry calls it only for an editor that holds
    * the base: a clean editor has no edit to lose.
    */
  final class Editor(val text: () => String, val take: String => Unit)

  /** A `Var`, so a view can follow its own session (§10).
    *
    * A dirty marker and a conflict banner have to appear the moment the state
    * changes, and a plain Map would need every view to poll for that.
    */
  private val sessions: Var[Map[DocumentSessionId, Session]] = Var(Map.empty)

  /** The editors of each session. Not a `Var`: nothing observes it, and the
    * registry reads it only when a version of the file arrives.
    */
  private var editors: Map[DocumentSessionId, List[Editor]] = Map.empty

  /** The text of a save of this page that has no answer yet, for each session.
    * The shell sends the event of a save BEFORE its reply, and this text tells
    * the event of the save from a write of a different tool.
    *
    * Not in [[sessions]]: no view shows it, and a change of [[sessions]] goes
    * through each view of the session.
    */
  private var saving: Map[DocumentSessionId, String] = Map.empty

  private[graphexplorer] def reset(): Unit =
    editors = Map.empty
    saving = Map.empty
    sessions.set(Map.empty)

  def get(id: DocumentSessionId): Option[Session] = sessions.now().get(id)

  /** One session as it changes. Laminar teardown ends the subscription, so a
    * view that unmounts stops following the file it showed (§10).
    */
  def signal(id: DocumentSessionId): Signal[Option[Session]] =
    sessions.signal.map(_.get(id)).distinct

  /** The session for a path, if the page holds one.
    *
    * An exact comparison, and it needs no separator normalising. Both sides
    * come from the shell's canonical path: the document event that recorded
    * the session, and the open request that looks it up. They agree by
    * construction.
    *
    * A linear search, on purpose. A person opens few files at once, and one map
    * cannot fall out of step with itself. A second index from path to id would
    * be faster, and would add an invariant to keep.
    */
  def find(path: String): Option[Session] = sessions.now().values.find(_.path == path)

  def all: List[Session] = sessions.now().values.toList

  private def put(session: Session): Unit = sessions.update(_.updated(session.id, session))

  /** Record what the shell sent, and return the session for that file.
    *
    * The operation is idempotent per path. A second `gx open` of one file gives
    * the same id as the first, and so gives the same route. Without this, each
    * open would mint an id, and the back button would walk through dead routes
    * that all name one file.
    *
    * §4.2 states the related rule for the shell: registration of a watch is
    * idempotent, but display is a separate operation. This method holds the
    * page's half. A new path makes a session, and the text is its base. For a
    * session that exists, the text is a version of the file, and [[receive]]
    * decides if the base moves. A document event, a reopen from the library
    * and an activation all come here.
    */
  def record(path: String, revision: String, sourceText: String): Session =
    find(path) match
      case None =>
        val session = Session(DocumentSessionId.random, path, revision, sourceText)
        put(session)
        session
      case Some(existing) => receive(existing, revision, sourceText)

  /** The §7.3 rule: a version of the file arrives, and this decides if the
    * base moves.
    *
    * The base moves, and no question comes, when one of these is true:
    *   - the file has the base text, with other line endings or another final
    *     newline at most. A save keeps those of the file, so it is not a
    *     change.
    *   - the version is the text of a save of this page (the event of the
    *     save). The editor can have more keys than the save, and it keeps them.
    *   - no editor holds an edit. Each editor holds the base, or holds the
    *     new text already. An editor that holds the base takes the new text.
    *     A session that no view shows has no editor, so it takes the file.
    *
    * Otherwise an editor holds an edit, and the file has a different text:
    * the base stays, the version becomes the conflict, and the banner shows.
    * A save then sends the old base, and the shell refuses it. A conflict
    * always holds the NEWEST version, so a later event replaces it and does
    * not clear it.
    *
    * Each version that is not the event of a save ends the wait for a save:
    * after it, the reply of that save must not move the base back.
    */
  private def receive(session: Session, revision: String, text: String): Session =
    val ownSave = saving.get(session.id).contains(text)
    saving -= session.id
    // The editors matter only for a version that is not the base and not our
    // own save. An editor that holds the base is clean, and it takes the text.
    val (clean, dirty) =
      if ownSave || session.holds(text) then (Nil, Nil)
      else editors.getOrElse(session.id, Nil).partition(view => session.holds(view.text()))
    val next =
      if dirty.exists(view => !LineEnding.sameOnDisk(text, view.text())) then
        session.copy(conflict = Some(Conflict(revision, text)))
      else session.rebased(revision, text)
    put(next)
    if next.conflict.isEmpty then clean.foreach(_.take(text))
    next

  /** End a conflict by making the file's version the base.
    *
    * ONE operation for each answer, because each needs the same thing: a base
    * that matches the file, so the next save's compare-and-swap succeeds. "Load
    * the file", "Keep my edit" and "Discard" differ only in whether the EDITOR
    * takes the text, and that is the caller's decision, not the registry's.
    */
  def acceptRemote(id: DocumentSessionId): Option[Session] =
    for
      session <- get(id)
      remote  <- session.conflict
    yield
      val resolved = session.rebased(remote.revision, remote.text)
      put(resolved)
      resolved

  /** A save of this page starts, with the text that it writes. */
  def beginSave(id: DocumentSessionId, text: String): Unit =
    saving = saving.updated(id, text)

  /** The shell answered a save of `text`. `revision` is the revision that the
    * save wrote, or `None` when the save did not write.
    *
    * The base moves only while the session waits for THIS save. The event of
    * the save comes before the reply and moves the base first. A version of
    * the file from a different writer also ends the wait. In both cases the
    * reply changes nothing, so it cannot undo a newer version of the file.
    */
  def endSave(id: DocumentSessionId, text: String, revision: Option[String]): Unit =
    if saving.get(id).contains(text) then
      saving -= id
      for written <- revision; session <- get(id) do put(session.rebased(written, text))

  /** Give the editor of a view to its session (§7.3), and return the call
    * that takes it back.
    *
    * The view gives it for its whole life. When the last editor of a session
    * goes, nothing holds an edit of that session. A conflict is then false,
    * and the file's version becomes the base: a view that opens later shows
    * the file, with no banner.
    */
  def attach(id: DocumentSessionId, editor: Editor): () => Unit =
    editors = editors.updated(id, editors.getOrElse(id, Nil) :+ editor)
    () =>
      editors.getOrElse(id, Nil).filterNot(_ eq editor) match
        case Nil =>
          editors = editors.removed(id)
          acceptRemote(id)
          ()
        case rest => editors = editors.updated(id, rest)

  /** The file of a session has a new path, because the person renamed it.
    *
    * The id stays, so the route and the view that show the session stay too.
    * The text stays, so the editor takes nothing, and keeps its language. The
    * shell moved its watch to the new path (`rename_document`). Thus the next
    * document event names the new path, and `record` finds this session. A
    * different session that already has the new path is a stale one, and it
    * goes.
    */
  def move(id: DocumentSessionId, newPath: String): Option[Session] =
    get(id).map: session =>
      val moved = session.copy(path = newPath)
      sessions.update(all => all.filterNot((other, s) => other != id && s.path == newPath).updated(id, moved))
      moved

  /** Release a session the page no longer shows.
    *
    * A session that stays after its watch stops keeps the text of a file in
    * memory, and offers a route that shows text the shell no longer follows.
    */
  def forget(id: DocumentSessionId): Unit =
    editors = editors.removed(id)
    saving = saving.removed(id)
    sessions.update(_.removed(id))
