package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{FileSystems, Files, Path}

/** A NEW library entry is owner-only, and an entry that exists keeps its
  * permissions (V-03).
  *
  * The desktop shell makes each new entry `rw-------`
  * (`write_library_entry` in `desktop/src-tauri/src/main.rs`). Its comment
  * says "A NEW entry is owner-only, as the entries that `gx` writes are", and
  * its tests assert `0o600`. An entry holds the tags and the notes of a
  * person, so another local account must not read it by chance.
  *
  * Before d8b88a37, `AtomicFiles.write` made each new file owner-only, because
  * `Files.createTempFile` makes `rw-------`. That commit gave a new DIAGRAM the
  * permissions of the umask, which is correct for a diagram. But
  * `MetadataStore.put` uses the same write, so a new entry also got the umask
  * (`rw-r--r--` with the usual umask 022).
  */
class EntryPermissionsSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ =>
      val dir = Files.createTempDirectory("gx-core-entry-perms").toRealPath()
      Files.createDirectories(dir.resolve("metadata"))
      dir
    ,
    teardown = dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def posix: Boolean = FileSystems.getDefault.supportedFileAttributeViews.contains("posix")

  private def entry(path: String) =
    LibraryEntry(path = path, metadata = DiagramMetadata(tags = List("t")), createdAt = 1L, updatedAt = 2L)

  private def modeOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

  tmp.test("a new entry is owner-only, as the desktop makes it"): dir =>
    assume(posix, "this file system has no POSIX permissions")
    val store = MetadataStore(dir.resolve("metadata"))
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    val file = dir.resolve("metadata").resolve(MetadataStore.fileNameOf("/work/a.dot"))
    assertEquals(modeOf(file), "rw-------")

  tmp.test("a put keeps the permissions of an entry that exists (V-03)"): dir =>
    assume(posix, "this file system has no POSIX permissions")
    val store = MetadataStore(dir.resolve("metadata"))
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    val file = dir.resolve("metadata").resolve(MetadataStore.fileNameOf("/work/a.dot"))
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    assertEquals(modeOf(file), "rw-r-----")
