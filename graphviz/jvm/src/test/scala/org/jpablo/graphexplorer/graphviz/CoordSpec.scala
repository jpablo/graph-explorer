package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.Coord

/** M4 exit gate (Y only): rank-axis Y matches the `plain` golden tightly.
  *
  * Scoped to TB, non-record graphs: 01/05/06/07 — **05 promoted** now that
  * edge-label rank-doubling + `label_vnode` + graph-label space are ported
  * (M6). LR rotation (02), records (04) and clusters (03) are deferred; the
  * cross-axis X coordinate is a separate milestone (PORT.md §4/§5.2).
  */
class CoordSpec extends FunSuite:

  /** node → y (inches) from the `plain` golden. */
  private def goldenY(name: String): Map[String, Double] =
    OracleHarness.plainNodePositions(name).view.mapValues(_._2).toMap

  private def ourY(name: String): Map[String, Double] =
    val g = OracleHarness.corpusGraph(name)
    Coord.yCoords(g).view.mapValues(_.value / 72.0).toMap

  private val tol = OracleHarness.Tol(abs = 0.005, rel = 0.0)

  // TB graphs: Y is exactly the deterministic model. 05 included now that
  // edge-label rank-doubling + label_vnode + graph-label space are modelled.
  List("01-minimal", "05-strings-comments", "06-undirected", "07-cross").foreach { name =>
    test(s"$name: rank-axis Y matches the plain golden"):
      val exp = goldenY(name)
      val got = ourY(name)
      assert(exp.nonEmpty, s"no nodes parsed from $name plain")
      exp.foreach { case (id, ey) =>
        val gy = got.getOrElse(id, fail(s"$name: node '$id' missing from our Y"))
        assert(OracleHarness.close(gy, ey, tol), s"$name '$id' y: got $gy expected $ey")
      }
  }

  // Promoted (was a self-flagging deferred-probe): edge-label rank-doubling
  // (`ED_minlen*=2`) + `make_chain` `label_vnode` height (nLines·fontsize·
  // LINESPACING — incl. the single-line HTML label) + graph-label bottom
  // space (`do_graph_label`, +YPAD 2·GAP) now reproduce 05's Y exactly.
  // (The HTML label's *width* / table layout is a separate M6 deferral and
  // does not affect the rank-axis Y gated here.)
  test("05: edge-label rank-doubling reproduces the golden Y (rank order holds)"):
    val y = ourY("05-strings-comments")
    assert(y("node one") > y("n2") && y("n2") > y("n3"), y.toString)

  test("01-minimal: exact rank Y (top→bottom 2.25 / 1.25 / 0.25)"):
    val y = ourY("01-minimal")
    assert(OracleHarness.close(y("a"), 2.25, tol), y.toString)
    assert(OracleHarness.close(y("b"), 1.25, tol), y.toString)
    assert(OracleHarness.close(y("c"), 0.25, tol), y.toString)

end CoordSpec
