package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.viewer.state.DocumentSessionId

/** What the page knows about each open loose file (§5).
  *
  * The registry is the indirection that keeps the path out of the route. A
  * route names a [[DocumentSessionId]]. This registry maps that id to the
  * canonical path, to the revision the page last saw, and to the source text.
  *
  * Process-global, like [[DesktopBridge]] and [[DesktopOpenRequests]]: one
  * window holds these listeners, and a session outlives the view that shows it.
  * A person can navigate to Home and back, and the session must survive that.
  *
  * The registry replaces no behaviour yet. [[DesktopBridge]] still holds its
  * own `documentRef`, and still aims a save with it. Phase 2 item 4 moves the
  * save onto `LooseFilePersistence`, and that implementation reads its session
  * from here.
  *
  * §7.3 gives a session three values: `base`, `local` and `remote`. Two of
  * them live here. `base` is [[Session.revision]] with [[Session.sourceText]] —
  * the revision the page loaded or last saved, and the text that goes with it.
  * `remote` appears as [[Session.conflict]] when the file changed under an edit.
  *
  * `local` is NOT here. The editor holds it, and the viewer compares the two.
  * §7.3 calls all three hashes; the page holds both texts, so it compares the
  * texts and needs no hash at all.
  */
object DesktopDocumentRegistry:

  /** The file changed on disk while the page held an edit (§7.3).
    *
    * Both versions are kept: the edit stays in the editor, and the file's text
    * waits here. §7.3 gives the rule that makes this necessary — dirty text is
    * never replaced silently.
    */
  case class Conflict(revision: String, text: String) derives CanEqual

  /** One open loose file.
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
      /** `remote`, and only while it disagrees with an edit (§7.3). */
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

  /** A `Var`, so a view can follow its own session (§10).
    *
    * A dirty marker and a conflict banner have to appear the moment the state
    * changes, and a plain Map would need every view to poll for that.
    */
  private val sessions: Var[Map[DocumentSessionId, Session]] = Var(Map.empty)

  private[desktop] def reset(): Unit = sessions.set(Map.empty)

  def get(id: DocumentSessionId): Option[Session] = sessions.now().get(id)

  /** One session as it changes. Laminar teardown ends the subscription, so a
    * view that unmounts stops following the file it showed (§10).
    */
  def signal(id: DocumentSessionId): Signal[Option[Session]] =
    sessions.signal.map(_.get(id)).distinct

  /** The session for a path, if the page holds one.
    *
    * A linear search, on purpose. A person opens few files at once, and one map
    * cannot fall out of step with itself. A second index from path to id would
    * be faster, and would add an invariant to keep.
    */
  def find(path: String): Option[Session] = sessions.now().values.find(_.path == path)

  def all: List[Session] = sessions.now().values.toList

  /** Record what the shell sent, and return the session for that file.
    *
    * The operation is idempotent per path. A second `gx open` of one file gives
    * the same id as the first, and so gives the same route. Without this, each
    * open would mint an id, and the back button would walk through dead routes
    * that all name one file.
    *
    * §4.2 states the related rule for the shell: registration of a watch is
    * idempotent, but display is a separate operation. This method holds the
    * page's half. It refreshes the revision and the text of a session that
    * exists, and it keeps the id.
    */
  def record(path: String, revision: String, sourceText: String): Session =
    val session =
      find(path) match
        // The conflict clears: this IS the new base, so there is no longer a
        // remote version waiting to be reconciled.
        case Some(existing) => existing.copy(revision = revision, sourceText = sourceText, conflict = None)
        case None           => Session(DocumentSessionId.random, path, revision, sourceText)
    sessions.update(_.updated(session.id, session))
    session

  /** The file changed, and the page holds an edit (§7.3).
    *
    * The base does NOT move. The edit and the file are both kept, and the
    * person chooses between them.
    */
  def markConflict(id: DocumentSessionId, revision: String, text: String): Unit =
    sessions.update(_.updatedWith(id)(_.map(_.copy(conflict = Some(Conflict(revision, text))))))

  /** End a conflict by making the file's version the base.
    *
    * ONE operation for both answers, because both need the same thing: a base
    * that matches the file, so the next save's compare-and-swap succeeds. "Take
    * theirs" and "keep mine" differ only in whether the EDITOR adopts the text,
    * and that is the caller's decision, not the registry's.
    */
  def acceptRemote(id: DocumentSessionId): Option[Session] =
    get(id).flatMap(_.conflict).map: remote =>
      val resolved = get(id).get.copy(revision = remote.revision, sourceText = remote.text, conflict = None)
      sessions.update(_.updated(id, resolved))
      resolved

  /** Release a session the page no longer shows.
    *
    * A session that stays after its watch stops keeps the text of a file in
    * memory, and offers a route that shows text the shell no longer follows.
    */
  def forget(id: DocumentSessionId): Unit =
    sessions.update(_.removed(id))
