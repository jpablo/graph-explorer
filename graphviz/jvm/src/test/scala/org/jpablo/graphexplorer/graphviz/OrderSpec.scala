package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.Order

/** M3 exit gate: within-rank ordering / crossing minimisation.
  *
  * Primary gate is crossing-count parity with the oracle (the objective
  * `mincross` optimises). The whole corpus is 2-layer planar per rank pair,
  * so the oracle achieves 0 crossings everywhere — our ordering must too.
  * `07-cross` additionally pins the resolved permutation: declaration order
  * gives 3 crossings, the unique optimum needs rank 1 = [b3,b2,b1].
  *
  * Left/right mirroring of a rank is an accepted documented deviation.
  * Clustered `03` is excluded (M6).
  */
class OrderSpec extends FunSuite:

  private val NodeStanza =
    """(?m)^\t+("(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_]*)\t\[([^\]]*)\]""".r
  private val PosRe = """\bpos="([-0-9.]+),([-0-9.]+)"""".r
  private val Keywords = Set("graph", "node", "edge")

  private def unquote(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else s

  /** Oracle order: rank 0..k, each rank left-to-right. Rank axis chosen by
    * `rankdir`; within a rank, order by the perpendicular axis.
    */
  private def expectedOrder(name: String): List[List[String]] =
    val dot     = OracleHarness.golden(name, "dot")
    val rankdir = """rankdir=([A-Z]+)""".r.findFirstMatchIn(dot).map(_.group(1)).getOrElse("TB")
    val pts = NodeStanza.findAllMatchIn(dot).flatMap { m =>
      val nm = unquote(m.group(1))
      if Keywords.contains(nm) then None
      else PosRe.findFirstMatchIn(m.group(2)).map(p => (nm, p.group(1).toDouble, p.group(2).toDouble))
    }.toList
    type Pt = (String, Double, Double)
    val (rankAxis, withinAxis): (Pt => Double, Pt => Double) =
      rankdir match
        case "LR" => ((t: Pt) => t._2, (t: Pt) => t._3)
        case "RL" => ((t: Pt) => -t._2, (t: Pt) => t._3)
        case "BT" => ((t: Pt) => t._3, (t: Pt) => t._2)
        case _    => ((t: Pt) => -t._3, (t: Pt) => t._2) // TB: rank by -y, within by x
    pts
      .groupBy(t => math.round(rankAxis(t) * 10.0))
      .toList
      .sortBy(_._1)
      .map { case (_, grp) => grp.sortBy(withinAxis).map(_._1) }

  private def ourOrder(name: String): List[List[String]] =
    val g = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)
    val r = Order.order(g)
    // Edge-label rank-doubling (rank.c edgelabel_ranks) interleaves empty
    // real-node ranks for the label vnodes; the oracle's position-recovered
    // partition has no such gap, so dropping empty rows is the
    // layout-equivalent comparison (cf. the mirror allowance).
    r.realOrder.toList.sortBy(_._1).map(_._2.toList).filter(_.nonEmpty)

  private def crossings(name: String): Long =
    val g = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)
    Order.order(g).crossings

  // Whole corpus is per-rank-pair planar ⇒ oracle = 0 crossings everywhere.
  List("01-minimal", "02-attrs", "04-ports-compass", "05-strings-comments",
       "06-undirected", "07-cross").foreach { name =>
    test(s"$name: zero crossings (matches oracle objective)"):
      assertEquals(crossings(name), 0L)
  }

  private def matchesAllowingMirror(our: List[List[String]], exp: List[List[String]]): Boolean =
    our.equals(exp) || our.equals(exp.map(_.reverse))

  List("01-minimal", "02-attrs", "04-ports-compass", "05-strings-comments", "06-undirected")
    .foreach { name =>
      test(s"$name: real-node order per rank matches the oracle"):
        assert(
          matchesAllowingMirror(ourOrder(name), expectedOrder(name)),
          s"$name order ${ourOrder(name)} vs oracle ${expectedOrder(name)}"
        )
    }

  test("07-cross: rank 1 resolved to [b3,b2,b1] (3 crossings → 0)"):
    val our = ourOrder("07-cross")
    val exp = expectedOrder("07-cross") // List(List(a1,a2,a3), List(b3,b2,b1))
    assertEquals(exp, List(List("a1", "a2", "a3"), List("b3", "b2", "b1")))
    assert(matchesAllowingMirror(our, exp), s"got $our")

  test("03 clusters: ordering runs for all real nodes (exact match deferred to M6)"):
    val g = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource("03-subgraph-cluster")).toOption.get)
    val r = Order.order(g)
    assertEquals(r.realOrder.values.flatten.toSet, g.nodes.map(_.id).toSet)

end OrderSpec
