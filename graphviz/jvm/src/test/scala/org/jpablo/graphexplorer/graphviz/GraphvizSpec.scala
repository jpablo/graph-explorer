package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** M7 increment-3 gate: the `renderFormats` facade behaves like the slice of
  * viz-js the viewer uses — `MultipleRenderResult`-shaped, formats emitted
  * identically to the individual writers, failures reported not thrown. */
class GraphvizSpec extends FunSuite:

  private val corpus = List("01-minimal", "06-undirected", "07-cross")

  corpus.foreach { name =>
    test(s"$name: renderFormats(dot_json,json0,svg) success + matches writers"):
      val src = OracleHarness.corpusSource(name)
      val r   = Graphviz.renderFormats(src, Seq("dot_json", "json0", "svg"))
      assertEquals(r.status, "success", r.errors.toString)
      assertEquals(r.output.keySet, Set("dot_json", "json0", "svg"))
      assert(r.errors.isEmpty)
      val g = AttrResolver.resolve(DotParser.parse(src).toOption.get)
      assertEquals(r.output("dot_json"), Output.dotJson(g))
      assertEquals(r.output("json0"), Output.json0(g))
      assertEquals(r.output("svg"), Svg.svg(g))
  }

  test("requested-format subset is honoured (viewer asks dot_json only)"):
    val r = Graphviz.renderFormats(OracleHarness.corpusSource("01-minimal"), Seq("dot_json"))
    assertEquals(r.status, "success")
    assertEquals(r.output.keySet, Set("dot_json"))

  test("malformed DOT → failure with errors, not an exception"):
    val r = Graphviz.renderFormats("digraph { a -> }", Seq("dot_json"))
    assertEquals(r.status, "failure")
    assert(r.output.isEmpty)
    assert(r.errors.nonEmpty)
    assertEquals(r.errors.head.level, Some("error"))

  test("unsupported format → failure (no partial output)"):
    val r = Graphviz.renderFormats(OracleHarness.corpusSource("01-minimal"), Seq("dot_json", "png"))
    assertEquals(r.status, "failure")
    assert(r.output.isEmpty)
    assert(r.errors.exists(_.message.contains("png")))

end GraphvizSpec
