package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** HTML-like labels (Graphviz `make_html_label`). Text labels size byte-
  * identically to the equivalent quoted label; rendering is left-anchored with
  * per-run font styling. Tables lay out cells with border/spacing/padding. */
class HtmlSpec extends FunSuite:
  // Inject the image-dimension sidecar (if any) into the resolved graph, exactly
  // as the caller would supply viz-js's `images` render option.
  private def g(n: String) =
    val r = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)
    r.copy(images = OracleHarness.corpusImages(n))

  private val cases = List(
    "30-htmltext", "31-htmlbold", "32-htmlitalic", "33-htmlfont", "34-htmlmulti",
    "35-htmltable1", "36-htmltable2", "37-htmltableel", "38-htmltableedge",
    "39-htmlbgcolor", "40-htmlnoborder", "41-htmlalign",
    "42-htmlcolspan", "43-htmlrowspan", "44-htmlspanmix",
    "45-htmlvaligntop", "46-htmlvalignbot", "47-htmlport", "48-htmlporthead",
    "49-htmlportheadn", "50-htmlports", "51-htmlsubsup",
    "52-htmlhr", "53-htmlvr", "54-htmlgradient", "55-htmlporttailn",
    "56-htmlimg", "57-htmlimgmix",
    "58-htmlimgnat", "59-htmlimgrow", "60-htmlimgfrac"
  )

  // Nested-table cell port resolves to the inner cell (recursion + offset). The
  // spline through the outer structure isn't byte-exact (routing), so gate the
  // resolution directly: a single centred nested cell sits at the outer centre.
  test("nested cell port resolves to the inner cell box"):
    import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel, HtmlTableLayout}
    val markup = "<TABLE><TR><TD><TABLE><TR><TD PORT=\"inner\">z</TD></TR></TABLE></TD></TR></TABLE>"
    val tbl = HtmlParser.parse(markup).collect { case HtmlLabel.Table(t) => t }.get
    val box = HtmlTableLayout.cellPortBox(tbl, "inner", 14.0, "Times").get
    assert(math.abs((box.llx + box.urx) / 2.0) < 1e-9, s"inner cell x-centre ≈ 0, got ${box.cx}")
    assert(math.abs((box.lly + box.ury) / 2.0) < 1e-9, s"inner cell y-centre ≈ 0, got ${box.cy}")

  cases.foreach { name =>
    test(s"$name: dot_json byte-exact (html size + raw markup label)"):
      assertEquals(Output.dotJson(g(name)), OracleHarness.golden(name, "dot_json"))
    test(s"$name: svg byte-exact (left-anchored html text)"):
      assertEquals(Svg.svg(g(name)), OracleHarness.golden(name, "svg"))
  }
