package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.XCoord

/** M4x exit gate: cross-axis X within ε of the `plain` golden.
  *
  * Scoped to label-free TB graphs (01/06/07) — same deferrals as M4-Y
  * (02 LR-rotation, 04 records, 03 clusters, edge-label rank-doubling).
  * Strict, no mirror allowance: with the `build_ranks` tail transpose
  * (mincross.c:1349) transcribed, 06 now matches the golden X directly —
  * the former layout-equivalent X-mirror is closed.
  */
class XCoordSpec extends FunSuite:

  private def goldenX(name: String): Map[String, Double] =
    OracleHarness.plainNodePositions(name).view.mapValues(_._1).toMap

  private def ourX(name: String): Map[String, Double] =
    val g = OracleHarness.corpusGraph(name)
    XCoord.xCoords(g).view.mapValues(_.value / 72.0).toMap

  // ~2px abs / 5% rel to start (PORT.md §2.1); tighten once stable.
  private val tol = OracleHarness.Tol(abs = 0.03, rel = 0.05)

  /** Strict: X matches the golden directly (no mirror). */
  private def matchesGolden(name: String): Boolean =
    val exp = goldenX(name)
    val got = ourX(name)
    exp.forall { case (id, ex) => got.get(id).exists(gx => OracleHarness.close(gx, ex, tol)) }

  // Label-free TB graphs: X must match the plain golden directly.
  // Closed after instrumenting real Graphviz: the omega model is
  // virtual_weight()'s class table, not ω=1/2/8-by-virtualness; and the
  // initial ordering is finalised by the build_ranks tail transpose (06).
  List("01-minimal", "06-undirected", "07-cross").foreach { name =>
    test(s"$name: node X matches the plain golden (strict, no mirror)"):
      assert(matchesGolden(name), s"$name X ${ourX(name)} vs golden ${goldenX(name)}")
  }

  test("07-cross: columns straight (a_i aligned with its matched b)"):
    val x = ourX("07-cross")
    // mincross order pairs a1-b3, a2-b2, a3-b1; each pair shares an x column
    assert(OracleHarness.close(x("a1"), x("b3"), tol), x.toString)
    assert(OracleHarness.close(x("a2"), x("b2"), tol), x.toString)
    assert(OracleHarness.close(x("a3"), x("b1"), tol), x.toString)

end XCoordSpec
