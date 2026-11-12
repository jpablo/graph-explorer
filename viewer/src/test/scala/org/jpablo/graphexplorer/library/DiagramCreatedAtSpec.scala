package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.scalajs.js

/** The index keeps the creation time of each diagram.
  *
  * `DiagramSummary.createdAt` has the default `System.currentTimeMillis()`.
  * upickle 4 omits a field that has the value of its default, and it runs the
  * default again at each write. It also fills a missing field from the
  * default when it reads.
  */
class DiagramCreatedAtSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DiagramCreatedAtSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** Run `block` with `Date.now` stopped at `ms`. Scala.js gives
    * `System.currentTimeMillis()` from `Date.now()`.
    */
  private def atTime[A](ms: Double)(block: => A): A =
    val date = js.Dynamic.global.Date
    val real = date.now
    date.now = (() => ms): js.Function0[Double]
    try block
    finally date.now = real

  test("a new diagram keeps its creation time when the index is read later"):
    // Related 2. `createEmptyDiagram` writes the index in the millisecond of
    // the creation. Before the fix, the default of `createdAt` was the time,
    // so `createdAt` had the value of its default, and the index did not
    // store it. Each later read gave the time of the read: the "Created" date
    // and the sort by creation were wrong. The clock stops for the creation,
    // so the write is in that millisecond, as in the app. Before the fix,
    // this test failed at the check.
    val createdAt = 1_700_000_000_000L
    val readAt    = createdAt + 3_600_000L
    val id        = atTime(createdAt.toDouble)(BrowserLibrary.createEmptyDiagram("Created"))
    val obtained  = atTime(readAt.toDouble)(BrowserLibrary.indexNow().diagrams.find(_.id == id).map(_.createdAt))
    same(obtained, Some(createdAt), s"the index does not keep the creation time. Stored entry: ${storedEntry(id.value)}")

  test("an entry of an old index with no createdAt reads as unknown, and shows lastModified"):
    // Related 2. An index from before the fix has entries with no `createdAt`.
    // Such an entry must read with a stable value, and the "Created" date and
    // the sort by creation use `lastModified`. Before the fix, this test
    // failed: `createdAt` was the time of the read.
    val old = upickle.default.read[LibraryIndex]("""{"projects":[{"id":{"value":"old"},"name":"Old","lastModified":200}]}""")
    val entry = old.diagrams.head
    same(entry.createdAt, 0L, "an entry with no createdAt does not read as unknown")
    same(entry.creationTime, 200L, "the creation time of an entry with no createdAt is not lastModified")
    same(upickle.default.write(old), """{"projects":[{"id":{"value":"old"},"name":"Old","lastModified":200}]}""", "the JSON shape changed")

  /** The raw JSON of the index entry of a diagram, as `localStorage` has it. */
  private def storedEntry(id: String): Option[String] =
    Option(dom.window.localStorage.getItem("[StoredString]graph-explorer.projects"))
      .flatMap(raw => ujson.read(raw)("projects").arr.find(_("id")("value").str == id))
      .map(ujson.write(_))
