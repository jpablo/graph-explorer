package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.gxcore.store.{MetadataMigration, SafeFileName}
import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages, SourceTitle}
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopIpc, FilePersistence}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc.{DiagramsFolder, EntryBase, FileSnapshot, IpcFailure}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, DocumentSessionId}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** What the home page knows about the file of an entry. */
enum FileState derives CanEqual:
  case Loaded(text: String, revision: String)
  case Missing
  case Unreadable(message: String)

/** What a rename of a file did. */
enum FileRename derives CanEqual:
  /** The file and its entry (if it had one) have the new name. */
  case Done(path: String)

  /** Nothing changed. `message` tells the person why. */
  case Refused(message: String)

  /** The file has the new name, but its entry did not move. */
  case MetadataNotMoved(path: String, message: String)

/** The library of the desktop app: the metadata entries of
  * `$GX_HOME/library/metadata/`, mirrored in memory (ADR 0001, plan §3).
  *
  * A diagram is a file, and the library is the set of files that have an
  * entry. `gx` writes the same directory, so `gx hide` or `gx add` with no
  * window open changes the library that the app reads. The shell sends
  * `ge:library.changed` when the directory changes for a reason that the page
  * did not cause, and the mirror reads it again.
  *
  * The key of an entry is the hex SHA-256 of the canonical `file:` URI of its
  * path ([[LibraryEntry.keySource]]). The page makes the URI with the shared
  * function and asks the shell for the hash (`hash_text`), so the key is the
  * key that `gx` computes. The id of a diagram in the list is its key.
  *
  * The source of each diagram is in its file, never here. The home page reads
  * each file for its title and its thumbnail (`read_document`, with no watch),
  * and a diagram opens through its file (`open_document`), with a document
  * session, an explicit Save and the conflict banner.
  */
