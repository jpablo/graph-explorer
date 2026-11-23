package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

/** HTML entities in a plain (quoted) label.
  *
  * Graphviz decodes the entities of each plain label (`htmlEntityUTF8` in
  * `make_label`): `label="Vec&lt;T&gt;"` shows `Vec<T>`, and the size of
  * the label comes from the decoded text. Before, the port decoded the
  * entities of end labels only. The other labels showed the raw text, for
  * example `Vec&lt;T&gt;` on the screen, and were too wide.
  *
  * The expected values come from Graphviz 13.0.1 (the viz-js oracle).
  */
class PlainLabelEntitySpec extends FunSuite:

  private val src =
    """digraph G {
      |  label="A &amp; B";
      |  subgraph cluster_x { label="Map&lt;K, V&gt;"; a; }
      |  a [label="Vec&lt;T&gt; &amp; Option"];
      |  b [label="x", xlabel="&#945;&#x3B2;"];
      |  a -> b [label="&lt;use&gt;"];
      |}""".stripMargin

  private lazy val out =
    val r = Graphviz.renderFormats(src, Seq("svg", "json0"))
    assertEquals(r.status, "success", r.errors.toString)
    r.output

  private def text(x: String, y: String, s: String) =
    s"""<text xml:space="preserve" text-anchor="middle" x="$x" y="$y" font-family="Times,serif" font-size="14.00">$s</text>"""

  private def assertText(x: String, y: String, s: String): Unit =
    val svg = out("svg")
    assert(svg.contains(text(x, y, s)), svg.linesIterator.filter(_.contains("<text")).mkString("\n"))

  test("a node label shows its decoded text"):
    assertText("99", "-127.4", "Vec&lt;T&gt; &amp; Option")

  test("an edge label shows its decoded text"):
    assertText("116.23", "-83", "&lt;use&gt;")

  test("a group label shows its decoded text"):
    assertText("99", "-165.8", "Map&lt;K, V&gt;")

  test("the graph label shows its decoded text"):
    assertText("99", "-8.2", "A &amp; B")

  test("an xlabel decodes numeric entities"):
    assertText("65", "-65", "αβ")

  test("the sizes come from the decoded text"):
    val j = out("json0")
    assert(j.contains("\"bb\": \"0,0,198,190.4\""), j.take(400))
    assert(j.contains("\"width\": \"2.2928\""), j)

end PlainLabelEntitySpec
