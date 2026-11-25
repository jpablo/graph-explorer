package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.html.HtmlParser

/** A label that does not parse gives a message that names the problem.
  *
  * The label dialog shows this message below the source, while the user types.
  * Before, the parser gave only "no label", and the user saw the error only on
  * the canvas.
  */
class HtmlParseErrorSpec extends FunSuite:

  private def error(markup: String): String =
    HtmlParser.parseOrError(markup) match
      case Left(message) => message
      case Right(label)  => fail(s"the markup parsed: $label")

  test("a valid label gives the same label as parse"):
    val markup = """<FONT FACE="Menlo" POINT-SIZE="16"><B>Range.split[0, 3]</B></FONT><BR/>middle"""
    assertEquals(HtmlParser.parseOrError(markup), HtmlParser.parse(markup).toRight("no label"))

  test("a tag that is not closed is named"):
    val message = error("<B>bold text")
    assert(message.toLowerCase.contains("<b>"), s"the message does not name <B>: $message")

  test("a closing tag that does not match is named, with the open tag"):
    val message = error("<B>bold</I>")
    val lower   = message.toLowerCase
    assert(lower.contains("<b>") && lower.contains("</i>"), s"the message does not name <B> and </I>: $message")

  test("an unknown tag is named"):
    val message = error("<BLINK>x</BLINK>")
    assert(message.toLowerCase.contains("blink"), s"the message does not name BLINK: $message")
