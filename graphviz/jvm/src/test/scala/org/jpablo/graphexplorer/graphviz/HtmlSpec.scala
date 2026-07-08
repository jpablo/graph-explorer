package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** HTML-like labels (Graphviz `make_html_label`). Text labels size byte-
  * identically to the equivalent quoted label; rendering is left-anchored with
  * per-run font styling. Tables lay out cells with border/spacing/padding. */
class HtmlSpec extends FunSuite:
  private def g(n: String) = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)

  private val cases = List(
    "30-htmltext", "31-htmlbold", "32-htmlitalic", "33-htmlfont", "34-htmlmulti"
  )

  cases.foreach { name =>
    test(s"$name: dot_json byte-exact (html size + raw markup label)"):
      assertEquals(Output.dotJson(g(name)), OracleHarness.golden(name, "dot_json"))
    test(s"$name: svg byte-exact (left-anchored html text)"):
      assertEquals(Svg.svg(g(name)), OracleHarness.golden(name, "svg"))
  }
