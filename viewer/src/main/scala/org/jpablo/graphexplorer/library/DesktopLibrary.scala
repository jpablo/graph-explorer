package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.gxcore.store.DiagramFileName
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState
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

  /** Scala 3 strict equality. Here a comparison of two page states is
    * correct, because it tells if the diagram changed. No other type must
    * compare with a page state by accident.
    */
  private given CanEqual[PersistedDiagramState, PersistedDiagramState] = CanEqual.derived

  private val diagrams: Var[Map[String, Diagram]] =
    Var(seed.map(d => d.id.value -> d).toMap)

  /** Page state still waiting to be written, by diagram id.
    *
    * An entry here separates two cases. In one case, the diagram changed and
    * the page must follow it. In the other case, the diagram changed while
    * the person typed. The two cases have different correct answers.
    */
  private var pending: Map[String, PersistedDiagramState] = Map.empty

  /** The text that each open diagram had at the start. With it, the library
    * can tell an external edit to the SAME field from an external edit to a
    * different field.
    */
  private var seededText: Map[String, String] = Map.empty

  dom.window.addEventListener(
    LibraryChangedEvent,
    ((_: dom.Event) => { refresh(); () }): js.Function1[dom.Event, Unit]
  )

  // ------------------------------------------------------------- reading

  def index: Signal[LibraryIndex] = diagrams.signal.map(toIndex)

  def indexNow(): LibraryIndex = toIndex(diagrams.now())

  private def indexByOrigin(byId: Map[String, Diagram]): Map[String, List[BoundDiagram]] =
    byId.values.toList
      .flatMap: d =>
        for
          binding <- d.binding
          path    <- binding.origin.filePath
        yield pathKey(path) -> BoundDiagram(d.id, d.text, binding)
      .groupMap(_._1)(_._2)

  /** The one form of a path that the two sides of the comparison use.
    *
    * The two sides come by different routes. The shell gives a canonical path
    * as the platform writes it: `C:\Users\x\a.dot` on Windows. A binding stores
    * a URI. `filePath` decodes the URI to `C:/Users/x/a.dot`, because
    * `fromCanonicalPath` writes URI separators. Thus one file has two
    * forms, and a byte comparison does not find the match.
    *
    * That miss is SILENT, and it costs much. The file looks unbound, so it
    * opens as a loose file next to the diagram that already has it. The two
    * then disagree about the same file.
    *
    * Only the separator is different. The case is already the same: the two
    * sides use the form that the file system uses, which `canonicalization.json`
    * pins for the two languages. The two sides also remove the Windows `\\?\`
    * verbatim prefix.
    */
  private def pathKey(path: String): String = path.replace('\\', '/')

  private def toIndex(byId: Map[String, Diagram]): LibraryIndex =
    LibraryIndex(
      byId.values.toList
        .map: d =>
          DiagramSummary(d.id, d.metadata.name, lastModified = d.updatedAt, createdAt = d.createdAt)
        // Newest first, matching what the localStorage directory accumulates by
        // prepending: the library must not reorder itself when the backend changes.
        .sortBy(-_.lastModified)
    )

  def diagramExists(id: DiagramId): Boolean = diagrams.now().contains(id.value)

  /** The diagrams, by the file that is their origin (§8, Phase 3 item 1).
    *
    * This is a real index, so an event does not scan the library. The index
    * is DERIVED, and the code does not keep it by hand. It changes when
    * `diagrams` changes, so it always agrees with the diagrams. A second map
    * that the code keeps by hand is faster to update, but it can become stale.
    *
    * The key is the PATH, because a path is what the event gives. The shell
    * gives a canonical path in a document event. A binding stores a canonical
    * URI, and `filePath` decodes it to a path. A diagram whose origin is not a
    * file (an `https:` origin) has no key here, and no event reaches it this
    * way. That is correct, because nothing on this computer can change it.
    */
  private val boundByPath: StrictSignal[Map[String, List[BoundDiagram]]] =
    diagrams.signal.map(indexByOrigin).observe

  /** One bus for each diagram, so a view gets only the writes that it did
    * not make.
    *
    * Only the watcher in `createDiagramPersistence` sends events on these
    * buses. That is the full guarantee: an event here comes from the library,
    * and never from the view that listens.
    */
  private val externalBuses = scala.collection.mutable.Map.empty[String, EventBus[PersistedDiagramState]]

  private def externalBus(id: String): EventBus[PersistedDiagramState] =
    externalBuses.getOrElseUpdate(id, EventBus[PersistedDiagramState]())

  override def diagramChangedExternally(id: DiagramId): EventStream[PersistedDiagramState] =
    externalBus(id.value).events

  override def diagramsBoundTo(path: String): List[BoundDiagram] =
    boundByPath.now().getOrElse(pathKey(path), Nil)

  /** The binding of the diagram, read directly and not through the index.
    *
    * The method does NOT use [[boundByPath]]. The keys of that index are
    * [[pathKey]] forms: they are for the COMPARISON of two paths, and the
    * shell must not get them. `filePath` gives the path as the binding stores
    * it, and the shell must get that path.
    *
    * On Windows, that path is `C:/Users/x/a.dot`, with URI separators, because
    * `fromCanonicalPath` writes them. The shell normalizes the path with
    * `Paths.get`, which accepts the two separators, so the path finds the
    * correct file. But §7 of HANDOFF.md keeps this on the list to test on a
    * real Windows computer. The last bug of this shape was silent.
    */
  override def originPathOf(id: DiagramId): Option[String] =
    diagrams.now().get(id.value).flatMap(_.binding).flatMap(_.origin.filePath)

  def diagramSource(id: DiagramId): Signal[String] =
    diagrams.signal.map(_.get(id.value).map(_.text).getOrElse(PersistedDiagramState.minimalGraphText))

  def findDiagramBySource(dot: String): Option[DiagramId] =
    diagrams.now().values.collectFirst { case d if d.text == dot => d.id }

  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo] =
    diagrams.now().get(id.value).map: d =>
      val format = d.format
        .pipeOpt(f => scala.util.Try(DiagramFormat.valueOf(f)).toOption)
        .getOrElse(DiagramFormat.detect(d.text))
      val backend = languages.forFormat(format)
      val name = d.metadata.name
      val displayName =
        if name.trim.nonEmpty && name != PersistedDiagramState.defaultProjectName then name
        else backend.extractTitle(d.text).getOrElse(name)
      DiagramCardInfo(format, displayName, backend.diagramKind(d.text))

  extension (s: String) private def pipeOpt[A](f: String => Option[A]): Option[A] = f(s)

  // ------------------------------------------------------------- writing

  def createEmptyDiagram(name: String): DiagramId =
    val id  = DiagramId.random
    val now = js.Date.now().toLong
    val d = Diagram(
      id = id,
      format = "",
      text = PersistedDiagramState.minimalGraphText,
      binding = None,
      metadata = DiagramMetadata(name = name, folder = FolderPath.root),
      createdAt = now,
      updatedAt = now
    )
    put(d)
    id

  def createDiagram(name: String, source: String): DiagramId =
    val id = createEmptyDiagram(name)
    diagrams.now().get(id.value).foreach(d => put(d.copy(text = source, updatedAt = js.Date.now().toLong)))
    id

  def deleteDiagram(id: DiagramId): Unit =
    diagrams.update(_ - id.value)
    pending -= id.value
    seededText -= id.value
    DesktopIpc
      .invoke("library_delete", js.Dynamic.literal(name = fileNameFor(id)))
      .failed
      .foreach(e => dom.console.error(s"could not delete ${id.value}: ${e.getMessage}"))

  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[PersistedDiagramState] =
    val existing = diagrams.now().get(id.value)
    val (initial, unparsed) = existing match
      case Some(d) => LibraryMapping.toPersisted(d)
      case None    => (PersistedDiagramState.minimal(initialSource), LibraryMapping.Unparsed.none)

    seededText += id.value -> initial.source
    val state = Var(initial)

    state.signal.distinct.changes.foreach: next =>
      pending += id.value -> next
      schedule(id, unparsed)

    // An external change while this diagram is open. When no write waits,
    // the page follows the diagram. That IS D7.3, and that is how a
    // headless `gx run hide` shows on the screen without a reload.
    diagrams.signal.changes.foreach: byId =>
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
    * The write goes through `put`, so an open viewer follows it.
    * `createDiagramPersistence` watches `diagrams`, and it sets its `Var` when
    * the person does not edit. When the person edits, it warns and keeps the
    * edit. Thus a raw origin event must never go to `ViewerState` directly
    * (item 6). A change must go to the diagram, and all other parts follow
    * from there.
    *
    * CAUTION: this comment said that the viewer followed long before it did.
    * The `Var` write is only one half. For a long time, nothing used the other
    * half: the viewer read the store ONCE at mount, so a pull went to the store
    * and did not show on the screen. `Persistence.followDiagram` uses it now.
    * A write to this `Var` is not a proof that a person saw it.
    *
    * `updatedAt` changes only when the TEXT changes. A pull changes the source.
    * A move of a stale baseline does not change the source, and the library
    * sorts by that field. Thus a `Converged` diagram must not go to the top of
    * the list for a change that nobody made.
    */
  override def storeReconciliation(id: DiagramId, text: Option[String], base: ContentHash): Unit =
    for
      d       <- diagrams.now().get(id.value)
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

  /** Write the state, merged onto the diagram as it is NOW, and not as it was
    * when the page opened it.
    *
    * That merge is the compare-and-swap. `LibraryMapping.toDiagram` copies the
    * tags, the notes, the folder and the binding from `previous`. The method
    * reads `previous` again at the time of the write. Thus a `gx run tag` that
    * arrives during an edit stays after the next keystroke, and a stale copy
    * does not overwrite it.
    */
  private def writeNow(id: DiagramId, state: PersistedDiagramState, unparsed: LibraryMapping.Unparsed): Unit =
    val latest  = diagrams.now().get(id.value)
    val diagram = LibraryMapping.toDiagram(id, state, latest, unparsed, js.Date.now().toLong)

    // A visit is not an edit. When `LibraryMapping` gives the diagram that the
    // library already holds, only the clock is different. A write of it moves
    // `updatedAt`, and the library sorts by that field. Thus the diagrams move
    // in the list only because a person looked at one.
    //
    // This is a guard, not a fix for a live bug. `signal.distinct` already
    // stops a state that did not change. The guard catches the case that
    // `distinct` cannot see: a different page state that maps to the same
    // diagram. (A really new field DOES write, one time. When a person opens
    // a diagram from before `autoDetectFormat`, the field becomes `true` and
    // the library saves it. That is the default of the viewer, it is older
    // than D7.3, and it occurs one time for each diagram.)
    val unchanged = latest.exists(current => diagram.copy(updatedAt = current.updatedAt) == current)
    if !unchanged then put(diagram)

  private def put(d: Diagram): Unit =
    diagrams.update(_.updated(d.id.value, d))
    val json = upickle.default.write(d, indent = 2)
    DesktopIpc
      .invoke("library_write", js.Dynamic.literal(name = s"${DiagramFileName.of(d.id)}.json", json = json))
      .failed
      .foreach(e => dom.console.error(s"could not save ${d.id.value}: ${e.getMessage}"))

  private def fileNameFor(id: DiagramId): String = s"${DiagramFileName.of(id)}.json"

  // ------------------------------------------------------------- pacing

  private var timers: Map[String, Int] = Map.empty

  /** A file write per keystroke would rewrite the whole diagram through an IPC
    * hop; `localStorage` never had to care. The cost of batching is a window in
    * which edits exist only in memory, which is what [[flush]] closes.
    */
  private def schedule(id: DiagramId, unparsed: LibraryMapping.Unparsed): Unit =
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
      writeNow(DiagramId(idValue), state, LibraryMapping.Unparsed.none)

  // ------------------------------------------------------------ refresh

  /** Re-read the whole library.
    *
    * Returns its Future so a test can assert V-15 by awaiting it rather than
    * sleeping and hoping — the same reason the shell's watcher decision was
    * pulled out of its thread.
    */
  private[library] def refresh(): Future[Unit] =
    DesktopLibrary.load().map: loaded =>
      diagrams.set(loaded.map(d => d.id.value -> d).toMap)

object DesktopLibrary:

  private val LibraryChangedEvent = "ge:library.changed"

  /** Long enough that a burst of typing is one write, short enough that the
    * window a crash could lose stays small.
    */
  private val WriteDebounceMs = 400.0

  /** Read each diagram that the shell can see.
    *
    * When a diagram does not parse, the method REPORTS it and skips it. One
    * bad file must not take down the whole library and make the app look
    * empty. `LibraryStore.unreadable()` guards against the same failure on
    * the JVM.
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
                  dom.console.warn(s"[library] $name is not a readable diagram: ${e.getMessage}")
                  None
        .recover:
          case NonFatal(e) =>
            dom.console.error(s"[library] could not be listed: ${e.getMessage}")
            Vector.empty
