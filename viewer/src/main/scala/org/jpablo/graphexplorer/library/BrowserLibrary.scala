package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, ViewerSettings}
import upickle.default.*

import scala.collection.mutable
import scala.scalajs.js
import scala.util.{Success, Try}

/** One entry of the library index.
  *
  * @param createdAt
  *   the creation time, or 0 when it is unknown. The default must be a
  *   constant. upickle omits a field that has the value of its default, and it
  *   fills a missing field from the default. A default of "now" thus put no
  *   `createdAt` into the index, because the create writes in the millisecond
  *   of the creation. Each later read then gave the time of that read. An
  *   entry from that time has no `createdAt`, and it reads as 0.
  */
case class DiagramSummary(
    id:           DiagramId,
    name:         String,
    lastModified: Long,
    createdAt:    Long = 0L
) derives ReadWriter:

  /** The time for the "Created" date and for the sort by creation. When the
    * creation time is unknown (0), it is `lastModified`.
    */
  def creationTime: Long = if createdAt > 0 then createdAt else lastModified

/** The list of the diagrams in the library.
  *
  * The JSON key of `diagrams` is `projects`. The browser library keeps the
  * index in `localStorage` with that key, and a different key loses the
  * library of a person.
  */
case class LibraryIndex(@upickle.implicits.key("projects") diagrams: List[DiagramSummary] = Nil) derives ReadWriter

/** The facts that a library card shows, from the payload of a diagram.
  *
  * `problem` is for the desktop, where a diagram is a file: a file that is
  * missing or that cannot be read has a card that says so.
  */
case class DiagramCardInfo(
    format:      DiagramFormat,
    displayName: String,
    diagramKind: Option[String],
    problem:     Option[String] = None
)

/** The browser library: `localStorage`, and the only option on the web.
  *
  * Unchanged by D7.3 on purpose. It already satisfies [[DiagramLibrary]], so
  * the desktop backend is an addition rather than a rewrite of a path that has
  * been carrying real libraries — and whose guards below exist because it has
  * lost data before.
  */
