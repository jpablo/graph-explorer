package org.jpablo.graphexplorer.gxcore.fs

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/** A file holds its own text: `sameOnDisk(file, file)` is true for each file.
  *
  * The desktop page asks `sameOnDisk` (through `Session.holds`) if the editor
  * holds an edit that the file does not have. When a file opens, the editor
  * has the text of the file. Thus `sameOnDisk(file, file)` false makes a file
  * dirty that nobody changed. Then a change from a different writer, for
  * example `gx tag`, becomes a conflict, and the viewer does not take it.
  *
  * Before, `sameOnDisk` compared `savedOver(file, text)` with `file`.
  * `savedOver` gives the whole text ONE line ending. Thus a file with mixed
  * line endings (a generator that adds LF lines to a CRLF file) was not the
  * same as itself. A file that ends with a lone CR was not the same as
  * itself either, because `withEndOf` removes that CR.
  */
class SameOnDiskSpec extends ScalaCheckSuite:

  /** Short texts with many line endings, mixed and lone. */
  private val texts: Gen[String] =
    Gen.listOfN(12, Gen.oneOf("a", "b", "\n", "\r\n", "\r")).map(_.mkString)

  property("each file holds its own text"):
    Prop.forAll(texts)(text => LineEnding.sameOnDisk(text, text))

  test("a file with mixed line endings holds its own text"):
    assert(LineEnding.sameOnDisk("a\nb\r\nc\r\n", "a\nb\r\nc\r\n"))

  test("a file that ends with a lone CR holds its own text"):
    assert(LineEnding.sameOnDisk("a\r", "a\r"))

  test("a file with mixed line endings holds the LF form of its text"):
    // The doc of `sameOnDisk` says that the two texts can differ in the line
    // endings. An editor that changes each CRLF to LF holds no edit.
    assert(LineEnding.sameOnDisk("a\nb\r\nc\r\n", "a\nb\nc\n"))

  test("a different text is still a different text"):
    assert(!LineEnding.sameOnDisk("a\nb\r\nc\r\n", "a\nb\r\nd\r\n"))
    assert(!LineEnding.sameOnDisk("a\r", "b\r"))
