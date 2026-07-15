package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Root graph label (do_graph_label) — 12-glabel probe (`label="hi"`, narrower
  * than the drawing ⇒ no label-driven widening). bbox reclaims the reserved
  * label space; json0 emits lp/lwidth/lheight alphabetically; svg renders the
  * centered label <text>. (Label-wider-than-drawing widening, multi-line, and
  * top labelloc are tracked follow-ups.)
  */
class GraphLabelSpec extends FunSuite:
  private def g(n: String) = OracleHarness.corpusGraph(n)
  test("12: dot_json byte-exact (bb reclaims graph-label space)"):
    assertEquals(Output.dotJson(g("12-glabel")), OracleHarness.golden("12-glabel", "dot_json"))
  test("12: json0 byte-exact (lp/lwidth/lheight)"):
    assertEquals(Output.json0(g("12-glabel")), OracleHarness.golden("12-glabel", "json0"))
  test("12: svg byte-exact (centered graph label <text>)"):
    assertEquals(Svg.svg(g("12-glabel")), OracleHarness.golden("12-glabel", "svg"))
