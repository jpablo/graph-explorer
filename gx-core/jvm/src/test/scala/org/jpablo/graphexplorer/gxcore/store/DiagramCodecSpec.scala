package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

/** The JSON shape of a diagram on the disk.
  *
  * The shape is an API: older builds of `gx` and of the desktop read the same
  * library. The two literals below came from the codec of the time when `name`
  * and `folder` were fields of `Diagram`. A change to the model must not change
  * one byte.
  */
class DiagramCodecSpec extends FunSuite:

  private val origin = OriginUri.parse("file:///tmp/gx/arch.dot").fold(e => fail(e), identity)

  private val representative = Diagram(
    id = DiagramId("arch"),
    format = "DOT",
    text = "digraph G {\n  a -> b\n}\n",
    binding = Some(Binding(origin, SyncMode.Sync, ContentHash.fromHex("ab12"), 1700000000000L)),
    metadata = DiagramMetadata(
      name = "Architecture",
      folder = FolderPath(List("systems", "core")),
      hiddenElements = Set("node:a"),
      tags = List("infra", "draft"),
      autoDetectFormat = Some(true)
    ),
    createdAt = 1700000000001L,
    updatedAt = 1700000000002L
  )

  /** The old writer gave this for `representative`. */
  private val representativeJson =
    """{"id":{"value":"arch"},"name":"Architecture","folder":{"segments":["systems","core"]},"format":"DOT","text":"digraph G {\n  a -> b\n}\n","binding":{"origin":"file:///tmp/gx/arch.dot","mode":"Sync","baseHash":"ab12","lastSyncAt":1700000000000},"metadata":{"hiddenElements":["node:a"],"tags":["infra","draft"],"autoDetectFormat":true},"createdAt":1700000000001,"updatedAt":1700000000002}"""

  /** A diagram in the root folder, with no binding and only default metadata. */
  private val minimal =
    representative.copy(binding = None, metadata = DiagramMetadata(name = "Architecture"))

  /** The old writer gave this for `minimal`. The root folder has a key, because
    * the old `Diagram` had no default for `folder`.
    */
  private val minimalJson =
    """{"id":{"value":"arch"},"name":"Architecture","folder":{"segments":[]},"format":"DOT","text":"digraph G {\n  a -> b\n}\n","binding":null,"metadata":{},"createdAt":1700000000001,"updatedAt":1700000000002}"""

  test("the writer gives the same JSON as the old writer"):
    assertEquals(upickle.default.write(representative), representativeJson)
    assertEquals(upickle.default.write(minimal), minimalJson)

  test("name and folder stay at the top level, and metadata has neither key"):
    val json = upickle.default.writeJs(representative).obj
    assertEquals(
      json.keys.toList,
      List("id", "name", "folder", "format", "text", "binding", "metadata", "createdAt", "updatedAt")
    )
    assertEquals(json("name").str, "Architecture")
    assertEquals(json("folder")("segments").arr.map(_.str).toList, List("systems", "core"))
    val metadata = json("metadata").obj
    assert(!metadata.contains("name"), s"metadata has a name key: $metadata")
    assert(!metadata.contains("folder"), s"metadata has a folder key: $metadata")

  test("an old file reads back to the same diagram"):
    assertEquals(upickle.default.read[Diagram](representativeJson), representative)
    assertEquals(upickle.default.read[Diagram](minimalJson), minimal)

  test("an old file gives the name and the folder to the metadata, and the defaults to the rest"):
    val d = upickle.default.read[Diagram](minimalJson)
    assertEquals(
      d.metadata,
      DiagramMetadata(
        name = "Architecture",
        folder = FolderPath.root,
        hiddenElements = Set.empty,
        collapsedGroups = Set.empty,
        tags = Nil,
        notes = "",
        autoDetectFormat = None
      )
    )
    assertEquals(
      upickle.default.read[Diagram](representativeJson).metadata,
      DiagramMetadata(
        name = "Architecture",
        folder = FolderPath(List("systems", "core")),
        hiddenElements = Set("node:a"),
        tags = List("infra", "draft"),
        autoDetectFormat = Some(true)
      )
    )

  test("an element ref that this build does not know survives a round trip"):
    // A newer `gx` can write a kind of ref that this build cannot parse. The
    // codec keeps the string as it is, so a save here does not erase it.
    val future = representative.copy(
      metadata = representative.metadata.copy(
        hiddenElements = Set("node:a", "future:x"),
        collapsedGroups = Set("cluster:y")
      )
    )
    val back = upickle.default.read[Diagram](upickle.default.write(future))
    assertEquals(back.metadata.hiddenElements, Set("node:a", "future:x"))
    assertEquals(back.metadata.collapsedGroups, Set("cluster:y"))
