package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{Board, DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.gxcore.store.{MetadataMigration, SafeFileName}
import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages, SourceTitle}
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopIpc}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc.{DiagramsFolder, EntryBase, FileSnapshot, IpcFailure}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, DocumentSessionId}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success, Try}
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
  /** The writes that wait for the key of their path: the metadata of the
    * viewer before the first change of the burst, and the latest metadata.
    */
  private var waitingWrites: Map[String, (DiagramMetadata, DiagramMetadata)] = Map.empty

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

  // A board file has an entry also (ADR 0003), and it is not a diagram. The
  // list of the diagrams leaves it out, and [[boards]] has it.
  private def toIndex(byKey: Map[String, LibraryEntry], fileStates: Map[String, FileState]): LibraryIndex =
    LibraryIndex(
      byKey.toList
        .filterNot((_, e) => Board.isBoardPath(e.path))
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

  // ------------------------------------------------------------ the boards

  /** The board files of the library, newest first. The name of a board is
    * the name of its file. [[open]] opens a board by its id, as a diagram.
    */
  def boards: Signal[List[BoardSummary]] = entries.signal.map(boardsOf)

  def boardsNow(): List[BoardSummary] = boardsOf(entries.now())

  private def boardsOf(byKey: Map[String, LibraryEntry]): List[BoardSummary] =
    byKey.toList
      .collect:
        case (key, e) if Board.isBoardPath(e.path) =>
          BoardSummary(key, LibraryEntry.nameOf(e.path), lastModified = e.updatedAt, createdAt = e.createdAt)
      .sortBy(board => (-board.createdAt, board.id))

  /** The number of items of a board, when the library read its file and the
    * file parses.
    */
  def boardItemCount(id: String): Option[Int] = itemCountOf(fileState(DiagramId(id)))

  /** The number of items of a board, as a Signal. It changes when the
    * library reads the board file again, for example at the mount of the home
    * page. Thus the card of a board shows the count of the file after a save.
    */
  def boardItemCountSignal(id: String): Signal[Option[Int]] =
    entries.signal
      .combineWith(files.signal)
      .map((byKey, fileStates) => itemCountOf(byKey.get(id).flatMap(e => fileStates.get(e.path))))
      .distinct

  private def itemCountOf(state: Option[FileState]): Option[Int] =
    state.collect { case FileState.Loaded(text, _) => Board.parse(text).toOption.map(_.items.size) }.flatten

  /** "New board": a new `.gxboard` file in the folder for new diagrams, with
    * an entry, open on the screen.
    */
  def newBoard(name: String): Future[Either[String, Route]] =
    newFile("board", name, Board.render(Board.empty), s".${Board.Extension}")

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
          // The same rule as the viewer of the file.
          val format  = DiagramFormat.ofFile(e.path, text).getOrElse(DiagramFormat.DOT)
          val extractor = languages.extractor(format)
          DiagramCardInfo(format, extractor.extractTitle(text).getOrElse(fileName), extractor.diagramKind(text))
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
    * The viewer owns the hidden elements, the collapsed groups, the language
    * mode, and the tags and the notes (the About section of the Diagram
    * panel). The entry owns the rest: `createdAt` and each key that this build
    * does not know.
    *
    * The write is a three-way merge. `before` is the metadata that the viewer
    * had before this change, and `viewer` is the metadata after it. The write
    * applies only the change of the viewer onto the entry
    * ([[DesktopLibrary.applyChange]]). A field that the viewer did not change
    * keeps the value of the entry. A hidden element, a collapsed group or a
    * tag that the viewer did not add or remove also keeps its state in the
    * entry. Thus a `gx lock`, `gx unlock`, `gx hide` or `gx tag` stays after
    * the next hide in the viewer. A file with no entry gets all the fields of the viewer.
    *
    * The mirror can be older than the disk: the shell polls the store, and
    * the refresh needs one more round trip. Thus the write is a
    * compare-and-swap on the text of the entry that the merge used. When `gx`
    * changed the entry in the meantime (`gx tag`, `gx lock`), the shell
    * refuses the write. The page then reads the entry again, applies the SAME
    * change onto it, and tries again ([[MaxWriteTries]] times). Thus a change
    * from `gx` that the mirror does not know yet stays after the next change
    * in the viewer.
    *
    * Only a change of the viewer writes. When the fields of the viewer did not
    * change (an edit of the source, a visit), the write does not occur. A
    * keystroke thus cannot put back an entry that `gx remove` removed. When
    * the change gives what the entry already holds (the adoption of an
    * outside change), the write does not occur either, because a write would
    * move `updatedAt`. A file with no entry gets one on the first change of
    * its metadata, and that puts it in the library, as `gx hide` does.
    *
    * While the key of the path comes from the shell, a burst of changes gives
    * one write. That write has the `before` of the first change of the burst
    * and the `viewer` of the last change.
    */
  def writeMetadata(path: String, before: DiagramMetadata, viewer: DiagramMetadata): Unit =
    if Owned.of(before) != Owned.of(viewer) then
      keys.now().get(path) match
        case Some(key) =>
          putMetadata(key, path, before, viewer)
          ()
        case None =>
          // The key comes from the shell. Keep the first `before` and the
          // latest value while it comes, so that a burst gives one write.
          val first = waitingWrites.get(path).fold(before)(_._1)
          waitingWrites += path -> (first, viewer)
          keyOf(path).foreach: key =>
            waitingWrites.get(path).foreach: (firstBefore, latest) =>
              waitingWrites -= path
              putMetadata(key, path, firstBefore, latest)

  private def putMetadata(key: String, path: String, before: DiagramMetadata, viewer: DiagramMetadata): Future[Unit] =
    val current   = entries.now().get(key)
    val unchanged = current match
      case Some(e) => Owned.of(applyChange(e.metadata, before, viewer)) == Owned.of(e.metadata)
      case None    => Owned.of(viewer) == Owned.none
    if unchanged then Future.unit
    else
      val base = for entry <- current; text <- texts.get(key) yield (entry, text)
      mergeAndWrite(key, path, before, viewer, base, MaxWriteTries).andThen(reportWriteFailure(path))

  /** Apply the change of the viewer (from `before` to `viewer`) onto `base`,
    * and write the result with the text of `base` as the base of the
    * compare-and-swap. `base` is the entry and its exact text, or `None` when
    * there is no entry. On a refusal, read the entry again and apply the same
    * change onto it, `tries` times in all.
    */
  private def mergeAndWrite(
      key: String,
      path: String,
      before: DiagramMetadata,
      viewer: DiagramMetadata,
      base: Option[(LibraryEntry, String)],
      tries: Int
  ): Future[Unit] =
    val now   = js.Date.now().toLong
    val entry = base.map(_._1)
    // `copy` keeps each key of the entry that this build does not know.
    val merged = entry.fold(
      LibraryEntry(path, applyChange(DiagramMetadata.empty, DiagramMetadata.empty, viewer), createdAt = now, updatedAt = now)
    )(e => e.copy(metadata = applyChange(e.metadata, before, viewer), updatedAt = now))
    write(key, merged, Some(base.fold(EntryBase.Absent)((_, text) => EntryBase.Present(text)))).recoverWith:
      case IpcFailure("LIBRARY_CONFLICT", _) if tries > 1 =>
        readEntry(key).flatMap(fresh => mergeAndWrite(key, path, before, viewer, fresh, tries - 1))

  /** The entry of `key` as the disk holds it now, with its exact text. */
  private def readEntry(key: String): Future[Option[(LibraryEntry, String)]] =
    DesktopIpc
      .libraryRead(s"$key.json")
      .map(_.map(json => (upickle.default.read[LibraryEntry](json), json)))

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
    newFile("diagram", name, text, extension)

  /** A new file with an entry, open on the screen. `kind` is the word for the
    * message of a failure.
    */
  private def newFile(kind: String, name: String, text: String, extension: String): Future[Either[String, Route]] =
    val created =
      for
        folder <- DesktopIpc.diagramsFolder()
        file   <- createUnique(folder, SafeFileName.of(name), extension, text)
      yield file
    created.transformWith:
      case Failure(e) => Future.successful(Left(s"Could not make a new $kind: ${messageOf(e)}"))
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
    write(key, entry, None).andThen(reportWriteFailure(entry.path))

  /** Log a failed write of the entry of `path`, and read the store again, so
    * that the mirror shows what the disk holds.
    */
  private def reportWriteFailure(path: String): PartialFunction[Try[Unit], Unit] =
    case Failure(error) =>
      dom.console.error(s"[library] could not save the metadata of $path: ${messageOf(error)}")
      refresh()
      ()

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
  private final case class Owned(
      hidden:     Set[String],
      collapsed:  Set[String],
      autoDetect: Boolean,
      renderer:   Option[String],
      extractor:  Option[String],
      locked:     Boolean,
      tags:       List[String],
      notes:      String
  ) derives CanEqual

  private object Owned:
    def of(m: DiagramMetadata): Owned =
      Owned(
        m.hiddenElements,
        m.collapsedGroups,
        m.autoDetectFormat.getOrElse(true),
        m.renderer,
        m.extractor,
        m.locked,
        m.tags,
        m.notes
      )
    val none: Owned = of(DiagramMetadata.empty)

  /** Apply the change of the viewer, from `before` to `viewer`, onto `base`
    * ([[DesktopLibrary.writeMetadata]]).
    *
    * Only the fields that the viewer owns change. A field that has the same
    * value in `before` and in `viewer` keeps the value of `base`. A different
    * writer can have changed that field after the viewer read it. For the
    * hidden elements and the collapsed groups, the merge is for each element:
    * the elements that the viewer added go in, the elements that the viewer
    * removed go out, and the other elements of `base` stay. The tags merge in
    * the same way, and they keep the order of `base`. A tag that the viewer
    * added goes at the end. The notes are one field: the text of the viewer
    * replaces the text of `base`.
    */
  private[library] def applyChange(base: DiagramMetadata, before: DiagramMetadata, viewer: DiagramMetadata): DiagramMetadata =
    def set(baseSet: Set[String], beforeSet: Set[String], viewerSet: Set[String]): Set[String] =
      baseSet -- (beforeSet -- viewerSet) ++ (viewerSet -- beforeSet)
    def list(baseList: List[String], beforeList: List[String], viewerList: List[String]): List[String] =
      val removed = beforeList.filterNot(viewerList.contains)
      val added   = viewerList.filterNot(t => beforeList.contains(t) || baseList.contains(t))
      baseList.filterNot(removed.contains) ++ added
    def field[A](baseValue: A, beforeValue: A, viewerValue: A)(using CanEqual[A, A]): A =
      if beforeValue == viewerValue then baseValue else viewerValue
    val (b, v) = (Owned.of(before), Owned.of(viewer))
    base.copy(
      hiddenElements = set(base.hiddenElements, before.hiddenElements, viewer.hiddenElements),
      collapsedGroups = set(base.collapsedGroups, before.collapsedGroups, viewer.collapsedGroups),
      autoDetectFormat = if b.autoDetect == v.autoDetect then base.autoDetectFormat else viewer.autoDetectFormat,
      renderer = field(base.renderer, before.renderer, viewer.renderer),
      extractor = field(base.extractor, before.extractor, viewer.extractor),
      locked = field(base.locked, before.locked, viewer.locked),
      tags = list(base.tags, before.tags, viewer.tags),
      notes = field(base.notes, before.notes, viewer.notes)
    )

  private[library] def messageOf(error: Throwable): String = error match
    case IpcFailure(_, message) => message
    case other                  => Option(other.getMessage).getOrElse(other.toString)

  private def formatOfPath(path: String): DiagramFormat =
    DiagramFormat.ofPath(path).getOrElse(DiagramFormat.DOT)

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
