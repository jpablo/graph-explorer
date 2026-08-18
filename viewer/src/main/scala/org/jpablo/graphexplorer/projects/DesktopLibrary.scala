package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.gxcore.store.DiagramFileName
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.control.NonFatal

/** The desktop library: the on-disk store, mirrored in memory (D7.3).
  *
  * The store IS the live state, so `gx import` with no window open puts a
  * diagram in the library the UI reads — no message sent, no second copy. What
  * makes that workable from a webview is the mirror: disk access is async and
  * the whole storage surface is synchronous, so reads answer from memory and
  * writes go out behind them.
  *
  * The mirror is refreshed by `ge:library.changed`, which the shell emits when
  * the directory moves for a reason the page did not cause.
  */
final class DesktopLibrary(seed: Vector[Diagram]) extends DiagramLibrary:
  import DesktopLibrary.*

  given owner: Owner = unsafeWindowOwner

  /** Scala 3 strict equality: comparing page state to page state is meaningful
    * here (has the record actually moved?), and nothing else should be
    * comparable to it by accident.
    */
  private given CanEqual[PersistedDiagramState, PersistedDiagramState] = CanEqual.derived

  private val records: Var[Map[String, Diagram]] =
    Var(seed.map(d => d.id.value -> d).toMap)

  /** Page state still waiting to be written, by diagram id.
    *
    * Its presence is what distinguishes "the record changed and the page should
    * follow" from "the record changed while the user was typing", which are
    * different situations with different right answers.
    */
  private var pending: Map[String, PersistedDiagramState] = Map.empty

  /** The text each open project was seeded with, so an external edit to the
    * SAME field can be told from an external edit to a different one.
    */
  private var seededText: Map[String, String] = Map.empty

  dom.window.addEventListener(
    LibraryChangedEvent,
    ((_: dom.Event) => { refresh(); () }): js.Function1[dom.Event, Unit]
  )

  // ------------------------------------------------------------- reading

  def directory: Signal[ProjectsDirectory] = records.signal.map(toDirectory)

  def directoryNow(): ProjectsDirectory = toDirectory(records.now())

  private def toDirectory(byId: Map[String, Diagram]): ProjectsDirectory =
    ProjectsDirectory(
      byId.values.toList
        .map: d =>
          ProjectInfo(ProjectId(d.id.value), d.name, lastModified = d.updatedAt, createdAt = d.createdAt)
        // Newest first, matching what the localStorage directory accumulates by
        // prepending: the library must not reorder itself when the backend changes.
        .sortBy(-_.lastModified)
    )

  def projectExists(id: ProjectId): Boolean = records.now().contains(id.value)

  def getProjectContent(id: ProjectId): Signal[String] =
    records.signal.map(_.get(id.value).map(_.text).getOrElse(PersistedDiagramState.minimalGraphText))

  def findProjectByExactSource(dot: String): Option[ProjectId] =
    records.now().values.collectFirst { case d if d.text == dot => ProjectId(d.id.value) }

  def projectCardInfo(id: ProjectId, languages: DiagramLanguages): Option[ProjectCardInfo] =
    records.now().get(id.value).map: d =>
      val format = d.format
        .pipeOpt(f => scala.util.Try(DiagramFormat.valueOf(f)).toOption)
        .getOrElse(DiagramFormat.detect(d.text))
      val backend = languages.forFormat(format)
      val displayName =
        if d.name.trim.nonEmpty && d.name != PersistedDiagramState.defaultProjectName then d.name
        else backend.extractTitle(d.text).getOrElse(d.name)
      ProjectCardInfo(format, displayName, backend.diagramKind(d.text))

  extension (s: String) private def pipeOpt[A](f: String => Option[A]): Option[A] = f(s)

  // ------------------------------------------------------------- writing

  def createProjectDirectoryEntry(name: String): ProjectId =
    val id  = ProjectId.random
    val now = js.Date.now().toLong
    val d = Diagram(
      id = DiagramId(id.value),
      name = name,
      folder = FolderPath.root,
      format = "",
      text = PersistedDiagramState.minimalGraphText,
      binding = None,
      metadata = DiagramMetadata.empty,
      createdAt = now,
      updatedAt = now
    )
    put(d)
    id

  def createNamedProject(name: String, source: String): ProjectId =
    val id = createProjectDirectoryEntry(name)
    records.now().get(id.value).foreach(d => put(d.copy(text = source, updatedAt = js.Date.now().toLong)))
    id

  def deleteProject(id: ProjectId): Unit =
    records.update(_ - id.value)
    pending -= id.value
    seededText -= id.value
    DesktopIpc
      .invoke("library_delete", js.Dynamic.literal(name = fileNameFor(id)))
      .failed
      .foreach(e => dom.console.error(s"could not delete ${id.value}: ${e.getMessage}"))

  def createProjectPersistence(id: ProjectId, initialSource: Option[String]): Var[PersistedDiagramState] =
    val existing = records.now().get(id.value)
    val (initial, unparsed) = existing match
      case Some(d) => LibraryMapping.toPersisted(d)
      case None    => (PersistedDiagramState.minimal(initialSource), LibraryMapping.Unparsed.none)

    seededText += id.value -> initial.source
    val state = Var(initial)

    state.signal.distinct.changes.foreach: next =>
      pending += id.value -> next
      schedule(id, unparsed)

    // An external change while this project is open. With nothing pending the
    // page should simply follow the record — that IS D7.3, and it is how a
    // headless `gx run hide` shows up without a reload.
    records.signal.changes.foreach: byId =>
      byId.get(id.value).foreach: latest =>
        if !pending.contains(id.value) then
          val (fresh, _) = LibraryMapping.toPersisted(latest)
          if fresh != state.now() then
            seededText += id.value -> fresh.source
            state.set(fresh)
        else if latest.text != seededText.getOrElse(id.value, latest.text) then
          // Both sides changed the text. The user is looking at theirs, so it
          // wins — but silently discarding a `gx set` would be the kind of
          // loss that is only noticed much later.
          dom.console.warn(
            s"[library] ${id.value} was edited on disk while you were typing; " +
              "the on-screen version was kept."
          )
          seededText += id.value -> latest.text

    state

  /** Write, merged onto the record as it stands NOW rather than as it stood
    * when the page opened it.
    *
    * That merge is the compare-and-swap: `LibraryMapping.toDiagram` carries
    * tags, notes, folder and binding across from `previous`, so re-reading
    * `previous` at write time means a `gx run tag` landing mid-edit survives
    * the next keystroke instead of being overwritten by a stale copy.
    */
  private def writeNow(id: ProjectId, state: PersistedDiagramState, unparsed: LibraryMapping.Unparsed): Unit =
    val latest  = records.now().get(id.value)
    val diagram = LibraryMapping.toDiagram(DiagramId(id.value), state, latest, unparsed, js.Date.now().toLong)
    put(diagram)

  private def put(d: Diagram): Unit =
    records.update(_.updated(d.id.value, d))
    val json = upickle.default.write(d, indent = 2)
    DesktopIpc
      .invoke("library_write", js.Dynamic.literal(name = s"${DiagramFileName.of(d.id)}.json", json = json))
      .failed
      .foreach(e => dom.console.error(s"could not save ${d.id.value}: ${e.getMessage}"))

  private def fileNameFor(id: ProjectId): String = s"${DiagramFileName.of(id.value)}.json"

  // ------------------------------------------------------------- pacing

  private var timers: Map[String, Int] = Map.empty

  /** A file write per keystroke would rewrite the whole diagram through an IPC
    * hop; `localStorage` never had to care. The cost of batching is a window in
    * which edits exist only in memory, which is what [[flush]] closes.
    */
  private def schedule(id: ProjectId, unparsed: LibraryMapping.Unparsed): Unit =
    timers.get(id.value).foreach(dom.window.clearTimeout)
    val handle = dom.window.setTimeout(
      () =>
        timers -= id.value
        pending.get(id.value).foreach: state =>
          pending -= id.value
          writeNow(id, state, unparsed)
      ,
      WriteDebounceMs
    )
    timers += id.value -> handle

  override def flush(): Unit =
    val outstanding = pending
    pending = Map.empty
    timers.values.foreach(dom.window.clearTimeout)
    timers = Map.empty
    outstanding.foreach: (idValue, state) =>
      writeNow(ProjectId(idValue), state, LibraryMapping.Unparsed.none)

  // ------------------------------------------------------------ refresh

  /** Re-read the whole library.
    *
    * Returns its Future so a test can assert V-15 by awaiting it rather than
    * sleeping and hoping — the same reason the shell's watcher decision was
    * pulled out of its thread.
    */
  private[projects] def refresh(): Future[Unit] =
    DesktopLibrary.load().map: loaded =>
      records.set(loaded.map(d => d.id.value -> d).toMap)

