package org.jpablo.graphexplorer.graphviz
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Edge labels increment 2 — the asymmetric label vnode (class2 label_vnode:
  * lw=nodesep, rw=labelWidth) so a label reserves order-axis space and pushes
  * its rank neighbour. 15-elbranch (a->b[WIDE]; a->c).
  *
  * Now fully byte-exact (2026-07-11): ported `maximal_bbox`'s label-vnode clamp
  * ("leave room for our own label" — right bound `x+10` then `-= rw`) and
  * `recover_slack`/`resize_vn` (dotsplines.c), which snap the label vnode to the
  * RIGHT edge of the box its edge threads, so a→b bows around the wide label
  * (2 cubics) AND a→c's box starts past the label. lp = routed vnode x +
  * labelWidth/2 (place_vnlabel), read off the byte-exact routed spline. */
class EdgeLabel2Spec extends FunSuite:
  private def g(n: String) = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)
  private def nodePos(dj: String): Map[String, String] =
    ujson.read(dj)("objects").arr.iterator
      .filter(o => o.obj.contains("pos") && !o.obj.contains("nodes"))
      .map(o => o("name").str -> o("pos").str).toMap
  test("15: node positions byte-exact (label vnode pushes neighbour correctly)"):
    val o = Output.json0(g("15-elbranch"))
    assertEquals(nodePos(o), nodePos(OracleHarness.golden("15-elbranch", "json0")))
  test("15: dot_json byte-exact (structure/attrs)"):
    assertEquals(Output.dotJson(g("15-elbranch")), OracleHarness.golden("15-elbranch", "dot_json"))
  test("15: json0 byte-exact (edge lp + spline pos around the label)"):
    assertEquals(Output.json0(g("15-elbranch")), OracleHarness.golden("15-elbranch", "json0"))
  test("15: svg byte-exact (label <text> + splines)"):
    assertEquals(Svg.svg(g("15-elbranch")), OracleHarness.golden("15-elbranch", "svg"))
