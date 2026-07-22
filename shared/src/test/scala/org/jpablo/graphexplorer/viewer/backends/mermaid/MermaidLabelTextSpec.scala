package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidLabelTextSpec extends FunSuite:

  // Stored form is DOT-escaped: the 2-char sequence \n is a line break, \\ a literal backslash.

  test("fromStored converts the stored line-break escape to <br/>"):
    assertEquals(MermaidLabelText.fromStored("a\\nb"), "a<br/>b")

  test("fromStored converts justified line breaks (\\l, \\r) to <br/>"):
    assertEquals(MermaidLabelText.fromStored("a\\lb\\rc"), "a<br/>b<br/>c")

  test("fromStored renders an escaped backslash as a literal backslash (no line break)"):
    // Stored a\\nb is the literal text a\nb — must NOT become a line break
    assertEquals(MermaidLabelText.fromStored("a\\\\nb"), "a\\nb")

  test("fromStored passes unknown escapes through untouched"):
    assertEquals(MermaidLabelText.fromStored("a\\Nb\\"), "a\\Nb\\")

  test("toStored converts <br/> variants to the stored line-break escape"):
    assertEquals(MermaidLabelText.toStored("a<br/>b"), "a\\nb")
    assertEquals(MermaidLabelText.toStored("a<br>b"), "a\\nb")
    assertEquals(MermaidLabelText.toStored("a<br />b"), "a\\nb")
    assertEquals(MermaidLabelText.toStored("a<BR/>b"), "a\\nb")

  test("toStored escapes literal backslashes so verbatim \\n text stays verbatim"):
    assertEquals(MermaidLabelText.toStored("a\\nb"), "a\\\\nb")

  test("toStored and fromStored are inverses on stored labels"):
    for stored <- List("a\\nb", "a\\\\nb", "plain", "x\\ny\\\\z", "\\n") do
      assertEquals(MermaidLabelText.toStored(MermaidLabelText.fromStored(stored)), stored, s"stored: $stored")
