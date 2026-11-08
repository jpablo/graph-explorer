package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, ViewerSettings}
import upickle.default.*

case class DiagramSummary(
    id:           DiagramId,
    name:         String,
    lastModified: Long,
    createdAt:    Long = System.currentTimeMillis()
) derives ReadWriter

/** The list of the diagrams in the library.
  *
  * The JSON key of `diagrams` is `projects`. The browser library keeps the
  * index in `localStorage` with that key, and a different key loses the
  * library of a person.
  */
case class LibraryIndex(@upickle.implicits.key("projects") diagrams: List[DiagramSummary] = Nil) derives ReadWriter

/** The facts that a library card shows, from the payload of a diagram. */
case class DiagramCardInfo(format: DiagramFormat, displayName: String, diagramKind: Option[String])

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

  private lazy val indexStorage =
    storedString(IndexKey, write(LibraryIndex()))

  private lazy val settingsStorage =
    storedString(SettingsKey, write(ViewerSettings.empty))

  // ONE permanent observation per storage instance: observing the signal is what keeps
  // laminext's localStorage sync alive, and `.observe.now()` per call allocated a
  // permanent subscription each time (updateIndex runs per keystroke, so these
  // accumulated by the thousands). Read via these handles instead.
  private lazy val indexStorageNow    = indexStorage.signal.observe
  private lazy val settingsStorageNow = settingsStorage.signal.observe

  // laminext's storedString namespaces its keys with a "[StoredString]" prefix
  // (PersistenceSpec pins this); every raw localStorage access must go through rawKey
  // so the key format cannot drift between read, remove and scan sites.
  private def rawKey(key: String): String = s"[StoredString]$key"

  // One-shot read for probe-only lookups (no writes on these keys from here).
  private def readLocalStorage(key: String, default: => String): String =
    Option(dom.window.localStorage.getItem(rawKey(key))).getOrElse(default)

  /** Physically write a key, for the same reason [[removeLocalStorage]] exists:
    * laminext only syncs an OBSERVED storedString, so a throwaway instance
    * created just to write would store nothing at all.
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
    * The `Var` starts with the value in `localStorage`. Each change to the
    * `Var` goes to `localStorage`. Each change also writes the time and the
    * name into the entry of the diagram in the library index.
    *
    * @param id
    *   the id of the diagram.
    * @param initialSource
    *   the source to use when `localStorage` has no state for the diagram.
    * @return
    *   a `Var` that holds the `DiagramSnapshot` and saves each change.
    */
  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[DiagramSnapshot] =
    val initialState   = DiagramSnapshot.minimal(initialSource)
    val diagramStorage = storedString(diagramKey(id), initial = write(initialState))
    // One observation for the storage of each diagram. It starts the
    // `localStorage` sync, and the reads below use it.
    val diagramStorageNow = diagramStorage.signal.observe
    // Initialize storage ~> DiagramSnapshot
    val diagramSnapshot: Var[DiagramSnapshot] =
      try
        Var(read[DiagramSnapshot](diagramStorageNow.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e, defaulting to initial state")
          Var(initialState)

    // The stored name for the guard below, parsed only when needed: this sync
    // runs per keystroke, and only a blank incoming name (the accident path)
    // has to look at the old payload.
    def storedPayloadName: String =
      try read[DiagramSnapshot](diagramStorageNow.now()).metadata.name
      catch case _: Throwable => ""

    // synchronize DiagramSnapshot ~> storage
    diagramSnapshot.signal.distinct.changes.foreach: state =>
      val guarded = state.modify(_.metadata.name).setTo(guardedDiagramName(state.metadata.name, storedPayloadName))
      // Write the payload of the diagram.
      diagramStorage.set(write(guarded))
      // Write the time and the name into the entry in the library index.
      updateIndex: current =>
        current.modify(_.diagrams.eachWhere(_.id == id))
          .using: entry =>
            entry.copy(
              lastModified = System.currentTimeMillis(),
              name = guardedDiagramName(guarded.metadata.name, entry.name)
            )
    diagramSnapshot

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
        Var(read[ViewerSettings](settingsStorageNow.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading viewer settings: $e")
          Var(ViewerSettings.empty)
    // synchronize ViewerSettings Var ~> storage
    viewerSettings.signal.distinct.changes.foreach: settings =>
      settingsStorage.set(write(settings))
    viewerSettings

  /** One-shot read of the persisted viewer settings — no new storage
    * subscription. For call sites that need current values at mount (the
    * library navbar's theme select) without allocating another synced Var:
    * pages persist through their own Var, so a long-lived Var from elsewhere
    * goes stale, and writing other settings through a stale copy would clobber
    * them.
    */
  def readViewerSettings(): ViewerSettings =
    try read[ViewerSettings](settingsStorageNow.now())
    catch
      case e: Throwable =>
        dom.console.error(s"Error reading viewer settings: $e")
        ViewerSettings.empty

  def createEmptyDiagram(name: String): DiagramId =
    val now     = System.currentTimeMillis()
    val summary = DiagramSummary(DiagramId.random, name, lastModified = now, createdAt = now)
    updateIndex(_.modify(_.diagrams).using(summary :: _))
    summary.id

  /** Make a new diagram, and write its name and its source into the payload
    * immediately.
    *
    * The usual create path puts the source in the route, and it gives the
    * name `defaultDiagramName`. That path cannot set a different name. On the
    * first change, the payload sync writes `name = state.metadata.name` into the
    * index entry, so it replaces a name that only the entry has. This method
    * writes the payload first, so the payload and the entry have the same name
    * from the start. The diagram page then restores the payload, because
    * `createDiagramPersistence` uses its `initialState` only when no payload
    * is in storage.
    */
  def createDiagram(name: String, source: String): DiagramId =
    val id = createEmptyDiagram(name)
    writeLocalStorage(
      diagramKey(id),
      write(DiagramSnapshot.minimal(Some(source)).modify(_.metadata.name).setTo(name))
    )
    id

  def deleteDiagram(id: DiagramId): Unit =
    removeLocalStorage(diagramKey(id)) // Remove the payload of the diagram.
    updateIndex: current =>
      current.copy(diagrams = current.diagrams.filterNot(_.id == id))

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

  private def updateIndex(f: LibraryIndex => LibraryIndex): Unit =
    val currentIndex = read[LibraryIndex](indexStorageNow.now())
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
    else
      indexStorage.set(write(updated))

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
    read[LibraryIndex](indexStorageNow.now())

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
      val name    = state.metadata.name
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
