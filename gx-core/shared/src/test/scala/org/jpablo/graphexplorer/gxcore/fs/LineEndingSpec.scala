package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

/** V-04 as one rule for each writer: a save keeps the line endings of the file
  * and its end (a final newline, or none). `gx` applies it in
  * `Documents.write`, and the desktop page applies it in `FilePersistence`.
  * The rule is shared, so the two cannot disagree.
  */
class LineEndingSpec extends FunSuite:

  test("a save over a CRLF file writes CRLF, and keeps the final CRLF"):
    assertEquals(LineEnding.savedOver("a\r\nb\r\n", "a\nc"), "a\r\nc\r\n")

  test("a save over a file with no final newline writes none"):
    assertEquals(LineEnding.savedOver("a\nb", "a\nc\n\n"), "a\nc")

  test("a save over an LF file with a final newline keeps it"):
    assertEquals(LineEnding.savedOver("a\nb\n", "a\r\nc"), "a\nc\n")

  test("a text that differs only in the line endings and the end is the same on the disk"):
    assert(LineEnding.sameOnDisk("a\r\nb\r\n", "a\nb"))
    assert(LineEnding.sameOnDisk("a\nb", "a\nb\n"))
    assert(LineEnding.sameOnDisk("a\nb\n", "a\nb\n"))
    assert(!LineEnding.sameOnDisk("a\r\nb\r\n", "a\nc"))