final class DesktopLibrary private () extends LibraryListing:
  import DesktopLibrary.*

  /** The entries, by key. */
  private val entries: Var[Map[String, LibraryEntry]] = Var(Map.empty)

  /** The exact text of each entry in [[entries]], as the page last read it or
    * wrote it. A write of metadata gives this text to the shell as its base,
    * so that the shell can refuse the write when `gx` changed the entry after
    * the page read it (`library_write`, `LIBRARY_CONFLICT`).
    */
  private var texts: Map[String, String] = Map.empty

  /** The key of each path that the library has hashed. A key never changes,
    * so the cache never goes stale.
    */
  private val keys: Var[Map[String, String]] = Var(Map.empty)

  /** The file of each entry, as the last read found it. */
  private val files: Var[Map[String, FileState]] = Var(Map.empty)

  /** A clock of the changes that this page makes, so that a refresh that
    * started before a change cannot undo it in the mirror.
    */
  private var changeClock               = 0L
  private var lastChange: Map[String, Long] = Map.empty

  private var hashing: Map[String, Future[String]]       = Map.empty
  private var waitingWrites: Map[String, DiagramMetadata] = Map.empty

  private val onLibraryChanged: js.Function1[dom.Event, Unit] = _ => { refresh(); () }

  dom.window.addEventListener(LibraryChangedEvent, onLibraryChanged)

  /** Stop the listener of `ge:library.changed`. */
  def close(): Unit = dom.window.removeEventListener(LibraryChangedEvent, onLibraryChanged)

  // ------------------------------------------------------------- the list

  /** The list changes when an entry changes, and when a file is read: the
    * name of a diagram is the title in its source.
    */
  def index: Signal[LibraryIndex] = entries.signal.combineWith(files.signal).map(toIndex)

  def indexNow(): LibraryIndex = toIndex(entries.now(), files.now())

  private def toIndex(byKey: Map[String, LibraryEntry], fileStates: Map[String, FileState]): LibraryIndex =
    LibraryIndex(
      byKey.toList
        .map: (key, e) =>
          DiagramSummary(DiagramId(key), nameOf(e.path, fileStates.get(e.path)), lastModified = e.updatedAt, createdAt = e.createdAt)
        // Newest first, as the browser library gives its list.
        .sortBy(d => (-d.lastModified, d.id.value))
    )

  /** The name of a diagram: the title in its source, or else its file name
    * with no extension (CONTEXT.md).
    */
  private def nameOf(path: String, state: Option[FileState]): String =
    state
      .collect { case FileState.Loaded(text, _) => SourceTitle.of(DiagramFormat.detect(text), text) }
      .flatten
      .getOrElse(LibraryEntry.nameOf(path))

  def diagramExists(id: DiagramId): Boolean = entries.now().contains(id.value)

  /** The path of the file of a diagram in the list. */
  def pathOf(id: DiagramId): Option[String] = entries.now().get(id.value).map(_.path)

  /** The diagram in the list that has this file, if one has. */
  def idOfPath(path: String): Option[DiagramId] =
    keys.now().get(path).filter(entries.now().contains).map(DiagramId(_))

  def fileState(id: DiagramId): Option[FileState] =
    entries.now().get(id.value).flatMap(e => files.now().get(e.path))

  def diagramSource(id: DiagramId): Signal[String] =
    entries.signal
      .combineWith(files.signal)
      .map: (byKey, fileStates) =>
        byKey
          .get(id.value)
          .flatMap(e => fileStates.get(e.path))
          .collect { case FileState.Loaded(text, _) => text }
          .getOrElse(DiagramSnapshot.minimalGraphText)
      .distinct

  /** What a card shows. `None` while the file is not read yet. A missing file
    * shows as missing: its name is the file name, and `problem` says why.
    */
  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo] =
    entries.now().get(id.value).flatMap: e =>
      val fileName = LibraryEntry.nameOf(e.path)
      files.now().get(e.path).map:
        case FileState.Loaded(text, _) =>
          // The same rule as the viewer of the file (`FilePersistence.formatOf`).
          val format  = FilePersistence.formatOf(e.path, text).getOrElse(DiagramFormat.DOT)
          val backend = languages.forFormat(format)
          DiagramCardInfo(format, backend.extractTitle(text).getOrElse(fileName), backend.diagramKind(text))
        case FileState.Missing =>
          DiagramCardInfo(formatOfPath(e.path), fileName, None, problem = Some(s"The file is missing: ${e.path}"))
        case FileState.Unreadable(message) =>
          DiagramCardInfo(formatOfPath(e.path), fileName, None, problem = Some(s"The file cannot be read: $message"))

  /** "Remove from library": the entry goes, and the file stays. */
  def deleteDiagram(id: DiagramId): Unit =
    removeEntry(id.value)
    ()

  // ------------------------------------------------- the metadata of a file

  /** The metadata of the entry of a file, if the library has one. */
  def metadataOf(path: String): Option[DiagramMetadata] =
    keys.now().get(path).flatMap(entries.now().get).map(_.metadata)

  /** The entry of a file as it changes. It starts the hash of the path, if
    * the library does not know the key of the path yet.
    */
  def entrySignal(path: String): Signal[Option[LibraryEntry]] =
    keyOf(path).failed.foreach(e => dom.console.warn(s"[library] no key for $path: ${messageOf(e)}"))
    keys.signal
      .combineWith(entries.signal)
      .map((byPath, byKey) => byPath.get(path).flatMap(byKey.get))
      .distinct

  /** Give the metadata of the viewer of a file to its entry.
    *
    * The viewer owns the hidden elements, the collapsed groups and the
    * language mode. The entry owns the rest: the tags and the notes, which the
    * viewer has no UI for, and `createdAt`. The write merges the fields of the
    * viewer onto the entry.
    *
    * The mirror can be older than the disk: the shell polls the store, and
    * the refresh needs one more round trip. Thus the write is a
    * compare-and-swap on the text of the entry that the merge used. When `gx`
    * changed the entry in the meantime (`gx tag`, `gx hide`), the shell
    * refuses the write. The page then reads the entry again, merges onto it,
    * and tries again ([[MaxWriteTries]] times). Thus a `gx tag` that the
    * mirror does not know yet stays after the next hide in the viewer.
    *
    * Only a change of the viewer writes. `before` is the metadata that the
    * viewer had before this change. When the fields of the viewer did not
    * change (an edit of the source, a visit), the write does not occur. A
    * keystroke thus cannot put back an entry that `gx remove` removed. When
    * the viewer gives what the entry already holds (the adoption of an
    * outside change), the write does not occur either, because a write would
    * move `updatedAt`. A file with no entry gets one on the first change of
    * its metadata, and that puts it in the library, as `gx hide` does.
    */
  def writeMetadata(path: String, before: DiagramMetadata, viewer: DiagramMetadata): Unit =
    if Owned.of(before) != Owned.of(viewer) then
      keys.now().get(path) match
        case Some(key) =>
          putMetadata(key, path, viewer)
          ()
        case None =>
          // The key comes from the shell. Keep only the latest value while it
          // comes, so that a burst of changes gives one write.
          waitingWrites += path -> viewer
          keyOf(path).foreach: key =>
            waitingWrites.get(path).foreach: latest =>
              waitingWrites -= path
              putMetadata(key, path, latest)

  private def putMetadata(key: String, path: String, viewer: DiagramMetadata): Future[Unit] =
    val current = entries.now().get(key)
    val unchanged = current match
      case Some(e) => Owned.of(e.metadata) == Owned.of(viewer)
      case None    => Owned.of(viewer) == Owned.none
    if unchanged then Future.unit
    else
      mergeAndWrite(key, path, viewer, current, texts.get(key), MaxWriteTries).andThen:
        case Failure(error) =>
          dom.console.error(s"[library] could not save the metadata of $path: ${messageOf(error)}")
          refresh()

  /** Merge the fields of the viewer onto `base`, and write the result with
    * the text of `base` as the base of the compare-and-swap. On a refusal,
    * read the entry again and try again, `tries` times in all.
    */
  private def mergeAndWrite(
      key: String,
      path: String,
      viewer: DiagramMetadata,
      base: Option[LibraryEntry],
      baseText: Option[String],
      tries: Int
  ): Future[Unit] =
    val now = js.Date.now().toLong
    val merged = LibraryEntry(
      path = base.fold(path)(_.path),
      metadata = base
        .fold(DiagramMetadata.empty)(_.metadata)
        .copy(
          hiddenElements = viewer.hiddenElements,
          collapsedGroups = viewer.collapsedGroups,
          autoDetectFormat = viewer.autoDetectFormat
        ),
      createdAt = base.fold(now)(_.createdAt),
      updatedAt = now
    )
    write(key, merged, Some(baseText.fold(EntryBase.Absent)(EntryBase.Present(_)))).recoverWith:
      case IpcFailure("LIBRARY_CONFLICT", _) if tries > 1 =>
        readEntry(key).flatMap((fresh, freshText) => mergeAndWrite(key, path, viewer, fresh, freshText, tries - 1))

  /** The entry of `key` as the disk holds it now, and its exact text. */
  private def readEntry(key: String): Future[(Option[LibraryEntry], Option[String])] =
    DesktopIpc
      .libraryRead(s"$key.json")
      .map:
        case None       => (None, None)
        case Some(json) => (Some(upickle.default.read[LibraryEntry](json)), Some(json))

  // ------------------------------------------------------------- the files

  /** Open the file of a diagram in the list: watch it, and make its document
    * session. The result is the route of the session.
    */
  def open(id: DiagramId): Future[Either[String, Route]] =
    entries.now().get(id.value) match
      case None => Future.successful(Left("This diagram is not in the library any more."))
      case Some(entry) => openPath(entry.path)

  private[library] def openPath(path: String): Future[Either[String, Route]] =
    DesktopIpc
      .openFile(path)
      .flatMap: snapshot =>
        val state = FileState.Loaded(snapshot.text, snapshot.revision)
        files.update(_.updated(path, state).updated(snapshot.path, state))
        // The session has the path that the shell gives. The viewer reads and
        // writes the entry of that path, so the entry must be there first.
        moveToCanonical(path, snapshot.path).map: _ =>
          val session = DesktopDocumentRegistry.record(snapshot.path, snapshot.revision, snapshot.text)
          Right(Route.File(session.id.value))
      .recoverWith:
        case NonFatal(error) =>
          readFileState(path).map: (_, state) =>
            files.update(_.updated(path, state))
            state match
              case FileState.Missing => Left(s"The file $path is missing. Put it back, or remove it from the library.")
              case _                 => Left(s"Could not open $path: ${messageOf(error)}")

  /** Move the entry of `path` to the key of `canonical`, the canonical path
    * of the same file that the shell gave.
    *
    * An old entry can have a path that is not canonical: a file that a
    * different tool renamed with a change of case only, or a path through
    * `/tmp` on macOS (`/private/tmp`). The key of an entry is the key of its
    * path, and `gx` uses the key of the canonical path. Thus the entry moves
    * to that key, with its metadata: the new entry first, and then the old
    * one goes. Then the viewer, `gx`, and the list use one entry.
    *
    * When the key of the canonical path has an entry already, that entry
    * stays, and the old one stays too. A move must not write over metadata.
    * A failure goes to the console: the file opens all the same.
    */
  private def moveToCanonical(path: String, canonical: String): Future[Unit] =
    if path == canonical then Future.unit
    else
      keys.now().get(path).flatMap(key => entries.now().get(key).map(key -> _)) match
        case None => Future.unit
        case Some((oldKey, entry)) =>
          keyOf(canonical)
            .flatMap: newKey =>
              if newKey == oldKey || entries.now().contains(newKey) then Future.unit
              else put(newKey, entry.copy(path = canonical)).flatMap(_ => removeEntry(oldKey))
            .recover:
              case NonFatal(e) =>
                dom.console.warn(s"[library] the entry of $path did not move to $canonical: ${messageOf(e)}")

  /** "New diagram": a NEW file in the folder for new diagrams, with an entry,
    * open on the screen (ADR 0001).
    *
    * The name is `<name><extension>`, and then `<name> 2<extension>`, and so
    * on: the shell refuses a name that exists (`create_document`), and the
    * next number is tried.
    */
  def newDiagram(name: String, text: String, extension: String): Future[Either[String, Route]] =
    val created =
      for
        folder <- DesktopIpc.diagramsFolder()
        file   <- createUnique(folder, SafeFileName.of(name), extension, text)
      yield file
    created.transformWith:
      case Failure(e) => Future.successful(Left(s"Could not make a new diagram: ${messageOf(e)}"))
      case Success(file) =>
        val now = js.Date.now().toLong
        keyOf(file.path)
          .flatMap(key => put(key, LibraryEntry(file.path, DiagramMetadata.empty, now, now)))
          .transformWith:
            case Failure(e) =>
              // The file exists, so the person must hear where it is.
              Future.successful(Left(s"The new file ${file.path} exists, but it could not join the library (${messageOf(e)})."))
            case Success(_) =>
              files.update(_.updated(file.path, FileState.Loaded(file.text, file.revision)))
              openPath(file.path)

  private def createUnique(folder: DiagramsFolder, base: String, extension: String, text: String): Future[FileSnapshot] =
    val taken = folder.existing.map(_.toLowerCase).toSet
    def attempt(n: Int): Future[FileSnapshot] =
      val fileName = SafeFileName.numbered(base, n) + extension
      if taken.contains(fileName.toLowerCase) then attempt(n + 1)
      else
        DesktopIpc.createFile(MetadataMigration.pathIn(folder.path, fileName), text).recoverWith:
          case IpcFailure("DOCUMENT_EXISTS", _) if n < MaxNumber => attempt(n + 1)
    attempt(1)

  /** "Rename": rename the FILE, and then move its entry to the key of the new
    * path (ADR 0001, plan §6).
    *
    * The file goes first. If the entry does not move, the file has the new
    * name and the result says so: the person sees the file, and the metadata
    * stays with the old name. The shell refuses a name that exists.
    *
    * The document session moves to the new path, so the view on the screen
    * stays, and the next save writes the new file. The shell moved its watch.
    */
  def renameFile(session: DocumentSessionId, newName: String): Future[FileRename] =
    DesktopDocumentRegistry.get(session) match
      case None => Future.successful(FileRename.Refused("This file is not open any more."))
      case Some(open) =>
        fileNameFor(newName, open.path).map(fileName => (fileName, siblingOf(open.path, fileName))) match
          case Left(why) => Future.successful(FileRename.Refused(why))
          case Right((_, target)) if target == open.path =>
            // The rename dialog starts with the name of the file, and Enter
            // sends it. The name does not change, so nothing changes.
            Future.successful(FileRename.Done(open.path))
          case Right((fileName, target)) =>
            DesktopIpc.renameFile(open.path, target).transformWith:
              case Failure(IpcFailure("DOCUMENT_EXISTS", _)) =>
                Future.successful(FileRename.Refused(s"A file named $fileName exists already. Choose a different name."))
              case Failure(error) =>
                Future.successful(FileRename.Refused(s"Could not rename the file: ${messageOf(error)}"))
              case Success(renamed) =>
                DesktopDocumentRegistry.move(session, renamed.path)
                files.update: states =>
                  states.get(open.path).fold(states)(state => states.removed(open.path).updated(renamed.path, state))
                moveEntry(open.path, renamed.path)

  /** Move the entry of `from` to the key of `to`. Write the new entry first,
    * and then remove the old one. Thus a failure leaves two entries, and never
    * none.
    *
    * When the two paths have one key, the entry is where it must be, and
    * nothing changes. A write and then a remove of one key removes the entry.
    */
  private def moveEntry(from: String, to: String): Future[FileRename] =
    keyOf(from).flatMap: oldKey =>
      entries.now().get(oldKey) match
        case None => Future.successful(FileRename.Done(to))
        case Some(entry) =>
          keyOf(to)
            .flatMap: newKey =>
              if newKey == oldKey then Future.successful(false)
              else put(newKey, entry.copy(path = to, updatedAt = js.Date.now().toLong)).map(_ => true)
            .transformWith:
              case Success(false) => Future.successful(FileRename.Done(to))
              case Failure(error) =>
                Future.successful(
                  FileRename.MetadataNotMoved(
                    to,
                    s"The file is now $to, but its metadata could not move (${messageOf(error)}). " +
                      "The library still has the metadata under the old name of the file."
                  )
                )
              case Success(_) =>
                removeEntry(oldKey).transform:
                  case Success(_) => Success(FileRename.Done(to))
                  case Failure(error) =>
                    Success(
                      FileRename.MetadataNotMoved(
                        to,
                        s"The file is now $to, and its metadata moved with it. The old entry could not go " +
                          s"(${messageOf(error)}), so the library also lists the old name as a missing file. " +
                          "Remove that one from the library."
                      )
                    )

  /** Read each file of the list again, for the titles and the thumbnails. The
    * home page calls this when it opens, because a different tool can change
    * a file at any time.
    */
  def readFiles(): Future[Unit] =
    readPaths(entries.now().values.map(_.path).toVector.distinct)

  private def readUnknownFiles(): Future[Unit] =
    val known = files.now()
    readPaths(entries.now().values.map(_.path).toVector.distinct.filterNot(known.contains))

  private def readPaths(paths: Vector[String]): Future[Unit] =
    Future.traverse(paths)(readFileState).map(states => files.update(_ ++ states))

  private def readFileState(path: String): Future[(String, FileState)] =
    DesktopIpc
      .readFile(path)
      .map(snapshot => path -> FileState.Loaded(snapshot.text, snapshot.revision))
      .recover:
        case IpcFailure("DOCUMENT_MISSING", _) => path -> FileState.Missing
        case NonFatal(error)                    => path -> FileState.Unreadable(messageOf(error))

  // ----------------------------------------------------------- the entries

  /** Write an entry, with no base check. The mirror changes at once, and the
    * write goes to the shell. A failed write reads the store again, so that
    * the mirror shows what the disk holds.
    */
  private[library] def put(key: String, entry: LibraryEntry): Future[Unit] =
    write(key, entry, None).andThen:
      case Failure(error) =>
        dom.console.error(s"[library] could not save the metadata of ${entry.path}: ${messageOf(error)}")
        refresh()

  /** Write an entry. The mirror and [[texts]] change at once, and the write
    * goes to the shell. The caller handles a failure.
    *
    * The mirror changes before the reply, so the base of the next write is
    * this text. When this write fails, the shell refuses the next write that
    * has a base, and that write reads the entry again.
    */
  private def write(key: String, entry: LibraryEntry, base: Option[EntryBase]): Future[Unit] =
    val json = upickle.default.write(entry, indent = 2)
    entries.update(_.updated(key, entry))
    texts += key -> json
    keys.update(_.updated(entry.path, key))
    markChange(key)
    DesktopIpc.libraryWrite(s"$key.json", json, base)

  private def removeEntry(key: String): Future[Unit] =
    entries.update(_.removed(key))
    texts -= key
    markChange(key)
    DesktopIpc
      .libraryDelete(s"$key.json")
      .map(_ => ())
      .andThen:
        case Failure(error) =>
          dom.console.error(s"[library] could not remove $key.json: ${messageOf(error)}")
          refresh()

  private[library] def contains(key: String): Boolean = entries.now().contains(key)

  private def markChange(key: String): Unit =
    changeClock += 1
    lastChange += key -> changeClock

  /** The key of a path: from the cache, or from the shell. Two calls for one
    * path while the shell works get the same answer.
    */
  private[library] def keyOf(path: String): Future[String] =
    keys.now().get(path) match
      case Some(key) => Future.successful(key)
      case None =>
        hashing.get(path) match
          case Some(waiting) => waiting
          case None =>
            val result = DesktopIpc
              .entryNameOf(path)
              .map: name =>
                val key = name.stripSuffix(".json")
                keys.update(_.updated(path, key))
                key
              .andThen { case _ => hashing -= path }
            hashing += path -> result
            result

  /** Read the store again (V-15: a diagram that `gx add` wrote is there).
    *
    * An entry that this page changed after the read started keeps the value
    * of the page: the read can hold the bytes from before that change.
    *
    * Returns its Future so a test can wait for it rather than sleep.
    */
  private[library] def refresh(): Future[Unit] =
    val started = changeClock
    loadEntries()
      .map:
        case None => () // The shell cannot list the store, so the mirror keeps what it has.
        case Some(loaded) =>
          val changedHere = lastChange.collect { case (key, at) if at > started => key }
          def keepChanges[A](read: Map[String, A], here: Map[String, A]): Map[String, A] =
            changedHere.foldLeft(read)((m, key) => here.get(key).fold(m.removed(key))(m.updated(key, _)))
          entries.set(keepChanges(loaded.view.mapValues(_._1).toMap, entries.now()))
          texts = keepChanges(loaded.view.mapValues(_._2).toMap, texts)
      .flatMap(_ => readUnknownFiles())

  /** Each entry that the shell can see, and whose name is the key of its path,
    * with its exact text. `None` when the shell cannot list the store.
    *
    * An entry that does not parse, or whose name is not the key of its path,
    * is REPORTED and skipped. One bad file must not take down the whole library.
    * `MetadataStore.unreadable()` makes the same choice on the JVM.
    */
  private def loadEntries(): Future[Option[Map[String, (LibraryEntry, String)]]] =
    DesktopIpc
      .libraryList()
      .flatMap: stored =>
        val parsed = stored.flatMap: file =>
          file.content match
            case Left(why) =>
              dom.console.warn(s"[library] ${file.name} could not be read: $why")
              None
            case Right(json) =>
              try Some((file.name, upickle.default.read[LibraryEntry](json), json))
              catch
                case NonFatal(e) =>
                  dom.console.warn(s"[library] ${file.name} is not a readable entry: ${e.getMessage}")
                  None
        Future
          .traverse(parsed): (name, entry, json) =>
            keyOf(entry.path)
              .map: key =>
                if s"$key.json" == name then Some(key -> (entry, json))
                else
                  dom.console.warn(
                    s"[library] $name is the entry of ${entry.path}, and its name is not the key of that path. " +
                      "The list does not show it."
                  )
                  None
              .recover { case NonFatal(e) => dom.console.warn(s"[library] no key for ${entry.path}: ${messageOf(e)}"); None }
          .map(found => Some(found.flatten.toMap))
      .recover:
        case NonFatal(e) =>
          dom.console.error(s"[library] could not be listed: ${messageOf(e)}")
          None

