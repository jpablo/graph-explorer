package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.FileOrigins
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

import java.nio.file.{Files, Path}

class LibraryStoreSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-store").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def diagram(id: String, name: String = "d", folder: FolderPath = FolderPath.root) =
    Diagram(
      id = DiagramId(id),
      format = "DOT",
      text = "digraph G { a -> b }",
      binding = None,
      metadata = DiagramMetadata(name = name, folder = folder),
      createdAt = 1,
      updatedAt = 2
    )

  tmp.test("a diagram round trips through disk unchanged") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val d = diagram("one").copy(
      metadata = DiagramMetadata(
        name = "d",
        hiddenElements = Set("n1", "n2"),
        collapsedGroups = Set("g1"),
        tags = List("arch"),
        notes = "a note"
      ),
      binding = Some(
        Binding(
          FileOrigins.originOfPath("/tmp/a.dot"),
          SyncMode.Pull,
          org.jpablo.graphexplorer.gxcore.model.ContentHash.fromHex("ab"),
          123L
        )
      )
    )
    store.save(d).fold(e => fail(s"$e"), identity)
    assertEquals(store.get(d.id), Right(d))
  }

  tmp.test("a missing diagram is NotFound, not an exception") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    assertEquals(store.get(DiagramId("nope")), Left(StoreError.NotFound(DiagramId("nope"))))
  }

  tmp.test("saving twice updates in place rather than duplicating") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    store.save(diagram("one", name = "first")).fold(e => fail(s"$e"), identity)
    store.save(diagram("one", name = "second")).fold(e => fail(s"$e"), identity)
    assertEquals(store.list().size, 1)
    assertEquals(store.list().head.metadata.name, "second")
  }

  /** §6: a scan recovers all the data that the library view needs. Thus no
    * index exists that can become different from the diagrams.
    */
  tmp.test("§6: the listing is derived from the diagrams on disk") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    for n <- List("c", "a", "b") do store.save(diagram(n, name = n)).fold(e => fail(s"$e"), identity)

    // A second store instance, sharing nothing but the directory — as gx and the
    // desktop do.
    val other = LibraryStore(dir)
    assertEquals(other.list().map(_.metadata.name), Vector("a", "b", "c"))
  }

  /** One corrupt diagram must not stop the user from opening the library.
    * Examples are a file that a killed process wrote only in part, or a file
    * from a future version.
    */
  tmp.test("a corrupt diagram is skipped, reported, and does not hide the others") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    store.save(diagram("good")).fold(e => fail(s"$e"), identity)
    Files.writeString(dir.resolve("diagrams").resolve("broken.json"), "{ not json")

    assertEquals(store.list().map(_.id.value), Vector("good"))
    assertEquals(store.unreadable().size, 1)
  }

  tmp.test("delete removes exactly one diagram and reports whether it did") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    store.save(diagram("one")).fold(e => fail(s"$e"), identity)
    store.save(diagram("two")).fold(e => fail(s"$e"), identity)
    assert(store.delete(DiagramId("one")))
    assert(!store.delete(DiagramId("one")))
    assertEquals(store.list().map(_.id.value), Vector("two"))
  }

  tmp.test("diagrams are found by origin, and one origin may back several") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val origin = FileOrigins.originOfPath("/tmp/shared.dot")
    val bound  = Some(Binding(origin, SyncMode.Pull, ContentHash.fromHex("aa"), 0L))
    store.save(diagram("one").copy(binding = bound)).fold(e => fail(s"$e"), identity)
    store.save(diagram("two").copy(binding = bound)).fold(e => fail(s"$e"), identity)
    store.save(diagram("three")).fold(e => fail(s"$e"), identity)

    assertEquals(store.findByOrigin(origin).map(_.id.value).sorted, Vector("one", "two"))
  }

  // ------------------------------------------------------------ folders

  tmp.test("folders containing diagrams need no declaration") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val work = FolderPath.parse("/work/architecture")
    store.save(diagram("one", folder = work)).fold(e => fail(s"$e"), identity)
    assert(store.folders().contains(work))
  }

  /** The only thing scanning cannot recover, which is exactly why folders.json
    * exists and why it holds nothing else.
    */
  tmp.test("an empty folder survives only because it is declared") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val empty = FolderPath.parse("/someday")
    store.saveFolders(Vector(empty)).fold(e => fail(s"$e"), identity)
    assert(store.folders().contains(empty))
  }

  tmp.test("losing folders.json costs organisation, never content") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val work = FolderPath.parse("/work")
    store.save(diagram("one", folder = work)).fold(e => fail(s"$e"), identity)
    store.saveFolders(Vector(FolderPath.parse("/empty"))).fold(e => fail(s"$e"), identity)

    Files.writeString(dir.resolve("folders.json"), "{{{ corrupt")
    assertEquals(store.list().size, 1, "a corrupt folders.json hid a diagram")
    assert(store.folders().contains(work), "a folder with content was lost")
  }

  test("folder paths collapse redundant separators, so two spellings are one folder") {
    assertEquals(FolderPath.parse("//work//arch/"), FolderPath.parse("/work/arch"))
    assertEquals(FolderPath.parse("/work/arch").render, "/work/arch")
    assertEquals(FolderPath.root.render, "/")
  }

  // ---------------------------------------------------------------- ids

  tmp.test("an id that would escape the diagrams directory cannot") { dir =>
    val store = LibraryStore(dir)
    store.initialize()
    val nasty = DiagramId("../../etc/passwd")
    store.save(diagram("x").copy(id = nasty)).fold(e => fail(s"$e"), identity)
    assertEquals(store.pathOf(nasty).getParent, dir.resolve("diagrams"))
    assert(store.get(nasty).isRight)
  }
