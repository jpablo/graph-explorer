package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.FileOrigins
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class MetadataStoreSpec extends FunSuite:

  /** A temporary directory with an empty `metadata/` in it. */
  private val tmp = FunFixture[Path](
    setup = _ =>
      val dir = Files.createTempDirectory("gx-core-metadata").toRealPath()
      Files.createDirectories(dir.resolve("metadata"))
      dir
    ,
    teardown = dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def storeIn(dir: Path) = MetadataStore(dir.resolve("metadata"))

  private def entry(path: String, tags: List[String] = Nil) =
    LibraryEntry(
      path = path,
      metadata = DiagramMetadata(tags = tags, hiddenElements = Set("node:a")),
      createdAt = 1L,
      updatedAt = 2L
    )

  private def jsonFiles(root: Path): Vector[String] =
    Files.list(root).iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".json")).toVector.sorted

  tmp.test("an entry round trips through the disk"): dir =>
    val store = storeIn(dir)
    val e     = entry("/work/arch.dot", List("infra"))
    assertEquals(store.put(e), Right(e))
    assertEquals(store.get("/work/arch.dot"), Right(e))
    assert(store.contains("/work/arch.dot"))

  tmp.test("an entry file has no name and no folder"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/arch.dot")).fold(err => fail(s"$err"), identity)
    val json = ujson.read(Files.readString(Files.list(dir.resolve("metadata")).iterator.asScala.next())).obj
    assert(!json.contains("name") && !json.contains("folder"), json.keys.toString)

  tmp.test("a missing entry is NotFound, not an exception"): dir =>
    assertEquals(storeIn(dir).get("/work/none.dot"), Left(MetadataError.NotFound("/work/none.dot")))

  tmp.test("a second put changes the entry in place"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/a.dot", List("one"))).fold(err => fail(s"$err"), identity)
    store.put(entry("/work/a.dot", List("two"))).fold(err => fail(s"$err"), identity)
    assertEquals(store.list().map(_.metadata.tags), Vector(List("two")))
    assertEquals(jsonFiles(dir.resolve("metadata")).size, 1)

  tmp.test("list gives each entry, in the order of the paths"): dir =>
    val store = storeIn(dir)
    for p <- List("/work/b.dot", "/work/a.dot", "/other/c.mmd") do store.put(entry(p)).fold(err => fail(s"$err"), identity)
    assertEquals(store.list().map(_.path), Vector("/other/c.mmd", "/work/a.dot", "/work/b.dot"))

  tmp.test("remove takes out one entry, and says if it did"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    store.put(entry("/work/b.dot")).fold(err => fail(s"$err"), identity)
    assertEquals(store.remove("/work/a.dot"), Right(true))
    assertEquals(store.remove("/work/a.dot"), Right(false))
    assertEquals(store.list().map(_.path), Vector("/work/b.dot"))

  tmp.test("an entry that does not parse is reported, and the other entries stay visible"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    val bad = dir.resolve("metadata").resolve(MetadataStore.fileNameOf("/work/bad.dot"))
    Files.writeString(bad, """{"path": "/work/bad.dot", "tags": [""", StandardCharsets.UTF_8)
    assertEquals(store.list().map(_.path), Vector("/work/a.dot"))
    assertEquals(store.unreadable().map(_.file), Vector(bad.getFileName.toString))
    store.get("/work/bad.dot") match
      case Left(MetadataError.Unreadable(_, _)) => ()
      case other                                => fail(s"expected Unreadable, got $other")

  tmp.test("an entry under a file name that is not the key of its path is reported, not listed"): dir =>
    // Only a person who copies a file by hand makes this. If the store
    // listed it, `get` of its path would not find it.
    val store = storeIn(dir)
    val json  = upickle.default.write(entry("/work/a.dot"))
    Files.writeString(dir.resolve("metadata").resolve(MetadataStore.fileNameOf("/work/z.dot")), json, StandardCharsets.UTF_8)
    assertEquals(store.list(), Vector.empty)
    assertEquals(store.unreadable().size, 1)
    assertEquals(store.get("/work/a.dot"), Left(MetadataError.NotFound("/work/a.dot")))
    store.get("/work/z.dot") match
      case Left(MetadataError.Unreadable(_, why)) => assert(why.contains("/work/a.dot"), why)
      case other                                  => fail(s"expected Unreadable, got $other")

  tmp.test("files that are not entries are ignored"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    Files.writeString(dir.resolve("metadata").resolve(".something.json.tmp"), "partial", StandardCharsets.UTF_8)
    Files.writeString(dir.resolve("metadata").resolve("README"), "x", StandardCharsets.UTF_8)
    assertEquals(store.list().map(_.path), Vector("/work/a.dot"))
    assertEquals(store.unreadable(), Vector.empty)

  tmp.test("the file on the disk has exactly the eight keys"): dir =>
    val store = storeIn(dir)
    store.put(entry("/work/a.dot")).fold(err => fail(s"$err"), identity)
    val text = Files.readString(dir.resolve("metadata").resolve(MetadataStore.fileNameOf("/work/a.dot")), StandardCharsets.UTF_8)
    assertEquals(
      ujson.read(text).obj.keys.toList,
      List("path", "tags", "notes", "hiddenElements", "collapsedGroups", "autoDetectFormat", "createdAt", "updatedAt")
    )

  tmp.test("a put refuses a store that does not exist"): dir =>
    // The migration makes `metadata/`, and it runs only while `metadata/`
    // does not exist. If a put made the directory, the migration would never
    // run, and the old library would stay out of sight.
    val store = MetadataStore(dir.resolve("absent"))
    assert(store.put(entry("/work/a.dot")).isLeft)
    assert(!Files.exists(dir.resolve("absent")))
    assertEquals(store.list(), Vector.empty)

  // ------------------------------------------------ the key

  test("the file name is the hex SHA-256 of the canonical file URI"):
    // printf '%s' 'file:///Users/someone/Documents/Graph%20Explorer/a%20b.dot' | shasum -a 256
    assertEquals(
      MetadataStore.keyOf("/Users/someone/Documents/Graph Explorer/a b.dot"),
      "80bbd4551f39abd1f785d1d4d2edcd09d29becd95916c85d4b178174a63bb990"
    )
    // printf '%s' 'file:///Users/someone/caf%C3%A9.dot' | shasum -a 256
    assertEquals(
      MetadataStore.keyOf("/Users/someone/café.dot"),
      "57016146a5c94e464f005e670f2d0705e6bda2741b41a6f91ee37a927325bf47"
    )
    assertEquals(
      MetadataStore.fileNameOf("/Users/someone/café.dot"),
      "57016146a5c94e464f005e670f2d0705e6bda2741b41a6f91ee37a927325bf47.json"
    )

  test("the two spellings of a Windows path give one key"):
    // printf '%s' 'file:///C:/Users/x/a.dot' | shasum -a 256
    val key = "0203b38420e4fe0dfe5d4f83e27c038131457eafe9e464e3ba4d745d72960d99"
    assertEquals(MetadataStore.keyOf("""C:\Users\x\a.dot"""), key)
    assertEquals(MetadataStore.keyOf("C:/Users/x/a.dot"), key)

  tmp.test("the key is the key of the origin that FileOrigins makes, for each spelling of a file"): dir =>
    val real = Files.createDirectories(dir.resolve("real"))
    val file = real.resolve("a.dot")
    Files.writeString(file, "digraph { a }")
    Files.createSymbolicLink(dir.resolve("link"), real)

    val viaLink   = FileOrigins.canonicalize(dir.resolve("link/a.dot"), dir).toString
    val viaDots   = FileOrigins.canonicalize(dir.resolve("real/../real/./a.dot"), dir).toString
    val uriSha256 = org.jpablo.graphexplorer.gxcore.fs.Hashing
      .ofBytes(FileOrigins.originOf(file, dir).value.getBytes(StandardCharsets.UTF_8))
      .hex
    assertEquals(MetadataStore.keyOf(viaLink), uriSha256)
    assertEquals(MetadataStore.keyOf(viaDots), uriSha256)

    val store = storeIn(dir)
    store.put(entry(viaLink)).fold(err => fail(s"$err"), identity)
    assertEquals(jsonFiles(dir.resolve("metadata")), Vector(s"$uriSha256.json"))
