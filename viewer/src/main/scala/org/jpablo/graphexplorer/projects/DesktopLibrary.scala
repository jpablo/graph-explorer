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

  private def indexByOrigin(byId: Map[String, Diagram]): Map[String, List[BoundRecord]] =
    byId.values.toList
      .flatMap: d =>
        for
          binding <- d.binding
          path    <- binding.origin.filePath
        yield pathKey(path) -> BoundRecord(ProjectId(d.id.value), d.text, binding)
      .groupMap(_._1)(_._2)

  /** The spelling both sides of this comparison collapse to.
    *
    * The two sides arrive by different routes. The shell reports a canonical
    * path as the platform writes it — `C:\Users\x\a.dot` on Windows. A binding
    * stores a URI, and `filePath` decodes it back to `C:/Users/x/a.dot`,
    * because `fromCanonicalPath` writes URI separators. One file, two
    * spellings, and a byte comparison misses.
    *
    * The miss is SILENT and costly: the file reads as unbound, so it opens as a
    * loose document beside the record that already owns it, and the two then
    * disagree about the same file.
    *
    * Only the separator differs. Case is already settled — both sides resolve
    * to the filesystem's own spelling, which `canonicalization.json` pins for
    * both languages — and both strip Windows' `\\?\` verbatim prefix.
    */
  private def pathKey(path: String): String = path.replace('\\', '/')

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

  /** Records by the file they are bound to (§8, Phase 3 item 1).
    *
    * A real index rather than a scan per event, and DERIVED rather than
    * maintained: it recomputes when `records` moves, so it cannot fall out of
    * step with the records it describes. A hand-kept second map would be faster
    * to update and would be the thing that goes stale.
    *
    * Keyed by the PATH, because that is what arrives. The shell reports a
    * canonical path in a document event; a binding stores a canonical URI, and
    * `filePath` decodes it back. A record whose origin is not a file — an
    * `https:` origin — has no key here and is unreachable this way, which is
    * correct: nothing on this machine can change it.
    */
  private val boundByPath: StrictSignal[Map[String, List[BoundRecord]]] =
    records.signal.map(indexByOrigin).observe

  /** One bus per record, so a view hears only writes it did not make.
    *
    * Fed from `createProjectPersistence`'s watcher and from nowhere else. That
    * is the whole guarantee: an event here came from the library, never from
    * the view that is listening.
    */
  private val externalBuses = scala.collection.mutable.Map.empty[String, EventBus[PersistedDiagramState]]

  private def externalBus(id: String): EventBus[PersistedDiagramState] =
    externalBuses.getOrElseUpdate(id, EventBus[PersistedDiagramState]())

  override def recordChangedExternally(id: ProjectId): EventStream[PersistedDiagramState] =
    externalBus(id.value).events

  override def recordsBoundTo(path: String): List[BoundRecord] =
    boundByPath.now().getOrElse(pathKey(path), Nil)

  /** The record's own binding, read directly rather than through the index.
    *
    * NOT through [[boundByPath]], whose keys are [[pathKey]] spellings — a form
    * for COMPARING two paths, not a path to hand back to the shell. `filePath`
    * gives the path as the binding stores it, which is what the shell must
    * receive.
    *
    * On Windows that is `C:/Users/x/a.dot`, with URI separators, because
    * `fromCanonicalPath` writes them. The shell normalizes through `Paths.get`,
    * which accepts either separator, so this reaches the right file — but §7 of
    * HANDOFF.md keeps it on the list to confirm on a real Windows machine. The
    * last bug of this shape was silent.
    */
  override def originPathOf(id: ProjectId): Option[String] =
    records.now().get(id.value).flatMap(_.binding).flatMap(_.origin.filePath)

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
            // The ONE place an outside write is announced. Setting the `Var`
            // above is not enough — its stream also carries this view's own
            // writes, and a consumer cannot tell them apart.
            externalBus(id.value).emit(fresh)
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

  /** Store what reconciliation decided (§8, Phase 3 item 4).
    *
    * Through `put`, so an open viewer follows: `createProjectPersistence`
    * watches `records` and sets its `Var` when the person is not mid-edit, and
    * warns instead of discarding when they are. That is why a raw origin event
    * must never reach `ViewerState` on its own (item 6) — the record is the one
    * place a change has to land, and everything else follows from there.
    *
    * CAUTION: this comment claimed the viewer followed long before it did.
    * Setting the `Var` is only half of it, and for a long time nothing consumed
    * the other half: the viewer read the store ONCE at mount, so a pull landed
    * here and stayed off the screen. `Persistence.followRecord` is the consumer.
    * Do not read a write to this `Var` as proof that anybody saw it.
    *
    * `updatedAt` moves only when the TEXT moves. A pull changes the document;
    * advancing a stale baseline does not, and the library is sorted by that
    * field — so a `Converged` record would jump to the top for a change nobody
    * made.
    */
  override def recordReconciled(id: ProjectId, text: Option[String], base: ContentHash): Unit =
    for
      d       <- records.now().get(id.value)
      binding <- d.binding
    do
      val at = js.Date.now().toLong
      put(
        d.copy(
          text = text.getOrElse(d.text),
          binding = Some(binding.copy(baseHash = base, lastSyncAt = at)),
          updatedAt = if text.isDefined then at else d.updatedAt
        )
      )

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

    // A visit is not an edit. If the mapping round-trip produces the record we
    // already hold, the only difference is the clock — and writing that would
    // bump `updatedAt`, which the library is sorted by, so diagrams would
    // reshuffle for having been looked at.
    //
    // This is a guard, not a fix for a live bug: `signal.distinct` already
    // suppresses a state that did not change. What it catches is the case
    // `distinct` cannot see — page state that differs while mapping back to an
    // identical record. (A genuinely new field DOES write, once: opening a
    // pre-`autoDetectFormat` diagram settles it to `true` and saves. That is
    // the viewer's own default, it predates D7.3, and it happens once per
    // diagram and then stops.)
    val unchanged = latest.exists(current => diagram.copy(updatedAt = current.updatedAt) == current)
    if !unchanged then put(diagram)

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
