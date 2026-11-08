package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.DiagramId
import upickle.default.{read, write}

/** The browser library keeps the index in `localStorage` under
  * `graph-explorer.projects`. The JSON key of the list is `projects`, and the
  * Scala field is `diagrams`. This spec pins the stored shape, because a
  * different key loses the library of a person.
  */
class LibraryIndexSpec extends FunSuite:

  private val oldFormat =
    """{"projects":[{"id":{"value":"abc"},"name":"Groups","lastModified":200,"createdAt":100}]}"""

  private val index =
    LibraryIndex(List(DiagramSummary(DiagramId("abc"), "Groups", lastModified = 200, createdAt = 100)))

  test("the index is written with the key projects"):
    val json = write(index)
    assertEquals(json, oldFormat)
    assert(!json.contains("\"diagrams\""), s"the key diagrams must not appear: $json")

  test("an index in the stored format is read back"):
    assertEquals(read[LibraryIndex](oldFormat), index)

  test("an empty stored index is read back as an empty index"):
    assertEquals(read[LibraryIndex]("""{"projects":[]}"""), LibraryIndex())
