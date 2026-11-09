package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.models.DiagramId

/** What a viewer shows (§2 of docs/desktop-open-targets-and-persistence.md).
  *
  * A browser diagram and a file have different owners and different rules for
  * storage. Before, `ViewerState(id)` took one id for the two. Thus a file had
  * to use a `DiagramId` that named no diagram. §6 removes that:
  * [[DiagramPersistence.forTarget]] matches on this enum, so the owner of a
  * view decides how the app saves it.
  *
  * The rules for the owners:
  *
  *   - [[Diagram]] is a diagram of the WEB app. The browser library keeps its
  *     source, because a browser has no files.
  *   - [[File]] is a diagram of the desktop app: a file (ADR 0001). The file is
  *     the only copy of the source, and a save writes it with a
  *     compare-and-swap. Its metadata is in its entry of the library, if it
  *     has one.
  *   - [[Example]] is temporary. It goes to no library and to no file.
  *
  * §2 also gives the invariant that Phase 2 must keep: the route, the
  * persistence owner, the event target and the save destination all name the
  * SAME target.
  */
enum ViewTarget derives CanEqual:

  case Diagram(id: DiagramId)

  /** A file, named by its session (§5).
    *
    * The session id, and not the path and revision that §2 sketches. A revision
    * advances on every save, and a target that carried a copy of it would go
    * stale the moment the file is written. `DesktopDocumentRegistry` holds the
    * path and the current revision, and this id reads them.
    */
  case File(session: DocumentSessionId)

  /** A built-in example. `name` is the name that the person clicked. It
    * becomes the title, because an example has no stored name.
    */
  case Example(slug: String, name: String)

object ViewTarget:

  def diagram(id: String): ViewTarget = Diagram(DiagramId(id))
