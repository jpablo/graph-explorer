package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.ContentHash

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.util.control.NonFatal

/** A document as it exists on disk right now. */
final case class Document(
    path:       Path,
    text:       String,
    hash:       ContentHash,
    lineEnding: LineEnding
)

enum DocumentError derives CanEqual:
  case NotFound(path: String)
  case NotAFile(path: String)

  /** The write was refused because the caller's baseline is stale. Carries both
    * hashes so the caller can report what changed underneath it, and — the part
    * that matters — the file has NOT been modified (V-01).
    */
  case Conflict(path: String, expected: ContentHash, actual: ContentHash)
  case AlreadyExists(path: String)
  case Io(path: String, message: String)

/** Reading and writing diagram files.
  *
  * Every read and write names UTF-8 explicitly (V-16). Windows reports
  * `windows-1252` as its default charset, so a platform-default decode changes
  * the bytes — and under D1 the bytes are the revision, which would make an
  * accented label look permanently Diverged between a Windows machine and a Mac
  * with nobody having edited anything. P0 confirmed the charset difference on a
  * real runner; this is the guard.
  */
object Documents:

  def read(path: Path): Either[DocumentError, Document] =
    if !Files.exists(path) then Left(DocumentError.NotFound(path.toString))
    else if !Files.isRegularFile(path) then Left(DocumentError.NotAFile(path.toString))
    else
      attempt(path):
        val bytes = Files.readAllBytes(path)
        val text  = String(bytes, StandardCharsets.UTF_8)
        Document(path, text, Hashing.ofBytes(bytes), LineEnding.detect(text))

  /** Hash what is on disk without decoding it. */
  def hashOf(path: Path): Option[ContentHash] =
    try Option.when(Files.isRegularFile(path))(Hashing.ofBytes(Files.readAllBytes(path)))
    catch case NonFatal(_) => None

  /** Conditional write: replace the contents only if the file still hashes to
    * `base`. This is D1's compare-and-swap, and it is the whole of the conflict
    * protocol — no locks, no coordination, and it works between processes that
    * have never heard of each other.
    *
    * The check-then-write window is real but small, and it is strictly better
    * than v1, whose in-memory counter could disagree with the disk indefinitely.
    */
  def write(path: Path, text: String, base: ContentHash): Either[DocumentError, Document] =
    read(path).flatMap: current =>
      if current.hash != base then
        Left(DocumentError.Conflict(path.toString, expected = base, actual = current.hash))
      else
        // The EXISTING file's convention, not the incoming text's: preserving it
        // is what keeps a save from changing the document's identity (V-04).
        writeBytes(path, text, current.lineEnding)

  /** Create a file that does not exist yet. Separate from [[write]] because
    * "I expect nothing here" and "I expect exactly these bytes" are different
    * claims, and conflating them makes an overwrite look like a create.
    */
  def create(path: Path, text: String, lineEnding: LineEnding = LineEnding.Lf)
      : Either[DocumentError, Document] =
    if Files.exists(path) then Left(DocumentError.AlreadyExists(path.toString))
    else writeBytes(path, text, lineEnding)

  private def writeBytes(path: Path, text: String, lineEnding: LineEnding)
      : Either[DocumentError, Document] =
    attempt(path):
      val encoded = lineEnding.applyTo(text)
      val bytes   = encoded.getBytes(StandardCharsets.UTF_8) // V-16
      writeAtomic(path, bytes)
      Document(path, encoded, Hashing.ofBytes(bytes), lineEnding)

  /** Write via a temp file in the SAME directory, fsync it, then rename over the
    * target (V-02). Same directory because `ATOMIC_MOVE` is only atomic within a
    * filesystem; a temp in `/tmp` can land on a different one and degrade to a
    * copy, which is exactly the non-atomic write this avoids.
    *
    * v1 did `fs::write` + `rename` with no fsync, so a crash between the two
    * could leave the rename durable and the contents not. The `force(true)` is
    * that fix.
    *
    * Not done: fsync of the parent directory, which would also make the rename
    * itself durable. Java exposes no portable way to do it, and the exposure is
    * a crash in the window between rename and the OS flushing metadata.
    */
  private def writeAtomic(path: Path, bytes: Array[Byte]): Unit =
    val dir  = Option(path.getParent).getOrElse(path.toAbsolutePath.getParent)
    val temp = Files.createTempFile(dir, s".${path.getFileName}.", ".tmp")
    try
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE)
      try
        channel.write(ByteBuffer.wrap(bytes))
        channel.force(true)
      finally channel.close()

      // V-03: keep the target's permission bits. v1 chmod'ed every file it wrote
      // to 0600 (main.rs:1076), so one Cmd+S silently made a group-readable
      // diagram owner-only — a change the user never asked for and would not
      // think to look for.
      permissionsOf(path).foreach: perms =>
        try Files.setPosixFilePermissions(temp, perms)
        catch case NonFatal(_) => () // best effort; must never fail a write

      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case NonFatal(e) =>
        Files.deleteIfExists(temp) // never leave a .tmp behind on failure
        throw e

  private def permissionsOf(path: Path): Option[java.util.Set[PosixFilePermission]] =
    if !Files.exists(path) then None
    else
      try Some(Files.getPosixFilePermissions(path))
      catch case _: UnsupportedOperationException => None // Windows has no POSIX view

  private def attempt[A](path: Path)(body: => A): Either[DocumentError, A] =
    try Right(body)
    catch case NonFatal(e) => Left(DocumentError.Io(path.toString, e.toString))
