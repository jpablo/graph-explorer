package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.gxcore.model.{Binding, ContentHash}
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId}

/** A library record that names an origin file (§8).
  *
  * The three things reconciliation needs, and nothing else: which record, the
  * text to compare, and the binding that says what may move and what both sides
  * last agreed on. A whole `Diagram` would carry metadata that reconciliation
  * must never read — §5.3.1 keeps metadata out of the text comparison on
  * purpose.
  */
final case class BoundRecord(id: ProjectId, text: String, binding: Binding) derives CanEqual

/** Where the library lives (D7.3).
  *
  * Two implementations, for a reason that is not a preference: a browser has no
  * disk, and the desktop has to share one with `gx`. On the web the library is
  * `localStorage` and always will be; in the desktop the on-disk store IS the
  * live state, so a record written by `gx import` with no window open is
  * already in the library the UI reads.
  *
  * The surface is deliberately the one `ProjectStorage` already had, so call
  * sites do not learn a second vocabulary — and so the localStorage path keeps
  * running exactly the code it ran before, rather than a reimplementation of it
  * that could drift. `ProjectStorage` satisfies this as it stands.
  *
  * Every read is SYNCHRONOUS. The desktop's disk access is not, so it keeps an
  * in-memory mirror; making the whole API async instead would have touched all
  * 19 call sites to buy nothing the mirror does not already give.
  */
trait DiagramLibrary:

  def directory: Signal[ProjectsDirectory]

  def directoryNow(): ProjectsDirectory

  def createProjectPersistence(id: ProjectId, initialSource: Option[String]): Var[PersistedDiagramState]

  def createProjectDirectoryEntry(name: String): ProjectId

  def createNamedProject(name: String, source: String): ProjectId

  def deleteProject(id: ProjectId): Unit

  def getProjectContent(id: ProjectId): Signal[String]

  def projectCardInfo(id: ProjectId, languages: DiagramLanguages): Option[ProjectCardInfo]

  def projectExists(id: ProjectId): Boolean

  def findProjectByExactSource(dot: String): Option[ProjectId]

  /** The records bound to a file path (§8).
    *
    * A LIST, not an Option. One file may legitimately back several records with
    * different metadata — [[org.jpablo.graphexplorer.gxcore.model.OriginUri]]
    * gives that as a reason the origin is an indexed attribute rather than the
    * primary key. An origin change reaches every record that claims it.
    *
    * `localStorage` answers Nil, and that is an answer rather than a gap: a
    * browser library has no origin to bind to, because it has no filesystem.
    */
  def recordsBoundTo(path: String): List[BoundRecord] = Nil

  /** Store what reconciliation decided (§8).
    *
    * @param text
    *   the origin's text, when a pull adopted it. None when only the agreed
    *   baseline moved, which is the `Converged` case: the content already
    *   matches, so rewriting it would be a change with no difference.
    * @param base
    *   the hash both sides now agree on.
    */
  def recordReconciled(id: ProjectId, text: Option[String], base: ContentHash): Unit = ()

  /** Push anything still held by a debounce.
    *
    * `localStorage` writes on the spot and has nothing to flush; the desktop
    * batches, because a file write per keystroke means rewriting the whole
    * diagram through an IPC hop. That batching is also a way to lose the last
    * edits, so quitting or losing focus has to be able to force it.
    */
  def flush(): Unit = ()
