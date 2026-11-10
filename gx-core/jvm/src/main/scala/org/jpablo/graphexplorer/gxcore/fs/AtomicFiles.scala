package org.jpablo.graphexplorer.gxcore.fs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.ThreadLocalRandom
import scala.util.control.NonFatal

/** Durable, all-or-nothing writes of a whole file (V-02). [[write]] replaces
  * the target. [[create]] makes a target that does not exist, and it never
  * replaces a file.
  *
  * Shared by the document layer and the library store rather than written twice:
  * both need the same guarantee, and a second copy is a second place for the
  * fsync to go missing — which is exactly what happened in v1, whose
  * `write_file_atomic` did `fs::write` + `rename` with no flush at all.
  *
  * Both operations write the bytes to a temp file in the SAME directory and
  * flush it before the target changes. For a new target, the temp file gets
  * the permissions that the umask gives, as a plain new file does.
  * `Files.createTempFile` makes `rw-------`, and with it each new file was
  * owner-only.
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
    *
    * A new target gets the permissions that the umask gives. An existing
    * target keeps its permissions (V-03).
    */
  def write(path: Path, bytes: Array[Byte]): Unit =
    val dir  = directoryOf(path)
    Files.createDirectories(dir)
    // V-03: keep the target's bits. v1 chmod'ed every file it wrote to 0600
    // (main.rs:1076), so one save silently made a group-readable diagram
    // owner-only — a change nobody asked for and nobody would look for.
    val kept = permissionsOf(path)
    val temp = writeTemp(dir, path, bytes, kept)
    try
      kept.foreach: perms =>
        // The umask can remove bits when the temp file is made. This call
        // gives the temp file all the bits of the target.
        try Files.setPosixFilePermissions(temp, perms)
        catch case NonFatal(_) => () // best effort; must never fail a write

      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case NonFatal(e) =>
        discard(temp, e) // never leave a .tmp beside the target
        throw e

  /** Make `path` with these bytes if no file is at `path`. The result is
    * `false` if a file is there. Then the file stays unchanged.
    *
    * A rename cannot do this. On Unix, `ATOMIC_MOVE` calls rename(2), and
    * rename(2) replaces a target that exists, also without `REPLACE_EXISTING`.
    * A move without `ATOMIC_MOVE` looks for the target and then renames. A
    * different writer can make the target between these two steps.
    *
    * Thus the create makes a hard link to the temp file, and then it deletes
    * the temp name. A hard link (link(2)) is one atomic step, and it fails if
    * the target exists. The target appears with all of its bytes, or it does
    * not appear. The temp file is in the same directory, because a hard link
    * cannot go to a different file system. The fsync of the parent directory
    * is not done, as in [[write]].
    *
    * Some file systems refuse hard links, for example FAT and some network
    * mounts. There, the create opens the target with `CREATE_NEW` and writes
    * the bytes into it. This also never replaces a file, and the create
    * flushes the bytes before it returns. But a reader can see a part of the
    * bytes during the write, and a crash can leave a part of them.
    */
  def create(path: Path, bytes: Array[Byte]): Boolean =
    val dir  = directoryOf(path)
    Files.createDirectories(dir)
    val temp = writeTemp(dir, path, bytes, perms = None)
    val linked =
      try
        Files.createLink(path, temp)
        Some(true)
      catch
        // NFS can repeat a link that succeeded, and then report that the
        // target exists. If the target is our temp file, the link succeeded.
        case NonFatal(_) if isSameFile(path, temp) => Some(true)
        case _: FileAlreadyExistsException => Some(false)
        // This file system refuses hard links. Use the fallback below.
        case _: UnsupportedOperationException | _: IOException => None
        case NonFatal(e) =>
          discard(temp, e)
          throw e
    // The target does not need the temp name now. A failure to delete the
    // name does not change the target, so it does not change the result.
    try Files.deleteIfExists(temp)
    catch case NonFatal(_) => ()
    linked.getOrElse(createInPlace(path, bytes))

  /** The fallback of [[create]] for a file system with no hard links. */
  private def createInPlace(path: Path, bytes: Array[Byte]): Boolean =
    val opened =
      try Some(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
      catch case _: FileAlreadyExistsException => None
    opened.foreach: channel =>
      try fill(channel, bytes)
      catch
        case NonFatal(e) =>
          // CREATE_NEW made this file, so it is ours. Delete the part that we wrote.
          discard(path, e)
          throw e
    opened.isDefined

  /** Write the bytes to a new temp file beside the target, and flush them. */
  private def writeTemp(
      dir:    Path,
      target: Path,
      bytes:  Array[Byte],
      perms:  Option[java.util.Set[PosixFilePermission]]
  ): Path =
    val (temp, channel) = openTemp(dir, target, perms, attempts = 100)
    try fill(channel, bytes)
    catch
      case NonFatal(e) =>
        discard(temp, e)
        throw e
    temp

  /** `CREATE_NEW` refuses a name that exists, also a symbolic link. Thus the
    * temp file is always a new file of ours.
    *
    * With no `perms`, the temp file gets the permissions of the umask. With
    * `perms`, the temp file gets them at the creation, less the bits that the
    * umask removes. Thus the new bytes are never more open than the target
    * is. A chmod after the open is too late: a different process can open the
    * file before the chmod, and it keeps that access.
    */
  private def openTemp(
      dir:      Path,
      target:   Path,
      perms:    Option[java.util.Set[PosixFilePermission]],
      attempts: Int
  ): (Path, FileChannel) =
    val random  = java.lang.Long.toUnsignedString(ThreadLocalRandom.current.nextLong)
    val temp    = dir.resolve(s".${target.getFileName}.$random.tmp")
    val options = java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    val attrs   = perms.map(PosixFilePermissions.asFileAttribute).toSeq
    try (temp, FileChannel.open(temp, options, attrs*))
    catch
      case _: FileAlreadyExistsException if attempts > 1 =>
        openTemp(dir, target, perms, attempts - 1)

  /** Write all the bytes, flush them to the disk, and close the channel. */
  private def fill(channel: FileChannel, bytes: Array[Byte]): Unit =
    try
      val buffer = ByteBuffer.wrap(bytes)
      while buffer.hasRemaining do channel.write(buffer)
      channel.force(true)
    finally channel.close()

  /** Delete a file of ours after a failure. A second failure does not hide the first. */
  private def discard(file: Path, cause: Throwable): Unit =
    try Files.deleteIfExists(file)
    catch case NonFatal(e) => cause.addSuppressed(e)

  private def isSameFile(a: Path, b: Path): Boolean =
    try Files.isSameFile(a, b)
    catch case NonFatal(_) => false

  private def directoryOf(path: Path): Path =
    Option(path.getParent).getOrElse(path.toAbsolutePath.getParent)

  private def permissionsOf(path: Path): Option[java.util.Set[PosixFilePermission]] =
    if !Files.exists(path) then None
    else
      try Some(Files.getPosixFilePermissions(path))
      catch case _: UnsupportedOperationException => None // Windows has no POSIX view
