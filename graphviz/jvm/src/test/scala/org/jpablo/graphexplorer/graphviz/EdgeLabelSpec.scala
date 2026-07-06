package org.jpablo.graphexplorer.graphviz
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Edge labels (14-edgelabel probe) — straight TB edges. The label sits right
  * of the edge at lp = labelVnode.x + labelWidth/2 (probe-derived). json0 `lp`
  * + svg `<text>` byte-exact. Also fixed a pre-existing Coord bug: intermediate
  * virtual ranks get half-height 0.5 (ND_ht=1). Asymmetric-vnode separation
  * (neighbours / rankdir=LR) is the follow-up increment. */
class EdgeLabelSpec extends FunSuite:
  private def g(n: String) = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)
  test("14: json0 byte-exact (edge lp)"):
    assertEquals(Output.json0(g("14-edgelabel")), OracleHarness.golden("14-edgelabel", "json0"))
  test("14: svg byte-exact (edge label <text>)"):
    assertEquals(Svg.svg(g("14-edgelabel")), OracleHarness.golden("14-edgelabel", "svg"))
  test("14: dot_json byte-exact"):
    assertEquals(Output.dotJson(g("14-edgelabel")), OracleHarness.golden("14-edgelabel", "dot_json"))
