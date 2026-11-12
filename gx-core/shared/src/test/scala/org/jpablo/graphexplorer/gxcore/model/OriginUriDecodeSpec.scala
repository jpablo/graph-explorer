package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

import scala.util.Try

/** `filePath` must decode each URI that `parse` accepts, and it must not
  * throw.
  *
  * `parse` accepts each text that starts with `file://`: a URI that this
  * encoder made, and a URI from an old record or from the wire. The decoder
  * read the two characters after each `%` as hex with `Integer.parseInt`. A
  * `%` that does not start an escape (a file `50% done.dot` from a writer that
  * did not encode it) then threw `NumberFormatException`. The decoder also
  * encoded each UTF-16 unit of a character that was not encoded by itself. A
  * character outside the BMP is two units, so it became `??`.
  */
class OriginUriDecodeSpec extends FunSuite:

  private def pathOf(raw: String): Option[String] =
    OriginUri.parse(raw).toOption.flatMap(_.filePath)

  private def decoded(raw: String): Option[String] =
    Try(pathOf(raw)).fold(e => fail(s"filePath of '$raw' threw $e"), identity)

  test("a '%' that does not start an escape stays in the path"):
    assertEquals(decoded("file:///tmp/50% done.dot"), Some("/tmp/50% done.dot"))

  test("a '%' before two characters that are not hex stays in the path"):
    assertEquals(decoded("file:///tmp/a%zz.dot"), Some("/tmp/a%zz.dot"))

  test("a '%' before a sign stays in the path"):
    // Integer.parseInt accepts a sign, so `%+1` decoded to the byte 0x01.
    assertEquals(decoded("file:///tmp/a%+1.dot"), Some("/tmp/a%+1.dot"))

  test("a character outside the BMP that is not encoded stays the same"):
    assertEquals(decoded("file:///tmp/😀.dot"), Some("/tmp/😀.dot"))

  test("a valid escape still decodes"):
    assertEquals(decoded("file:///tmp/50%25%20done.dot"), Some("/tmp/50% done.dot"))
