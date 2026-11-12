package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.fs.{AtomicFiles, Dirs, Hashing}
import org.jpablo.graphexplorer.gxcore.model.LibraryEntry

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

enum MetadataError derives CanEqual:
  case NotFound(path: String)

  /** `file` is the name of the entry file in the store. */
  case Unreadable(file: String, message: String)
  case Io(message: String)

/** The library: the metadata of each diagram file, by the path of the file
  * (plan §3).
  *
  * {{{
  * $GX_HOME/library/metadata/
  *   <sha256>.json    one diagram: path, tags, notes, hidden and collapsed
  * }}}
  *
  * `root` is the `metadata/` directory itself. The migration uses this, and
  * it writes a new store into a temporary directory before a rename.
  *
  * The name of an entry file is the hex SHA-256 of the canonical `file:` URI
  * of the diagram ([[LibraryEntry.keySource]]). Thus the desktop app can find
  * an entry with no list and no index.
  *
  * Each write is atomic ([[AtomicFiles]]), and each diagram has its own file.
  * Thus gx and the desktop do not collide when they change two different
  * diagrams at the same time.
  */
final class MetadataStore(val root: Path):
  import MetadataStore.*

  private def fileOf(path: String): Path = root.resolve(fileNameOf(path))

  /** The entry of a diagram file. `path` must be canonical. */
  def get(path: String): Either[MetadataError, LibraryEntry] =
    val file = fileOf(path)
    if !Files.isRegularFile(file) then Left(MetadataError.NotFound(path))
    else read(file)

  def contains(path: String): Boolean = Files.isRegularFile(fileOf(path))

  /** Write an entry. A second put for the same path replaces the first.
    *
    * The put refuses a store whose directory does not exist. The migration
    * makes the directory, and it runs only while the directory does not
    * exist. If a put made it, the migration would never run, and the old
    * records would stay out of the library.
    *
    * A NEW entry file is owner-only (`rw-------`), as the desktop shell makes
    * it (`write_library_entry`). An entry holds the tags and the notes of a
    * person, so a different local account must not read it by chance. An
    * entry file that exists keeps its permissions (V-03).
    */
  def put(entry: LibraryEntry): Either[MetadataError, LibraryEntry] =
    if !Files.isDirectory(root) then
      Left(MetadataError.Io(s"the metadata store $root does not exist. The migration makes it."))
    else
      try
        val json = upickle.default.write(entry, indent = 2)
        AtomicFiles.write(fileOf(entry.path), json.getBytes(StandardCharsets.UTF_8), newFileMode = Some(OwnerOnly))
        Right(entry)
      catch case NonFatal(e) => Left(MetadataError.Io(e.toString))

  /** Take a diagram out of the library. The diagram file does not change.
    * `Right(false)` tells that the library did not have the diagram.
    */
  def remove(path: String): Either[MetadataError, Boolean] =
    try Right(Files.deleteIfExists(fileOf(path)))
    catch case NonFatal(e) => Left(MetadataError.Io(e.toString))

  /** Each entry that the store can read, in the order of the paths.
    *
    * An entry that does not parse is not in the list, and the list does not
    * fail. One bad file must not hide the whole library. [[unreadable]]
    * reports the bad files.
    */
  def list(): Vector[LibraryEntry] = listing()._1

  /** [[list]], and the bad files, from one scan of the store. */
  def listing(): (Vector[LibraryEntry], Vector[MetadataError.Unreadable]) =
    val all = scan()
    (all.flatMap(_.toOption).sortBy(_.path), all.collect { case Left(u: MetadataError.Unreadable) => u })

  private def scan(): Vector[Either[MetadataError, LibraryEntry]] =
    Dirs.entries(root)
      .filter(p => p.getFileName.toString.endsWith(".json") && Files.isRegularFile(p))
      .map(read)

  /** Read an entry file, and check that its name is the key of its path. A
    * file with a different name is a copy that a person made by hand. The
    * store reports it, because `get` of its path cannot find it.
    */
  private def read(file: Path): Either[MetadataError, LibraryEntry] =
    val name = file.getFileName.toString
    try
      val entry = upickle.default.read[LibraryEntry](Files.readString(file, StandardCharsets.UTF_8))
      if fileNameOf(entry.path) == name then Right(entry)
      else Left(MetadataError.Unreadable(name, s"the entry is for ${entry.path}, and its file name is not the key of that path"))
    catch case NonFatal(e) => Left(MetadataError.Unreadable(name, e.toString))

object MetadataStore:

  /** The permissions of a new entry file. The set is immutable, so no caller can change it. */
  private val OwnerOnly: java.util.Set[PosixFilePermission] =
    java.util.Set.copyOf(PosixFilePermissions.fromString("rw-------"))

  /** The key of a diagram file: the hex SHA-256 of the UTF-8 bytes of its
    * canonical `file:` URI. `canonicalPath` must be canonical
    * (`FileOrigins.canonicalize`). A path that is not canonical gives a key,
    * but it is the key of a different identity.
    */
  def keyOf(canonicalPath: String): String =
    Hashing.ofBytes(LibraryEntry.keySource(canonicalPath).getBytes(StandardCharsets.UTF_8)).hex

  def fileNameOf(canonicalPath: String): String = s"${keyOf(canonicalPath)}.json"
