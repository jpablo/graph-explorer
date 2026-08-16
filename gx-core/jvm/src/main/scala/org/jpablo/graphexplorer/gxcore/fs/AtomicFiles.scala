package org.jpablo.graphexplorer.gxcore.fs

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.util.control.NonFatal

/** Durable, all-or-nothing file replacement (V-02).
  *
  * Shared by the document layer and the library store rather than written twice:
  * both need the same guarantee, and a second copy is a second place for the
  * fsync to go missing — which is exactly what happened in v1, whose
  * `write_file_atomic` did `fs::write` + `rename` with no flush at all.
  */
object AtomicFiles:

  /** Write via a temp file in the SAME directory, flush it, then rename over the
    * target.
    *
    * Same directory because `ATOMIC_MOVE` is only atomic within a filesystem: a
    * temp under `/tmp` can land on a different one and silently degrade to a
    * copy, which is precisely the torn write this exists to prevent.
    *
    * Not done: fsync of the parent directory, which would also make the rename
    * itself durable. Java exposes no portable way to do it; the residual
    * exposure is a crash between the rename and the OS flushing its metadata.
    */
  def write(path: Path, bytes: Array[Byte], preservePermissions: Boolean = true): Unit =
    val dir  = Option(path.getParent).getOrElse(path.toAbsolutePath.getParent)
    Files.createDirectories(dir)
    val temp = Files.createTempFile(dir, s".${path.getFileName}.", ".tmp")
    try
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE)
      try
        channel.write(ByteBuffer.wrap(bytes))
        channel.force(true)
      finally channel.close()

      if preservePermissions then
        // V-03: keep the target's bits. v1 chmod'ed every file it wrote to 0600
        // (main.rs:1076), so one save silently made a group-readable diagram
        // owner-only — a change nobody asked for and nobody would look for.
        permissionsOf(path).foreach: perms =>
          try Files.setPosixFilePermissions(temp, perms)
          catch case NonFatal(_) => () // best effort; must never fail a write

      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case NonFatal(e) =>
        Files.deleteIfExists(temp) // never leave a .tmp beside the target
        throw e

  private def permissionsOf(path: Path): Option[java.util.Set[PosixFilePermission]] =
    if !Files.exists(path) then None
    else
      try Some(Files.getPosixFilePermissions(path))
      catch case _: UnsupportedOperationException => None // Windows has no POSIX view
