package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics

/** A monospaced font that Graphviz does not know gets the Courier metrics.
  *
  * The text-width estimator of Graphviz knows three families: Times,
  * Helvetica/Arial and Courier. Each other font name gets the Times metrics.
  * The browser then draws the real font. For a monospaced font such as Menlo,
  * each character is about 0.6 em wide, as in Courier, and much wider than in
  * Times. So a node with Menlo text was too narrow: the text went past the
  * border of the box, and the edge labels overlapped.
  *
  * The common monospaced font names now get the Courier metrics. Consolas is
  * not in the list: the tables have its own metrics. A proportional font that
  * Graphviz does not know still gets the Times metrics, as in Graphviz.
  *
  * The SVG also asks for a monospaced fallback font: `font-family="Menlo,monospace"`.
  * A browser that does not have Menlo then draws a monospaced font.
  */
class MonoFontMetricsSpec extends FunSuite:

  private val sample = "Range.split[0, 3] (0 <= 3) =:= true"

  private val monoNames = List(
    "Menlo",
    "Monaco",
    "SF Mono",
    "SFMono-Regular",
    "ui-monospace",
    "monospace",
    "DejaVu Sans Mono",
    "Fira Code",
    "Fira Mono",
    "JetBrains Mono",
    "Source Code Pro",
    "Ubuntu Mono",
    "Roboto Mono",
    "Cascadia Code",
    "Andale Mono",
    "Lucida Console",
    "IBM Plex Mono",
    "Inconsolata",
    "Hack"
  )

  test("each common monospaced font name gets the Courier width"):
    val courier = FontMetrics.estimateTextWidth1pt("Courier", sample)
    val wrong = monoNames.filter: name =>
      math.abs(FontMetrics.estimateTextWidth1pt(name, sample) - courier) > 1e-9
    assert(wrong.isEmpty, s"these names do not get the Courier width: ${wrong.mkString(", ")}")

  test("bold and italic text in a monospaced font also gets the Courier width"):
    for (bold, italic) <- List((true, false), (false, true), (true, true)) do
      val courier = FontMetrics.estimateTextWidth1pt("Courier", sample, bold, italic)
      val menlo   = FontMetrics.estimateTextWidth1pt("Menlo", sample, bold, italic)
      assertEqualsDouble(menlo, courier, 1e-9, s"bold=$bold italic=$italic")

  test("a proportional font that Graphviz does not know still gets the Times width"):
    val times = FontMetrics.estimateTextWidth1pt("Times", sample)
    for name <- List("Helvetica Neue", "Avenir", "Optima") do
      assertEqualsDouble(FontMetrics.estimateTextWidth1pt(name, sample), times, 1e-9, name)

  /** The diagram of the report, with each font name replaced by `face`. */
  private def diagram(face: String): String =
    s"""digraph G {
       |  node [shape=box, style="rounded,filled", margin="0.18,0.12"];
       |  edge [fontname="$face", fontsize=11];
       |  derived [label=<<FONT FACE="$face" POINT-SIZE="15">summon[BinarySearchTypeClass[4]]</FONT><BR/><FONT FACE="$face" POINT-SIZE="19"><B>derived[4]</B></FONT>>];
       |  split [label=<<FONT FACE="$face" POINT-SIZE="16"><B>Range.split[0, 3]</B></FONT><BR/><FONT FACE="$face" POINT-SIZE="12">middle: ValueOf[1]</FONT>>];
       |  left [label=<<FONT FACE="$face" POINT-SIZE="16"><B>Range.split[0, 0]</B></FONT>>];
       |  right [label=<<FONT FACE="$face" POINT-SIZE="16"><B>Range.split[2, 3]</B></FONT>>];
       |  derived -> split [label="range: Range[0, 3]"];
       |  split -> left [label="left: Range[0, 0]"];
       |  split -> right [label="right: Range[2, 3]"];
       |}""".stripMargin

  /** The layout of `src`: the SVG output with the font names removed. */
  private def layout(src: String, face: String): String =
    val r = Graphviz.renderFormats(src, Seq("svg"))
    assertEquals(r.status, "success", r.errors.toString)
    r.output("svg").replace(face, "FONT")

  test("a diagram in Menlo gets the same layout as the same diagram in Courier"):
    assertNoDiff(layout(diagram("Menlo"), "Menlo"), layout(diagram("Courier"), "Courier"))

  test("the SVG of a monospaced font has a monospace fallback"):
    val src =
      """digraph G {
        |  a [fontname="Menlo", label="plain menlo"];
        |  b [label=<<FONT FACE="Menlo">html menlo</FONT>>];
        |  c [fontname="Helvetica", label="helvetica text"];
        |  a -> b [fontname="Menlo", label="edge menlo"];
        |  b -> c;
        |}""".stripMargin
    val r = Graphviz.renderFormats(src, Seq("svg"))
    assertEquals(r.status, "success", r.errors.toString)
    val svg = r.output("svg")
    val families = """font-family="([^"]*)"""".r.findAllMatchIn(svg).map(_.group(1)).toList
    val menlo = families.filter(_.contains("Menlo"))
    // The node text, the HTML run and the edge label.
    assertEquals(menlo.size, 3, families.toString)
    assert(menlo.forall(_ == "Menlo,monospace"), menlo.toString)
    // A name that the PostScript alias table knows keeps the Graphviz output.
    val helvetica = svg.linesIterator.filter(_.contains(">helvetica text<")).toList
    assertEquals(helvetica.size, 1, svg)
    assert(helvetica.head.contains(""" font-family="Helvetica,sans-Serif" """), helvetica.head)