object DesktopLibrary:

  private val LibraryChangedEvent = "ge:library.changed"

  /** Long enough that a burst of typing is one write, short enough that the
    * window a crash could lose stays small.
    */
  private val WriteDebounceMs = 400.0

  /** Read every record the shell can see.
    *
    * A record that fails to parse is REPORTED and skipped rather than taking
    * the whole library down with it: one bad file must not make the app look
    * empty, which is the failure `LibraryStore.unreadable()` guards on the JVM.
    */
  def load(): Future[Vector[Diagram]] =
    if !DesktopIpc.available then Future.successful(Vector.empty)
    else
      DesktopIpc
        .invoke("library_list", js.Dynamic.literal())
        .map: raw =>
          val entries = raw.asInstanceOf[js.Array[js.Dynamic]]
          entries.toVector.flatMap: entry =>
            val name = entry.selectDynamic("name").asInstanceOf[String]
            val json = entry.selectDynamic("json")
            if js.isUndefined(json) || json == null then
              dom.console.warn(s"[library] $name could not be read")
              None
            else
              try Some(upickle.default.read[Diagram](json.asInstanceOf[String]))
              catch
                case NonFatal(e) =>
                  dom.console.warn(s"[library] $name is not a readable record: ${e.getMessage}")
                  None
        .recover:
          case NonFatal(e) =>
            dom.console.error(s"[library] could not be listed: ${e.getMessage}")
            Vector.empty
