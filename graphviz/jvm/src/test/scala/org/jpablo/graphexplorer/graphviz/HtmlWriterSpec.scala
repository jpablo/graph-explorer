package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.html.*

/** The writer of HTML-like text labels.
  *
  * The Formatted mode of the label dialog edits an [[HtmlText]] and writes it
  * back as markup. The parser and the writer must agree: the markup that the
  * writer gives parses to the same text. The parser joins adjacent runs that
  * have the same font, so the checks compare texts with such runs joined.
  *
  * The writer gives one canonical form: a `<FONT>` with only the attributes
  * that the run sets, then `<B>`, `<I>`, `<U>`, `<O>`, `<S>`, and `<SUB>` or
  * `<SUP>` inside it, in this order. A `<BR/>` ends each line, with the
  * alignment of that line.
  */
class HtmlWriterSpec extends FunSuite:

  private def text(markup: String): HtmlText =
    HtmlParser.parse(markup) match
      case Some(HtmlLabel.Text(t)) => t
      case other                   => fail(s"setup: $markup is not a text label: $other")

  /** `t` with adjacent runs of the same font joined, and empty runs removed. */
  private def joined(t: HtmlText): HtmlText =
    HtmlText(t.spans.map { span =>
      val items = span.items.filter(_.str.nonEmpty).foldLeft(List.empty[HtmlItem]) {
        case (prev :: rest, item) if prev.font == item.font => prev.copy(str = prev.str + item.str) :: rest
        case (acc, item)                                     => item :: acc
      }
      span.copy(items = items.reverse)
    })

  private def roundTrip(t: HtmlText): Unit =
    val markup = HtmlWriter.write(t)
    HtmlParser.parse(markup) match
      case Some(HtmlLabel.Text(back)) => assertEquals(joined(back), joined(t), s"markup: $markup")
      case other                      => fail(s"the written markup does not parse as text: $markup -> $other")

  private val menlo16 = HtmlFont(size = Some(16), name = Some("Menlo"), color = Some("#104A41"))

  test("a run with a font and bold text gets one FONT with B inside"):
    val t = HtmlText(List(HtmlSpan(List(HtmlItem("Range.split[0, 3]", menlo16.copy(bold = true))), None)))
    assertEquals(
      HtmlWriter.write(t),
      """<FONT FACE="Menlo" POINT-SIZE="16" COLOR="#104A41"><B>Range.split[0, 3]</B></FONT>"""
    )

  test("a run with no font attributes gets no FONT"):
    val t = HtmlText(List(HtmlSpan(List(HtmlItem("plain", HtmlFont()), HtmlItem("bold", HtmlFont(bold = true))), None)))
    assertEquals(HtmlWriter.write(t), "plain<B>bold</B>")

  test("the style tags nest in one order"):
    val all = HtmlFont(bold = true, italic = true, underline = true, overline = true, strike = true, sub = true)
    val t   = HtmlText(List(HtmlSpan(List(HtmlItem("x", all)), None)))
    assertEquals(HtmlWriter.write(t), "<B><I><U><O><S><SUB>x</SUB></S></O></U></I></B>")

  test("a line break is a BR, and an alignment goes on the BR that ends its line"):
    val t = HtmlText(
      List(
        HtmlSpan(List(HtmlItem("left", HtmlFont())), Some(HtmlAlign.Left)),
        HtmlSpan(List(HtmlItem("centre", HtmlFont())), None),
        HtmlSpan(List(HtmlItem("right", HtmlFont())), Some(HtmlAlign.Right))
      )
    )
    assertEquals(HtmlWriter.write(t), """left<BR ALIGN="LEFT"/>centre<BR/>right<BR ALIGN="RIGHT"/>""")
    roundTrip(t)

  test("special characters are escaped"):
    val t = HtmlText(List(HtmlSpan(List(HtmlItem("""a < b & c > "d"""", HtmlFont())), None)))
    assertEquals(HtmlWriter.write(t), """a &lt; b &amp; c &gt; "d"""")
    roundTrip(t)

  test("a font size keeps its decimals only when it has them"):
    val t = HtmlText(List(HtmlSpan(List(HtmlItem("a", HtmlFont(size = Some(12))), HtmlItem("b", HtmlFont(size = Some(10.5)))), None)))
    assertEquals(HtmlWriter.write(t), """<FONT POINT-SIZE="12">a</FONT><FONT POINT-SIZE="10.5">b</FONT>""")

  test("the parser keeps an overline"):
    assertEquals(text("<O>x</O>").spans.head.items.head.font.overline, true)

  test("the label of the report survives a round trip"):
    roundTrip(
      text(
        """<FONT FACE="Menlo" POINT-SIZE="15" COLOR="#084B40"><B>Range.At(1, left, right)</B></FONT><BR/>""" +
          """<FONT FACE="Menlo" POINT-SIZE="12" COLOR="#14574E">Step 1</FONT><BR/>""" +
          """<FONT FACE="Helvetica Neue" POINT-SIZE="12"><I>returns</I> &minus;1</FONT>"""
      )
    )

  test("each text label of the corpus survives a round trip"):
    val dir   = java.io.File("graphviz/corpus")
    val files = Option(dir.listFiles()).getOrElse(Array.empty[java.io.File]).filter(_.getName.endsWith(".dot"))
    assume(files.nonEmpty, s"no corpus at ${dir.getAbsolutePath}")
    // A label of the form label=<...>, where the markup has no TABLE.
    val labelRe = "(?s)label\\s*=\\s*<(.*?)>\\s*[,;\\]]".r
    val labels = files.toList.flatMap(f => labelRe.findAllMatchIn(scala.io.Source.fromFile(f).mkString).map(_.group(1)))
    val texts  = labels.flatMap(m => HtmlParser.parse(m).collect { case HtmlLabel.Text(t) => t })
    assert(texts.size >= 5, s"only ${texts.size} text labels found in the corpus")
    texts.foreach(roundTrip)
