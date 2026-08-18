package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.file.{Files, Paths}

/** The bytes `gx` actually writes, read back by the shared codec.
  *
  * `local-protocol/fixtures/library-record.json` was produced by running the
  * real CLI — `gx import`, then `gx run demo hide` — against a scratch library.
  * Under D7.3 the webview reads these same bytes, so what this pins is that a
  * record is readable by something other than the code that wrote it.
  *
  * The interesting part is what the file does NOT contain. upickle omits
  * defaults, so `metadata` carries only `hiddenElements`; `tags`, `notes` and
  * `autoDetectFormat` are absent entirely. A reader that required them would
  * pass every synthetic test and fail on the first real library.
  */
class RealRecordSpec extends FunSuite:

  private val fixture =
    Paths.get("..").toAbsolutePath.getParent.resolve("local-protocol/fixtures/library-record.json")

  private lazy val text = Files.readString(fixture)

  test("a record written by the real gx parses"):
    val d = upickle.default.read[Diagram](text)
    assertEquals(d.id, DiagramId("demo"))
    assertEquals(d.name, "demo")
    assertEquals(d.format, "DOT")
    assert(d.text.startsWith("digraph Demo"), d.text)
    assert(d.isBound, "gx import binds the record to its origin")

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
