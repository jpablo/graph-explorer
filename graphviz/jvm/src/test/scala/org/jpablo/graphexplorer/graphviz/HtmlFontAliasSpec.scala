package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

/** A `<b>` or `<i>` run in an HTML label, with a PostScript font name that
  * already gives the weight or the style.
  *
  * The font name `Helvetica-Bold` gives `font-weight="bold"`. Before, the
  * `<b>` run wrote `font-weight="bold"` again. An attribute that occurs two
  * times in one element makes the SVG an XML document that is not
  * well-formed. The viewer cannot parse such an SVG, and the canvas stays
  * empty. Graphviz (`svg_textspan`) writes the weight of `<b>` only when the
  * font gives no weight, and the style of `<i>` only when the font gives no
  * style.
  */
class HtmlFontAliasSpec extends FunSuite:

  /** The `<text>` elements of the SVG of `src`. */
  private def texts(src: String): List[String] =
    val r = Graphviz.renderFormats(src, Seq("svg"))
    assertEquals(r.status, "success", r.errors.toString)
    "<text[^>]*>".r.findAllIn(r.output("svg")).toList

  /** The attribute names that occur more than one time in `tag`. */
  private def repeated(tag: String): List[String] =
    """\s([a-zA-Z:-]+)=""".r.findAllMatchIn(tag).map(_.group(1)).toList
      .groupBy(identity).collect { case (n, l) if l.size > 1 => n }.toList.sorted

  private def assertNoRepeats(src: String): Unit =
    val bad = texts(src).filter(repeated(_).nonEmpty)
    assert(bad.isEmpty, s"elements with an attribute two times:\n${bad.mkString("\n")}")

  /** The SVG must parse as XML, as it does in the viewer. */
  private def assertWellFormed(src: String): Unit =
    val svg = Graphviz.renderFormats(src, Seq("svg")).output("svg")
    val f   = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(svg.getBytes("UTF-8")))

  private val boldGroup =
    """digraph G {
      |  subgraph cluster_a {
      |    fontname="Helvetica-Bold";
      |    label=<<b>bold</b><br/><font point-size="9">plain</font>>;
      |    a;
      |  }
      |}""".stripMargin

  private val boldNode =
    """digraph G {
      |  n [shape=plain, fontname="Helvetica-Bold", label=<<b>x</b> <i>y</i>>];
      |}""".stripMargin

  private val italicNode =
    """digraph G {
      |  n [shape=plain, fontname="Helvetica-Oblique", label=<<i>x</i> <b>y</b>>];
      |}""".stripMargin

  test("a group label with <b> and a bold font writes font-weight one time"):
    assertNoRepeats(boldGroup)
    assertWellFormed(boldGroup)

  test("a node label with <b> and a bold font writes font-weight one time"):
    assertNoRepeats(boldNode)
    assertWellFormed(boldNode)

  test("a node label with <i> and an oblique font writes font-style one time"):
    assertNoRepeats(italicNode)
    assertWellFormed(italicNode)

  // Graphviz 14.1.5 output for `boldNode`: the attributes of the `<i>` run.
  test("a <i> run in a bold font has the weight of the font and the style of <i>"):
    val y = texts(boldNode).find(_.contains("font-style")).getOrElse(fail("no italic run"))
    assert(
      y.contains("""font-family="Helvetica,sans-Serif" font-weight="bold" font-style="italic" font-size="14.00""""),
      y
    )

end HtmlFontAliasSpec
