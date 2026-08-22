package org.jpablo.graphexplorer.viewer.state

/** What a viewer shows (§2 of docs/desktop-open-targets-and-persistence.md).
  *
  * A library record and a loose file have different owners and different
  * persistence rules. `ViewerState(projectId)` forced both through one
  * identity, and a loose file therefore had to borrow a `ProjectId` that named
  * no record. §6 removes that: [[DiagramPersistence.forTarget]] matches on this
  * enum, so the owner of a diagram decides how it is saved.
  *
  * §2 gives the ownership rules:
  *
  *   - A library record is authoritative for [[LibraryDiagram]]. Its binding
  *     and its sync mode govern any origin file.
  *   - The file is authoritative for [[LooseFile]]. It has no record, and a
  *     save writes the file with a compare-and-swap.
  *   - [[Example]] is ephemeral. It reaches neither the library nor a file.
  *
  * §2 also gives the invariant that Phase 2 must keep: the route, the
  * persistence owner, the event target and the save destination all name the
  * SAME target.
  */
enum ViewTarget derives CanEqual:

  case LibraryDiagram(id: ProjectId)

  /** A loose file, named by its session (§5).
    *
    * The session id, and not the path and revision that §2 sketches. A revision
    * advances on every save, and a target that carried a copy of it would go
    * stale the moment the file is written. `DesktopDocumentRegistry` holds the
    * path and the current revision, and this id reads them.
    */
  case LooseFile(session: DocumentSessionId)

  /** A built-in example. `name` is what the reader clicked, and it becomes the
    * title, because an example has no stored project name.
    */
  case Example(slug: String, name: String)

object ViewTarget:

  def library(id: String): ViewTarget = LibraryDiagram(ProjectId(id))
