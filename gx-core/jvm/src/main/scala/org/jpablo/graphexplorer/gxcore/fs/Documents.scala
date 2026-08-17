package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.ContentHash

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
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
      AtomicFiles.write(path, bytes)
      Document(path, encoded, Hashing.ofBytes(bytes), lineEnding)

  private def attempt[A](path: Path)(body: => A): Either[DocumentError, A] =
    try Right(body)
    catch case NonFatal(e) => Left(DocumentError.Io(path.toString, e.toString))