object BrowserLibrary extends DiagramLibrary:
  given owner: Owner = unsafeWindowOwner

  private val IndexKey    = "graph-explorer.projects"
  private val SettingsKey = "graph-explorer.settings"

  private lazy val indexStorage = StoredKey(IndexKey, write(LibraryIndex()))

  private lazy val settingsStorage = StoredKey(SettingsKey, write(ViewerSettings.empty))

  /** One `localStorage` key, with its value in memory.
    *
    * The writes do not use laminext `storedString`. It calls `setItem` in an
    * Airstream `scanLeft`, and the state of that fold is a `Try`. After one
    * refused write (for example, a QuotaExceededError), the fold stays failed.
    * Then no later write occurs, also after the person frees space, and each
    * read throws. Here, the value in memory is the state, and each write goes
    * to `localStorage` in a `try`. A refused write thus changes only the copy
    * in storage, and the next write tries again.
    *
    * `now()` reads a plain field, not the `Var`. A `Var.set` in a running
    * transaction occurs later, and a read in the same transaction must see the
    * new value.
    */
  private final class StoredKey(key: String, default: => String):
    private var current: String =
      try readLocalStorage(key, default)
      catch case _: Throwable => default
    private val value = Var(current)

    val signal: Signal[String] = value.signal

    def now(): String = current

    /** Keep `next`, and write it to `localStorage`. A failure is the refusal of
      * the storage.
      */
    def set(next: String): Try[Unit] =
      current = next
      value.set(next)
      Try(writeLocalStorage(key, next))

  // laminext's storedString namespaces its keys with a "[StoredString]" prefix
  // (PersistenceSpec pins this). The stored data keeps that prefix, so every raw
  // localStorage access must go through rawKey: then the key format cannot drift
  // between read, write, remove and scan sites.
  private def rawKey(key: String): String = s"[StoredString]$key"

  // One read of a key. `default` is for a key that the storage does not have.
  private def readLocalStorage(key: String, default: => String): String =
    Option(dom.window.localStorage.getItem(rawKey(key))).getOrElse(default)

  /** Physically write a key. It throws when the storage refuses the write (for
    * example, a QuotaExceededError).
    */
  private def writeLocalStorage(key: String, value: String): Unit =
    dom.window.localStorage.setItem(rawKey(key), value)

  /** Remove a key from `localStorage`.
    *
    * Do not remove a key with `storedString(key, "").set("")`. Laminext syncs
    * only an OBSERVED storedString to `localStorage`. A new instance thus writes
    * nothing, and the payload stays after its diagram goes. Then
    * `storedDiagramPayloadsExist` sees these orphans as a live library.
    */
  private def removeLocalStorage(key: String): Unit =
    dom.window.localStorage.removeItem(rawKey(key))

  lazy val index: Signal[LibraryIndex] =
    indexStorage.signal.map(read[LibraryIndex](_))

  /** Give the stored state of one diagram as a `Var`, and keep the `Var` and
    * `localStorage` in sync.
    *
    * The `Var` starts with the payload in `localStorage`. Each change to the
    * `Var` goes to `localStorage` through [[storeDiagram]]: the payload, and
    * the time and the name in the entry of the diagram in the library index.
    *
    * @param id
    *   the id of the diagram.
    * @param initialSource
    *   the source for a diagram that has no payload in `localStorage` yet (a
    *   new diagram). A stored payload always has priority over it.
    * @return
    *   a `Var` that holds the `DiagramSnapshot` and saves each change.
    */
  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[DiagramSnapshot] =
    val initialState = DiagramSnapshot.minimal(initialSource)
    // Initialize storage ~> DiagramSnapshot
    val diagramSnapshot: Var[DiagramSnapshot] =
      storedPayload(id) match
        case None => Var(initialState)
        case Some(json) =>
          try Var(read[DiagramSnapshot](json))
          catch
            case e: Throwable =>
              dom.console.error(s"Error reading state: $e, defaulting to initial state")
              Var(initialState)
    // synchronize DiagramSnapshot ~> storage
    diagramSnapshot.signal.distinct.changes.foreach(storeDiagram(id, _))
    diagramSnapshot

  /** Write one diagram: its payload, and the time and the name in its entry in
    * the library index.
    *
    * A refused write does not stop the next write: each call tries again. The
    * refusal stays in [[refusedWrite]] until a write of the diagram succeeds,
    * and it goes to [[refusedWrites]], so that the view tells the person. When
    * the storage refuses the payload, the index entry stays as it is. Thus
    * "Last modified" does not show a change that the storage does not have.
    *
    * @return
    *   the message for the person when the storage refuses a write.
    */
  def storeDiagram(id: DiagramId, state: DiagramSnapshot): Option[String] =
    val guarded = state.copy(name = guardedDiagramName(state.name, storedPayloadName(id)))
    // Write the payload of the diagram.
    val payload = Try(writeLocalStorage(diagramKey(id), write(guarded)))
    // Write the time and the name into the entry in the library index.
    val entry =
      if payload.isFailure then payload
      else
        updateIndex: current =>
          current.modify(_.diagrams.eachWhere(_.id == id))
            .using: entry =>
              entry.copy(
                lastModified = System.currentTimeMillis(),
                name = guardedDiagramName(guarded.name, entry.name)
              )
    val refusal = entry.failed.toOption.map(refusalMessage(_, "the last change of this diagram"))
    refusal match
      case Some(message) =>
        dom.console.error(s"The browser refused a write of the diagram ${id.value}: $message")
        refusedDiagrams(id) = message
        refusedWriteBus.emit(id -> message)
      case None =>
        refusedDiagrams -= id
    refusal

  /** The message of the last write of a diagram, when the storage refused it.
    * `None` when the last write succeeded.
    */
  def refusedWrite(id: DiagramId): Option[String] = refusedDiagrams.get(id)

  /** The message for the person, each time the storage refuses a write of the
    * diagram.
    */
  def refusedWrites(id: DiagramId): EventStream[String] =
    refusedWriteBus.events.filter(_._1 == id).map(_._2)

  private val refusedDiagrams = mutable.Map.empty[DiagramId, String]
  private val refusedWriteBus = EventBus[(DiagramId, String)]()

  /** The text for the person when the storage refuses a write of `what`. */
  private def refusalMessage(error: Throwable, what: String): String =
    if isQuotaError(error) then
      s"The browser storage is full, so $what is not saved. " +
        "Delete diagrams that you do not need, and then save again."
    else s"The browser did not save $what: ${error.getMessage}"

  /** True for the error of a full storage. Firefox used a different name. */
  private def isQuotaError(error: Throwable): Boolean =
    error match
      case js.JavaScriptException(e) if js.typeOf(e) == "object" && (e.asInstanceOf[AnyRef] ne null) =>
        val name = e.asInstanceOf[js.Dynamic].name
        js.typeOf(name) == "string" &&
        (name.asInstanceOf[String] == "QuotaExceededError" || name.asInstanceOf[String] == "NS_ERROR_DOM_QUOTA_REACHED")
      case _ => false

  /** Tell the person about a refused write that no open diagram shows: the
    * notice of the home page.
    */
  private def reportRefusal(what: String)(error: Throwable): Unit =
    val message = refusalMessage(error, what)
    dom.console.error(message)
    Library.showNotice(Vector(message))

  /** The payload of a diagram, as `localStorage` has it. */
  private def storedPayload(id: DiagramId): Option[String] =
    try Option(dom.window.localStorage.getItem(rawKey(diagramKey(id))))
    catch case _: Throwable => None

  /** The stored name for the guard of [[storeDiagram]]. It is parsed only when
    * needed: the sync runs for each keystroke, and only a blank incoming name
    * (the accident path) has to look at the old payload. A diagram with no
    * payload has the name of `DiagramSnapshot.minimal`.
    */
  private def storedPayloadName(id: DiagramId): String =
    storedPayload(id) match
      case None => DiagramSnapshot.defaultDiagramName
      case Some(json) =>
        try read[DiagramSnapshot](json).name
        catch case _: Throwable => ""

  /** Retrieves the persisted viewer settings.
    *
    * This function initializes the settings from local storage. It ensures that any changes to the settings are persisted back to local
    * storage.
    *
    * @return
    *   A `Var` containing the `ViewerSettings`.
    */
  def loadViewerSettings(): Var[ViewerSettings] =
    // Initialize storage ~> ViewerSettings Var
    val viewerSettings =
      try
        Var(read[ViewerSettings](settingsStorage.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading viewer settings: $e")
          Var(ViewerSettings.empty)
    // synchronize ViewerSettings Var ~> storage
    viewerSettings.signal.distinct.changes.foreach: settings =>
      settingsStorage.set(write(settings)).failed.foreach: e =>
        dom.console.error(s"The browser refused a write of the viewer settings: $e")
    viewerSettings

  /** One-shot read of the persisted viewer settings — no new storage
    * subscription. For call sites that need current values at mount (the
    * library navbar's theme select) without allocating another synced Var:
    * pages persist through their own Var, so a long-lived Var from elsewhere
    * goes stale, and writing other settings through a stale copy would clobber
    * them.
    */
  def readViewerSettings(): ViewerSettings =
    try read[ViewerSettings](settingsStorage.now())
    catch
      case e: Throwable =>
        dom.console.error(s"Error reading viewer settings: $e")
        ViewerSettings.empty

  def createEmptyDiagram(name: String): DiagramId =
    val now     = System.currentTimeMillis()
    val summary = DiagramSummary(DiagramId.random, name, lastModified = now, createdAt = now)
    updateIndex(_.modify(_.diagrams).using(summary :: _)).failed.foreach(reportRefusal("the new diagram"))
    summary.id

  /** Make a new diagram, and write its name and its source into the payload
    * immediately.
    *
    * The usual create path puts the source in the route, and it gives the
    * name `defaultDiagramName`. That path cannot set a different name. On the
    * first change, the payload sync writes `name = state.name` into the
    * index entry, so it replaces a name that only the entry has. This method
    * writes the payload first, so the payload and the entry have the same name
    * from the start. The diagram page then restores the payload, because
    * `createDiagramPersistence` uses its `initialState` only when no payload
    * is in storage.
    *
    * A visit with no edit writes nothing. Thus a source that only the route
    * has is not stored until the first edit. A source that must stay (the
    * text of a share link) comes through this method.
    */
  def createDiagram(name: String, source: String): DiagramId =
    val id = createEmptyDiagram(name)
    Try(writeLocalStorage(diagramKey(id), write(DiagramSnapshot.minimal(Some(source)).copy(name = name))))
      .failed.foreach(reportRefusal("the new diagram"))
    id

  def deleteDiagram(id: DiagramId): Unit =
    removeLocalStorage(diagramKey(id)) // Remove the payload of the diagram.
    refusedDiagrams -= id
    updateIndex: current =>
      current.copy(diagrams = current.diagrams.filterNot(_.id == id))
    .failed.foreach(reportRefusal("the removal of the diagram"))

  /** The source of a diagram, as a Signal.
    *
    * @param id
    *   the id of the diagram.
    * @return
    *   a Signal that holds the source of the diagram.
    */
  def diagramSource(id: DiagramId): Signal[String] =
    val diagramStorage = storedString(diagramKey(id), write(DiagramSnapshot.empty))
    diagramStorage.signal.map: stateStr =>
      try
        read[DiagramSnapshot](stateStr).source
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e")
          DiagramSnapshot.minimalGraphText

  // ----------------- Private methods -----------------

  /** Change the library index, in memory and in `localStorage`.
    *
    * @return
    *   a failure when the storage refuses the write. The index in memory has
    *   the change also then, and the next write tries again.
    */
  private def updateIndex(f: LibraryIndex => LibraryIndex): Try[Unit] =
    val currentIndex = read[LibraryIndex](indexStorage.now())
    val updated      = f(currentIndex)
    // This GUARD prevents the loss of the whole index. The accident is a BAD
    // READ. `currentIndex` is empty when the library is not empty (for example,
    // a wrong storage key gives the empty default). Then `updated` is empty
    // too, and a write of it erases the index. All the payloads stay.
    //
    // Thus the condition also tests the READ, not only the write. A read that
    // gives entries is correct. When the caller then empties the index, the
    // caller deletes the last diagram, and the write must occur. An earlier
    // guard tested only `updated`, and a person could not delete the last
    // diagram. The library kept an entry that no delete could remove. When
    // `currentIndex` is empty, the skipped write changes nothing.
    if currentIndex.diagrams.isEmpty && updated.diagrams.isEmpty && storedDiagramPayloadsExist() then
      if !phantomIndexReported then
        phantomIndexReported = true
        dom.console.warn(
          "The library index read as empty while diagram payloads exist in storage. " +
            "No write was needed, but if the library looks empty in the UI this is the bug to chase. " +
            "(Reported once per session.)"
        )
      Success(())
    else
      val json = write(updated)
      // A write that changes nothing does not occur. The sync of a diagram
      // that has no index entry calls this for each change, and a full write
      // of the index each time has no use.
      if json == indexStorage.now() then Success(())
      else indexStorage.set(json)

  /** The condition above re-occurs on every state change while it holds; logging it
    * each time trains the reader to ignore it (it fired on every viewer test). */
  private var phantomIndexReported = false

  /** This GUARD prevents the loss of a name. It is the empty-index guard of
    * [[updateIndex]], for one field.
    *
    * The accident has the same shape. On 2026-08-02, a stale incremental
    * bundle crashed during render, and the viewer stayed half initialized.
    * The state sync ran while the name in the state still held "" from before
    * the restore. The sync then erased a real stored name in the payload AND in
    * the index entry ("Groups" became "").
    *
    * No user flow gives a diagram a blank name, because `RenameDiagramDialog`
    * ignores a blank name. Thus a blank name over a real name is always an
    * accident, and the stored name stays. A real name from the sync always
    * goes through. That is also the repair for a name that the accident erased. A
    * blank name over a blank name also goes through, so each name stays
    * writable.
    *
    * `stored` is by-name, because its value can need a payload parse. This
    * path runs on each keystroke, and the usual case does not need the parse.
    */
  private def guardedDiagramName(incoming: String, stored: => String): String =
    if incoming.trim.nonEmpty then incoming
    else
      val storedName = stored
      if storedName.trim.nonEmpty then storedName else incoming

  /** True when a payload key of a diagram holds real content.
    *
    * `deleteDiagram` removes the key. But libraries from before that fix still
    * hold orphan payloads, so this can be true for diagrams that the index no
    * longer lists.
    */
  private def storedDiagramPayloadsExist(): Boolean =
    val prefix = rawKey("graph-explorer.project.")
    (0 until dom.window.localStorage.length).exists { i =>
      Option(dom.window.localStorage.key(i)).exists { k =>
        k.startsWith(prefix) && Option(dom.window.localStorage.getItem(k)).exists(_.length > 2)
      }
    }

  private def diagramKey(id: DiagramId): String =
    s"graph-explorer.project.${id.value}"

  // ---- raw access, for the one-time copy onto disk (D7.3)
  //
  // The migration reads the browser library as JSON rather than as parsed
  // state: it has to cope with payloads written by older versions whose shape
  // this build no longer knows, and re-parsing them into today's types would
  // discard exactly the fields it is trying to preserve.

  private[library] def rawIndexJson: String =
    readLocalStorage(IndexKey, write(LibraryIndex()))

  private[library] def rawPayload(diagramId: String): Option[String] =
    Option(dom.window.localStorage.getItem(rawKey(s"graph-explorer.project.$diagramId")))

  private[library] def readFlag(key: String): Option[String] =
    Option(dom.window.localStorage.getItem(rawKey(key)))

  private[library] def writeFlag(key: String, value: String): Unit =
    writeLocalStorage(key, value)

  /** The library index as it is in storage now. */
  def indexNow(): LibraryIndex =
    read[LibraryIndex](indexStorage.now())

  /** The facts that the card of a diagram shows, from its payload.
    *
    * The facts are the format of the diagram (for the kind badge and the kind
    * filter) and the name to show. The name to show is the stored name. When a
    * person did not rename the diagram, the title that the diagram declares
    * replaces it. `ViewerState.displayTitle` uses the same rule on the diagram
    * page.
    *
    * The stored format tag has priority, because a person can set it. For a
    * payload from before the tag, the format comes from the source. This is
    * one raw read, so it is fast enough for each card. The slow part of the
    * library (the thumbnail render) stays lazy.
    */
  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo] =
    try
      val state = read[DiagramSnapshot](readLocalStorage(diagramKey(id), write(DiagramSnapshot.empty)))
      val format = state.format
        .flatMap(f => scala.util.Try(DiagramFormat.valueOf(f)).toOption)
        .getOrElse(DiagramFormat.detect(state.source))
      val name    = state.name
      val backend = languages.forFormat(format)
      val displayName =
        if name.trim.nonEmpty && name != DiagramSnapshot.defaultDiagramName then name
        else backend.extractTitle(state.source).getOrElse(name)
      Some(DiagramCardInfo(format, displayName, backend.diagramKind(state.source)))
    catch case _: Throwable => None

  /** True when the library index has a diagram with this id. */
  def diagramExists(id: DiagramId): Boolean =
    indexNow().diagrams.exists(_.id == id)

  /** Find a diagram whose stored source is exactly the given DOT text. */
  def findDiagramBySource(dot: String): Option[DiagramId] =
    indexNow().diagrams.collectFirst(Function.unlift { summary =>
      try
        val state = read[DiagramSnapshot](readLocalStorage(diagramKey(summary.id), write(DiagramSnapshot.empty)))
        if state.source == dot then Some(summary.id) else None
      catch
        case _: Throwable => None
    })

end BrowserLibrary
