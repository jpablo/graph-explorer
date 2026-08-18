package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.fs.AtomicFiles
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

enum StoreError derives CanEqual:
  case NotFound(id: DiagramId)
  case Unreadable(id: DiagramId, message: String)
  case Io(message: String)

/** The library on disk.
  *
  * {{{
  * ~/.graph-explorer/library/
  *   folders.json          the virtual tree, including empty folders
  *   diagrams/<id>.json    one record: text + metadata + binding
  * }}}
  *
  * **One file per diagram, on purpose.** `gx` and the desktop are separate
  * processes writing concurrently; a single library file would make every
  * unrelated edit a write conflict between them. Per-record files mean two
  * processes editing different diagrams never collide, and each record write
  * reuses the same atomic path as a document write.
  *
  * **The listing is derivable.** Everything needed to render the library can be
  * recovered by scanning `diagrams/`. `folders.json` holds only what scanning
  * cannot recover — the tree's shape, including folders with nothing in them —
  * so losing or corrupting it costs organisation, never content.
  *
  * Store records are also just `file:` resources, which is why `gx import`
  * reaches a running UI with no message sent: the desktop observes the new
  * record with the same watcher it uses for origins (§6).
  */
final class LibraryStore(val root: Path) extends DiagramSink:
  import LibraryStore.*

  /** `DiagramSink`, so the shared migration can write here as well as into the
    * desktop's library. Distinct from `save` only in its error type: the trait
    * is linked into Scala.js, where `StoreError`'s filesystem cases cannot
    * arise, so it flattens to the message a report would print anyway.
    */
  def write(diagram: Diagram): Either[String, Diagram] =
    save(diagram).left.map(_.toString)

  private val diagramsDir = root.resolve("diagrams")
  private val foldersFile = root.resolve("folders.json")

  def initialize(): Unit = Files.createDirectories(diagramsDir)

  def pathOf(id: DiagramId): Path = diagramsDir.resolve(s"${sanitize(id.value)}.json")

  def save(diagram: Diagram): Either[StoreError, Diagram] =
    try
      val json = upickle.default.write(diagram, indent = 2)
      AtomicFiles.write(pathOf(diagram.id), json.getBytes(StandardCharsets.UTF_8))
      Right(diagram)
    catch case NonFatal(e) => Left(StoreError.Io(e.toString))

  def get(id: DiagramId): Either[StoreError, Diagram] =
    val file = pathOf(id)
    if !Files.isRegularFile(file) then Left(StoreError.NotFound(id))
    else
      try Right(upickle.default.read[Diagram](Files.readString(file, StandardCharsets.UTF_8)))
      catch case NonFatal(e) => Left(StoreError.Unreadable(id, e.toString))

  def contains(id: DiagramId): Boolean = Files.isRegularFile(pathOf(id))

  def delete(id: DiagramId): Boolean =
    try Files.deleteIfExists(pathOf(id))
    catch case NonFatal(_) => false

  /** Every readable record.
    *
    * A record that fails to parse is SKIPPED rather than failing the listing.
    * One corrupt file — a half-written record from a killed process, or one
    * written by a future version — must not make the whole library unopenable.
    * [[unreadable]] reports them so they are visible rather than merely ignored.
    */
  def list(): Vector[Diagram] =
    scan().flatMap(_.toOption).sortBy(d => (d.folder.render, d.name.toLowerCase, d.id.value))

  def unreadable(): Vector[StoreError] = scan().flatMap(_.left.toOption)

  private def scan(): Vector[Either[StoreError, Diagram]] =
    if !Files.isDirectory(diagramsDir) then Vector.empty
    else
      val stream = Files.list(diagramsDir)
      try
        stream
          .toArray
          .toVector
          .map(_.asInstanceOf[Path])
          .filter(p => p.getFileName.toString.endsWith(".json"))
          .sortBy(_.getFileName.toString)
          .map: p =>
            val id = DiagramId(p.getFileName.toString.stripSuffix(".json"))
            try Right(upickle.default.read[Diagram](Files.readString(p, StandardCharsets.UTF_8)))
            catch case NonFatal(e) => Left(StoreError.Unreadable(id, e.toString))
      finally stream.close()

  /** Records bound to an origin.
    *
    * Plural because §3.1 permits it: one file may back several records with
    * different metadata. The watch registry still polls that file once.
    */
  def findByOrigin(origin: OriginUri): Vector[Diagram] =
    list().filter(_.binding.exists(_.origin == origin))

  def inFolder(folder: FolderPath): Vector[Diagram] =
    list().filter(_.folder == folder)

  // ------------------------------------------------------------ folders

  /** The tree, including folders that hold nothing.
    *
    * Folders containing diagrams are recovered from the records themselves, so
    * this file only has to carry the ones that would otherwise vanish — which
    * is what makes losing it survivable.
    */
  def folders(): Vector[FolderPath] =
    val declared =
      if !Files.isRegularFile(foldersFile) then Vector.empty
      else
        try upickle.default.read[Vector[FolderPath]](Files.readString(foldersFile, StandardCharsets.UTF_8))
        catch case NonFatal(_) => Vector.empty
    val fromDiagrams = list().map(_.folder)
    (declared ++ fromDiagrams ++ Vector(FolderPath.root)).distinct.sortBy(_.render)

  def saveFolders(paths: Vector[FolderPath]): Either[StoreError, Unit] =
    try
      val json = upickle.default.write(paths.distinct.sortBy(_.render), indent = 2)
      AtomicFiles.write(foldersFile, json.getBytes(StandardCharsets.UTF_8))
      Right(())
    catch case NonFatal(e) => Left(StoreError.Io(e.toString))

object LibraryStore:
  /** `~/.graph-explorer/library`, the location `gx` and the desktop agree on. */
  def default(home: Path = Paths.get(sys.props.getOrElse("user.home", "."))): LibraryStore =
    LibraryStore(home.resolve(".graph-explorer").resolve("library"))

  /** An id becomes a filename, so it must not be able to escape the directory or
    * collide after normalisation. Anything outside a conservative set becomes
    * `_`, which is lossy — hence [[DiagramId.derivedFrom]] producing ids that
    * are already safe rather than relying on this to clean up after them.
    */
  private[store] def sanitize(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '_' then c else '_')
    if cleaned.isEmpty then "_" else cleaned.take(120)
