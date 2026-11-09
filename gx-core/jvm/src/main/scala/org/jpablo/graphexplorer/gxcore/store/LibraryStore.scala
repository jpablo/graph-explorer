package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.fs.{AtomicFiles, GxHome}
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

enum StoreError derives CanEqual:
  case NotFound(id: DiagramId)
  case Unreadable(id: DiagramId, message: String)
  case Io(message: String)

/** The library on the disk.
  *
  * {{{
  * ~/.graph-explorer/library/
  *   folders.json          the virtual tree, with the empty folders
  *   diagrams/<id>.json    one diagram: text + metadata + binding
  * }}}
  *
  * **One file for each diagram, on purpose.** `gx` and the desktop are
  * different processes, and they can write at the same time. With one library
  * file, each edit would cause a write conflict between them, also when the
  * edits are not related. With one file for each diagram, two processes that
  * edit different diagrams never collide. Each write of a diagram uses the same
  * atomic path as a write of an origin file.
  *
  * **The code can derive the list.** A scan of `diagrams/` recovers all the
  * data that the library view needs. `folders.json` holds only the data that a
  * scan cannot recover: the shape of the tree, with the empty folders. If that
  * file is lost or corrupt, the user loses the organization, but never the
  * content.
  *
  * Each diagram in the store is also only a `file:` resource. Thus `gx import`
  * gets to a UI that runs, and it sends no message: the desktop sees the new
  * diagram with the same watcher that it uses for origins (§6).
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

  /** Each diagram that the store can read.
    *
    * If a diagram does not parse, the list SKIPS it, and the list does not
    * fail. With one corrupt file, the user must still be able to open the library.
    * Examples are a diagram that a killed process wrote only in part, or a
    * diagram from a future version. [[unreadable]] reports these files, so
    * that the user can see them.
    */
  def list(): Vector[Diagram] =
    scan().flatMap(_.toOption).sortBy(d => (d.metadata.folder.render, d.metadata.name.toLowerCase, d.id.value))

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

  // ------------------------------------------------------------ folders

  /** The tree, with the folders that hold nothing.
    *
    * The code recovers each folder that contains diagrams from the diagrams.
    * Thus this file holds only the folders that the code cannot recover in a
    * different way. Because of this, the library survives the loss of this
    * file.
    */
  def folders(): Vector[FolderPath] =
    val declared =
      if !Files.isRegularFile(foldersFile) then Vector.empty
      else
        try upickle.default.read[Vector[FolderPath]](Files.readString(foldersFile, StandardCharsets.UTF_8))
        catch case NonFatal(_) => Vector.empty
    val fromDiagrams = list().map(_.metadata.folder)
    (declared ++ fromDiagrams ++ Vector(FolderPath.root)).distinct.sortBy(_.render)

  def saveFolders(paths: Vector[FolderPath]): Either[StoreError, Unit] =
    try
      val json = upickle.default.write(paths.distinct.sortBy(_.render), indent = 2)
      AtomicFiles.write(foldersFile, json.getBytes(StandardCharsets.UTF_8))
      Right(())
    catch case NonFatal(e) => Left(StoreError.Io(e.toString))

object LibraryStore:
  /** `$GX_HOME/library`, or `~/.graph-explorer/library` — the location `gx` and
    * the desktop agree on.
    *
    * The parameter is the GX HOME, not the user's: routed through [[GxHome]] so
    * there is one definition of where the data lives. Two would be one too many
    * — a half-applied override is worse than none, because it splits the library
    * from the runtime file that names its socket.
    */
  def default(gxHome: Path = GxHome.resolveOrThrow()): LibraryStore =
    LibraryStore(GxHome.libraryDir(gxHome))

  /** An id becomes a filename, so it must not be able to escape the directory or
    * collide after normalisation. Anything outside a conservative set becomes
    * `_`, which is lossy — hence [[DiagramId.derivedFrom]] producing ids that
    * are already safe rather than relying on this to clean up after them.
    */
  /** Delegates to the shared rule. Under D7.3 the webview writes this same
    * directory, so the id -> file-name mapping cannot live on one side only.
    */
  private[store] def sanitize(id: String): String = DiagramFileName.of(id)
