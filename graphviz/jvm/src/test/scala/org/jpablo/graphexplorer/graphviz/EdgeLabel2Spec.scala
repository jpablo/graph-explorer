package org.jpablo.graphexplorer.graphviz
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.Output

/** Edge labels increment 2 — the asymmetric label vnode (class2 label_vnode:
  * lw=nodesep, rw=labelWidth) so a label reserves order-axis space and pushes
  * its rank neighbour. 15-elbranch (a->b[WIDE]; a->c): NODE positions become
  * byte-exact (b/c spread to fit the label). The label vnode's own x (lp) +
  * the spline routing around it still need the make_edge_pairs label offset —
  * tracked follow-up. */
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
