package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.models.DiagramId

/** What a viewer shows (§2 of docs/desktop-open-targets-and-persistence.md).
  *
  * A diagram and a loose file have different owners and different rules for
  * storage. Before, `ViewerState(id)` took one id for the two. Thus a loose
  * file had to use a `DiagramId` that named no diagram. §6 removes that:
  * [[DiagramPersistence.forTarget]] matches on this enum, so the owner of a
  * view decides how the app saves it.
  *
  * §2 gives the rules for the owners:
  *
  *   - The diagram in the library is the authority for [[Diagram]]. Its
  *     binding and its sync mode control the origin file, if it has one.
  *   - The file is the authority for [[LooseFile]]. It has no diagram in the
  *     library, and a save writes the file with a compare-and-swap.
  *   - [[Example]] is temporary. It goes to no library and to no file.
  *
  * §2 also gives the invariant that Phase 2 must keep: the route, the
  * persistence owner, the event target and the save destination all name the
  * SAME target.
  */
enum ViewTarget derives CanEqual:

  case Diagram(id: DiagramId)

  /** A loose file, named by its session (§5).
    *
    * The session id, and not the path and revision that §2 sketches. A revision
    * advances on every save, and a target that carried a copy of it would go
    * stale the moment the file is written. `DesktopDocumentRegistry` holds the
    * path and the current revision, and this id reads them.
    */
  case LooseFile(session: DocumentSessionId)

  /** A built-in example. `name` is the name that the person clicked. It
    * becomes the title, because an example has no stored name.
    */
  case Example(slug: String, name: String)

object ViewTarget:

  def diagram(id: String): ViewTarget = Diagram(DiagramId(id))
