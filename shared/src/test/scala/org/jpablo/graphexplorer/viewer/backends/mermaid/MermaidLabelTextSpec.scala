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

  // Real Mermaid changes `#name;` into `ﬂ°name¶ß` and `#123;` into `ﬂ°°123¶ß`
  // before it parses (`encodeEntities`), and the parser results keep that code.

  test("decodeEntityCodes gives back a quote for the code of #quot;"):
    assertEquals(MermaidSourceScan.decodeEntityCodes("say ﬂ°quot¶ßhiﬂ°quot¶ß"), "say \"hi\"")

  test("decodeEntityCodes gives back the source form of each other code"):
    assertEquals(MermaidSourceScan.decodeEntityCodes("a ﬂ°amp¶ß b ﬂ°°35¶ß"), "a #amp; b #35;")

  test("decodeEntityCodes keeps a text with no code"):
    for text <- List("plain", "Stage [beta]", "a $1 \\ b", "#quot", "") do
      assertEquals(MermaidSourceScan.decodeEntityCodes(text), text, s"text: $text")

  test("decodeQuotes is the inverse of encodeQuotes"):
    for label <- List("say \"hi\"", "\"core\"", "no quote", "\"\"") do
      val encoded = MermaidSourceScan.encodeQuotes(label)
      assert(!encoded.contains("\""), s"encoded: $encoded")
      assertEquals(MermaidSourceScan.decodeQuotes(encoded), label)
