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
