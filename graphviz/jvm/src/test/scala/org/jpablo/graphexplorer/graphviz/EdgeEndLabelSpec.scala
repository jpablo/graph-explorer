package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

/** The labels at the ends of an edge: `headlabel` and `taillabel`.
  *
  * Before, the port did not draw these labels. A UML diagram that shows its
  * multiplicities ("0..*", "1") with `headlabel` lost all of them. Graphviz
  * draws each label as a `<text>` in the group of the edge, after the arrow
  * heads, and json0 gives its position as `head_lp` and `tail_lp`.
  *
  * The expected values come from Graphviz 13.0.1 (the viz-js oracle). Graphviz
  * 14.1.5 gives other numbers for the same source.
  */
class EdgeEndLabelSpec extends FunSuite:

  private val src =
    """digraph G {
      |  a -> b [headlabel="0..*", taillabel="owner"];
      |  c -> d [dir=back, arrowtail=diamond, arrowhead=none, headlabel="1"];
      |}""".stripMargin

  private lazy val out =
    val r = Graphviz.renderFormats(src, Seq("svg", "json0"))
    assertEquals(r.status, "success", r.errors.toString)
    r.output

  /** The SVG of the edge group with the title `title`. */
  private def edgeGroup(title: String): String =
    val svg   = out("svg")
    val start = svg.indexOf(s"<title>$title</title>")
    assert(start >= 0, s"no edge $title in:\n$svg")
    svg.substring(start, svg.indexOf("</g>", start))

  private def text(x: String, y: String, s: String) =
    s"""<text xml:space="preserve" text-anchor="middle" x="$x" y="$y" font-family="Times,serif" font-size="14.00">$s</text>"""

  test("a headlabel and a taillabel are drawn in the group of the edge"):
    val g = edgeGroup("a&#45;&gt;b")
    assert(g.contains(text("24.49", "-40.3", "0..*")), g)
    assert(g.contains(text("17.49", "-59.1", "owner")), g)

  test("a headlabel on an edge with dir=back is drawn at the head"):
    val g = edgeGroup("c&#45;&gt;d")
    assert(g.contains(text("103.49", "-40.3", "1")), g)

  test("json0 gives head_lp and tail_lp"):
    val j = out("json0")
    assert(j.contains("\"head_lp\": \"24.486,44.504\""), j)
    assert(j.contains("\"tail_lp\": \"17.493,63.297\""), j)
    assert(j.contains("\"head_lp\": \"103.49,44.504\""), j)

end EdgeEndLabelSpec