object DesktopLibrary:

  private val LibraryChangedEvent = "ge:library.changed"

  /** The highest number that "New diagram" tries before it stops. */
  private val MaxNumber = 1000

  /** How many times a write of metadata tries, when a different writer
    * changes the entry each time ([[DesktopLibrary.writeMetadata]]).
    */
  private val MaxWriteTries = 3

  /** Read the library, and the file of each entry. */
  def load(): Future[DesktopLibrary] =
    val library = new DesktopLibrary()
    library.refresh().map(_ => library)

  /** The fields of the metadata that the viewer owns. `None` and `Some(true)`
    * of the language mode are the same value: the viewer reads `None` as on.
    */
  private final case class Owned(hidden: Set[String], collapsed: Set[String], autoDetect: Boolean) derives CanEqual

  private object Owned:
    def of(m: DiagramMetadata): Owned = Owned(m.hiddenElements, m.collapsedGroups, m.autoDetectFormat.getOrElse(true))
    val none: Owned                   = Owned(Set.empty, Set.empty, autoDetect = true)

  private[library] def messageOf(error: Throwable): String = error match
    case IpcFailure(_, message) => message
    case other                  => Option(other.getMessage).getOrElse(other.toString)

  private def formatOfPath(path: String): DiagramFormat =
    FilePersistence.formatOfPath(path).getOrElse(DiagramFormat.DOT)

  /** The file name for a rename. A name with no extension keeps the extension
    * of the file.
    */
  private[library] def fileNameFor(typed: String, oldPath: String): Either[String, String] =
    val name = typed.trim
    if name.isEmpty then Left("A file name cannot be empty.")
    else if name.exists(c => c == '/' || c == '\\') then Left("A file name cannot contain / or \\.")
    else if name == "." || name == ".." then Left(s"$name is not a file name.")
    else if name.lastIndexOf('.') > 0 then Right(name)
    else
      val oldName = oldPath.substring(oldPath.lastIndexWhere(c => c == '/' || c == '\\') + 1)
      val ext     = oldName.lastIndexOf('.') match
        case i if i > 0 => oldName.substring(i)
        case _          => ""
      Right(name + ext)

  /** A file in the folder of `path`, with the separator of `path`. */
  private[library] def siblingOf(path: String, fileName: String): String =
    path.lastIndexWhere(c => c == '/' || c == '\\') match
      case -1 => fileName
      case i  => path.substring(0, i + 1) + fileName
