package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.Output

/** A port with no compass point is `dyna`: `closestSide` (shapes.c:4283) picks
  * the cell side nearest `other`, and `endpath` supplies that other end as
  * `agtail(e)` of the segment it was handed.
  *
  * The subtlety is WHICH segment. `dot_splines_` collects the chain's FIRST
  * SEGMENT into `edges[]`, so `make_regular_edge`'s
  * `hackflag = |Δrank(edges[ind])| > 1` is FALSE for an ordinary chained edge,
  * the walk down `ND_out(hn).list[0]` leaves `e` at the LAST segment, and
  * `endpath(P, hackflag ? &fwdedgeb.out : e, ...)` therefore resolves against
  * the last chain VNODE — never the real tail. gv's own probe on 192 reports
  * `tailtype=VIRTUAL` for every `endpath` into a table node.
  *
  * It matters whenever the chain has drifted past the target's centre: 27 of
  * 192's edges reach `db_table`/`ws_table`/`ps_table`/`gpu_table` from vnodes to
  * the RIGHT of the node while their real tails are far to the LEFT, so
  * comparing against the real tail entered every one of them on the wrong side.
  */
class PortSideSpec extends FunSuite:

  private val callGraph = "192-rank-gap-callgraph"

  /** Arrow tips (`e,x,y`) of every edge into `node`, by tail name. */
  private def tipsInto(json0: String, node: String): Vector[(String, Double, Double)] =
    val o = ujson.read(json0)
    val names = o("objects").arr.map(x => x.obj.get("name").map(_.str).getOrElse("")).toVector
    o("edges").arr.iterator.collect {
      case e if names(e("head").num.toInt) == node =>
        val Array(_, x, y) = e("pos").str.split(" ").head.split(",")
        (names(e("tail").num.toInt), x.toDouble, y.toDouble)
    }.toVector

  test("a chained edge enters the side nearest its LAST VNODE, not its tail"):
    val json0 = Output.json0(OracleHarness.corpusGraph(callGraph))
    val tips  = tipsInto(json0, "db_table")
    assert(tips.nonEmpty)
    // db_table spans x 1951.9..2258.1; its full-width cells run 1958.9..2251.1.
    // `start_worker_if_fits` sits at x=1220, far to the LEFT — resolving against
    // it would enter at 1958.9. gv enters at 2251.1, because the chain arrives
    // at x=2134.
    val fromFits = tips.filter(_._1 == "start_worker_if_fits")
    assert(fromFits.nonEmpty, "192 should still route start_worker_if_fits -> db_table")
    fromFits.foreach { (t, x, _) =>
      assertEqualsDouble(x, 2251.1, 0.05, s"$t -> db_table should enter from the RIGHT")
    }

  test("both sides are still reachable — this is a choice, not a constant"):
    val tips = tipsInto(Output.json0(OracleHarness.corpusGraph(callGraph)), "ws_table")
    val xs   = tips.map(_._2).distinct
    assert(xs.length > 1, s"ws_table should be entered on more than one side: $xs")

  test("a merged member keeps its own clip; only the rep's was cleared"):
    // beginpath/endpath's side branch walks ED_to_orig and writes
    // `ED_head_port(orig).clip = false` — on the REP's original edge. A member
    // has no chain, so nothing cleared its port and clip_and_install still
    // clips it against the port box. 192 declares
    // `start_worker_if_fits -> worker_table:build_inference` twice; the two
    // copies end 0.27pt apart, and sharing the rep's flag collapses them.
    val json0 = Output.json0(OracleHarness.corpusGraph(callGraph))
    val tips  = tipsInto(json0, "worker_table").filter(_._1 == "start_worker_if_fits")
    assertEquals(tips.length, 2, "the class should install one copy per declaration")
    assert(tips.map(_._3).distinct.length == 2,
      s"the copies must NOT share an endpoint: ${tips.map(_._3)}")
    assertEquals(tips.map(_._3).sorted, Vector(388.8, 389.07))
