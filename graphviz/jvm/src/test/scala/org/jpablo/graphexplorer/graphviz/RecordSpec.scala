package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.NodeSize

/** M6 records gate: `record`/`Mrecord` parse + field layout reproduce the
  * 04 `plain` golden's per-field `rects` exactly.
  *
  * `rects` in `plain` are absolute (post node-placement). Records are not
  * placed yet (that's the downstream coord milestone now that they're
  * sized), so the oracle here is `golden rect − golden node centre ==
  * our node-local field box` — which isolates the record *layout* (the
  * thing this increment ports) from node placement. Node `width`/`height`
  * are independently gated by `NodeSizeSpec` against the `dot` golden.
  */
class RecordSpec extends FunSuite:

  private val eps = 0.05 // pt

  private def graph =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource("04-ports-compass")).toOption.get)

  // plain: node <name> <x> <y> <w> <h> <label> ... ; coords in inches.
  private def nodeCenterPt(name: String): (Double, Double) =
    val re = s"""(?m)^node $name (\\S+) (\\S+) """.r
    re.findFirstMatchIn(OracleHarness.golden("04-ports-compass", "plain"))
      .map(m => (m.group(1).toDouble * 72.0, m.group(2).toDouble * 72.0))
      .getOrElse(fail(s"no node $name in plain golden"))

  // Expected node-local box = golden absolute rect − node centre.
  private def expectLocal(node: String, rect: (Double, Double, Double, Double)) =
    val (cx, cy) = nodeCenterPt(node)
    (rect._1 - cx, rect._2 - cy, rect._3 - cx, rect._4 - cy)

  private def boxOf(node: String, port: String): (Double, Double, Double, Double) =
    val g = graph
    val n = g.nodes.find(_.id == node).getOrElse(fail(s"no node $node"))
    val r = NodeSize.recordLayout(n, g).getOrElse(fail(s"$node not a record"))
    NodeSize.recordLayout(n, g)
    org.jpablo.graphexplorer.graphviz.layout.RecordLabel
      .fieldBox(r, port).getOrElse(fail(s"$node has no port $port"))

  private def near(a: (Double, Double, Double, Double), b: (Double, Double, Double, Double)): Unit =
    assert(math.abs(a._1 - b._1) <= eps && math.abs(a._2 - b._2) <= eps &&
           math.abs(a._3 - b._3) <= eps && math.abs(a._4 - b._4) <= eps,
      s"$a vs $b")

  // golden 04 plain rects (absolute, points), per field.
  test("04 struct1: horizontal record field rects match the golden"):
    near(boxOf("struct1", "f0"), expectLocal("struct1", (0.0, 87.1, 34.655, 123.1)))
    near(boxOf("struct1", "f1"), expectLocal("struct1", (34.655, 87.1, 89.538, 123.1)))
    near(boxOf("struct1", "f2"), expectLocal("struct1", (89.538, 87.1, 131.98, 123.1)))

  test("04 struct2: { } flips orientation — vertical field rects match the golden"):
    near(boxOf("struct2", "a"), expectLocal("struct2", (36.99, 25.3, 90.99, 50.1)))
    near(boxOf("struct2", "b"), expectLocal("struct2", (36.99, 0.5, 90.99, 25.3)))

  test("records are now sized (NodeSize no longer returns None for 04)"):
    val g = graph
    g.nodes.foreach { n =>
      assert(NodeSize.nodeSize(n, g).isDefined, s"${n.id} unsized")
    }

end RecordSpec
