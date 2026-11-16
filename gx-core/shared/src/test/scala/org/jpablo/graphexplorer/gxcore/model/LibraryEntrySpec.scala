package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

/** The JSON shape of one entry in the metadata store.
  *
  * The shape is an API: the desktop app reads the same files in phase 3. Thus
  * the literal below is the contract, and a change to the model must not
  * change one byte of it.
  */
class LibraryEntrySpec extends FunSuite:

  private val path = "/Users/someone/Documents/Graph Explorer/arch.dot"

  private val representative = LibraryEntry(
    path = path,
    metadata = DiagramMetadata(
      hiddenElements = Set("node:b", "node:a"),
      collapsedGroups = Set("group:svc"),
      tags = List("infra", "systems/core"),
      notes = "a note",
      autoDetectFormat = Some(true)
    ),
    createdAt = 1700000000001L,
    updatedAt = 1700000000002L
  )

  private val representativeJson =
    """{"path":"/Users/someone/Documents/Graph Explorer/arch.dot","tags":["infra","systems/core"],"notes":"a note","hiddenElements":["node:a","node:b"],"collapsedGroups":["group:svc"],"autoDetectFormat":true,"createdAt":1700000000001,"updatedAt":1700000000002}"""

  /** Each key is present, also when its value is the default. */
  private val minimalJson =
    """{"path":"/Users/someone/Documents/Graph Explorer/arch.dot","tags":[],"notes":"","hiddenElements":[],"collapsedGroups":[],"autoDetectFormat":null,"createdAt":1,"updatedAt":2}"""

  test("the writer gives the pinned JSON"):
    assertEquals(upickle.default.write(representative), representativeJson)
    assertEquals(
      upickle.default.write(LibraryEntry(path, DiagramMetadata(), 1, 2)),
      minimalJson
    )

  test("the JSON has exactly the eight keys, in this order"):
    assertEquals(
      upickle.default.writeJs(representative).obj.keys.toList,
      List("path", "tags", "notes", "hiddenElements", "collapsedGroups", "autoDetectFormat", "createdAt", "updatedAt")
    )

  test("the pinned JSON reads back to the same entry"):
    assertEquals(upickle.default.read[LibraryEntry](representativeJson), representative)

  test("a read of the minimal JSON gives the empty metadata"):
    assertEquals(upickle.default.read[LibraryEntry](minimalJson), LibraryEntry(path, DiagramMetadata.empty, 1, 2))

  test("an entry with a selected renderer has the renderer key, after the eight keys"):
    val entry = LibraryEntry(path, DiagramMetadata(renderer = Some("mermaid")), 1, 2)
    val json  = upickle.default.write(entry)
    assertEquals(json, minimalJson.stripSuffix("}") + ""","renderer":"mermaid"}""")
    assertEquals(upickle.default.read[LibraryEntry](json), entry)

  test("an entry from before the renderer field reads as no selection, and a write gives the same bytes"):
    // `minimalJson` and `representativeJson` have no `renderer` key.
    val read = upickle.default.read[LibraryEntry](representativeJson)
    assertEquals(read.metadata.renderer, None)
    assertEquals(upickle.default.write(read), representativeJson)

  test("an entry with a selected extractor and a lock has the two keys, after the renderer key"):
    val entry = LibraryEntry(path, DiagramMetadata(renderer = Some("mermaid"), extractor = Some("dot"), locked = true), 1, 2)
    val json  = upickle.default.write(entry)
    assertEquals(json, minimalJson.stripSuffix("}") + ""","renderer":"mermaid","extractor":"dot","locked":true}""")
    assertEquals(upickle.default.read[LibraryEntry](json), entry)

  test("an entry with only a lock has only the locked key, after the eight keys"):
    val entry = LibraryEntry(path, DiagramMetadata(locked = true), 1, 2)
    val json  = upickle.default.write(entry)
    assertEquals(json, minimalJson.stripSuffix("}") + ""","locked":true}""")
    assertEquals(upickle.default.read[LibraryEntry](json), entry)

  test("an entry from before the extractor and the lock reads as no selection and not locked, and a write gives the same bytes"):
    val read = upickle.default.read[LibraryEntry](representativeJson)
    assertEquals(read.metadata.extractor, None)
    assertEquals(read.metadata.locked, false)
    assertEquals(upickle.default.write(read), representativeJson)

  test("the location of an entry is the canonical file URI of its path"):
    val entry = LibraryEntry("/Users/someone/Documents/Graph Explorer/a b.dot", DiagramMetadata.empty, 1, 2)
    assertEquals(entry.location.value, "file:///Users/someone/Documents/Graph%20Explorer/a%20b.dot")
    // The key of the entry is the hash of this text.
    assertEquals(entry.location.value, LibraryEntry.keySource(entry.path))

  test("a read ignores a name and a folder in the file, and a write does not give them back"):
    val withName = minimalJson.replace("\"tags\":[]", "\"name\":\"Architecture\",\"folder\":{\"segments\":[\"x\"]},\"tags\":[]")
    val read     = upickle.default.read[LibraryEntry](withName)
    assertEquals(read, LibraryEntry(path, DiagramMetadata.empty, 1, 2))
    assertEquals(upickle.default.write(read), minimalJson)

  test("the base name drops only the last extension, for each separator"):
    assertEquals(LibraryEntry.nameOf("/a/b/arch.dot"), "arch")
    assertEquals(LibraryEntry.nameOf("/a/b/archive.v2.mmd"), "archive.v2")
    assertEquals(LibraryEntry.nameOf("/a/b/no-extension"), "no-extension")
    assertEquals(LibraryEntry.nameOf("/a/b/.hidden"), ".hidden")
    assertEquals(LibraryEntry.nameOf("""C:\Users\x\arch.dot"""), "arch")

  test("the key source is the canonical file URI of the path"):
    // The desktop computes the same key in phase 3: it gets the canonical
    // path from its shell (V-13), makes the URI with this shared function,
    // and hashes the UTF-8 bytes with `hash_text`.
    assertEquals(
      LibraryEntry.keySource("/Users/someone/Documents/Graph Explorer/a b.dot"),
      "file:///Users/someone/Documents/Graph%20Explorer/a%20b.dot"
    )
    assertEquals(LibraryEntry.keySource("/Users/someone/café.dot"), "file:///Users/someone/caf%C3%A9.dot")

  test("the two spellings of a Windows path give one key source"):
    assertEquals(LibraryEntry.keySource("""C:\Users\x\a.dot"""), "file:///C:/Users/x/a.dot")
    assertEquals(LibraryEntry.keySource("C:/Users/x/a.dot"), "file:///C:/Users/x/a.dot")
