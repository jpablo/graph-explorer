package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite

import java.nio.file.{Files, Paths}

/** The migration reads the bytes that the old `gx` really wrote.
  *
  * The real CLI made `local-protocol/fixtures/library-record.json`. The steps
  * were `gx import`, then `gx run demo hide`, on a temporary library. The
  * migration of the JVM and of the desktop reads these same bytes. Thus this
  * spec makes sure that the reader of an old record reads a real one, not only
  * the one that a test wrote.
  *
  * The important part is what the file does NOT contain. upickle omits
  * defaults, so `metadata` holds only `hiddenElements`. `tags`, `notes` and
  * `autoDetectFormat` are not in the file at all. A reader that requires them
  * passes each synthetic test and fails on the first real library.
  */
class RealRecordSpec extends FunSuite:

  private val fixture =
    Paths.get("..").toAbsolutePath.getParent.resolve("local-protocol/fixtures/library-record.json")

  private lazy val text = Files.readString(fixture)

  private def record: LegacyRecord = LegacyRecord.read(text).fold(e => fail(e), identity)

  test("a record written by the real gx parses"):
    val r = record
    assertEquals(r.id, "demo")
    assertEquals(r.metadata.name, "demo")
    assertEquals(r.format, "DOT")
    assert(r.text.startsWith("digraph Demo"), r.text)
    assertEquals(r.origin, Some("file:///private/tmp/gx-fixture/demo.dot"), "gx import binds the record to its origin")

  test("the view state a headless `gx run hide` wrote is there, in ElementRef spelling"):
    assertEquals(record.metadata.hiddenElements, Set("node:db"))

  test("metadata fields the file omits read as their defaults, not as failures"):
    // This is the whole reason the fixture is a real file rather than a
    // hand-written one: upickle writes no key for a defaulted field.
    assert(!text.contains("autoDetectFormat"), "the fixture must actually omit them")
    assert(!text.contains("\"tags\""), "the fixture must actually omit them")
    assertEquals(record.metadata.tags, Nil)
    assertEquals(record.metadata.notes, "")
    assertEquals(record.metadata.autoDetectFormat, None)
