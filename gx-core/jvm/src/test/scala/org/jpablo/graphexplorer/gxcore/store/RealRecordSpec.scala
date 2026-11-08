package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

import java.nio.file.{Files, Paths}

/** The shared codec reads the bytes that `gx` really writes.
  *
  * The real CLI made `local-protocol/fixtures/library-record.json`. The steps
  * were `gx import`, then `gx run demo hide`, on a temporary library. Under
  * D7.3, the webview reads these same bytes. Thus this spec makes sure that
  * other code can read a diagram, not only the code that wrote it.
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

  test("a diagram written by the real gx parses"):
    val d = upickle.default.read[Diagram](text)
    assertEquals(d.id, DiagramId("demo"))
    assertEquals(d.metadata.name, "demo")
    assertEquals(d.format, "DOT")
    assert(d.text.startsWith("digraph Demo"), d.text)
    assert(d.isBound, "gx import binds the diagram to its origin")

  test("the view state a headless `gx run hide` wrote is there, in ElementRef spelling"):
    val d = upickle.default.read[Diagram](text)
    assertEquals(d.metadata.hiddenElements, Set("node:db"))

  test("metadata fields the file omits read as their defaults, not as failures"):
    // This is the whole reason the fixture is a real file rather than a
    // hand-written one: upickle writes no key for a defaulted field.
    assert(!text.contains("autoDetectFormat"), "the fixture must actually omit them")
    assert(!text.contains("\"tags\""), "the fixture must actually omit them")
    val d = upickle.default.read[Diagram](text)
    assertEquals(d.metadata.tags, Nil)
    assertEquals(d.metadata.notes, "")
    assertEquals(d.metadata.autoDetectFormat, None)

  test("re-serialising keeps every field the original had"):
    val d = upickle.default.read[Diagram](text)
    assertEquals(upickle.default.read[Diagram](upickle.default.write(d)), d)
