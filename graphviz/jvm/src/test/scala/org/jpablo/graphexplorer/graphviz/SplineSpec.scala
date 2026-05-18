package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.Spline

/** M5 exit gate: edge geometry is **visually close** to the `plain` golden.
  *
  * The full Graphviz 13.0.1 box-fit router is ported (box channel →
  * `Pshortestpath` taut funnel → `Proutespline` recursive Bézier fit →
  * `clip_and_install`/`bezier_clip`). It was derived and verified against an
  * instrumented gv-13.0.1 build (PORT.md §2.5): the box channel, shortest
  * path and raw spline reproduce the probe dumps byte-for-byte (e.g. 01
  * `a→c`), so this is a faithful reimplementation, not a curve fit.
  *
  * Gate = **dense-sample symmetric Hausdorff** between our piecewise cubic
  * and the golden's (the "visually close" objective — not control-point
  * parity, which Graphviz itself does not guarantee across builds). A whole-
  * drawing horizontal mirror is layout-equivalent and allowed (identical to
  * `XCoordSpec`): 06's X comes out mirrored, which is not a routing defect.
  *
  * Bounds (PORT.md §1 "visually close" = ~2–4 px = 0.03–0.06 in):
  *  - `Eps`   = 0.04 in: general bound, kept for the 06 whole-drawing gate
  *    (06 is undirected ⇒ no arrow clip; its ≈0.013 in residual is the
  *    documented layout-equivalent X-mirror, cf. XCoordSpec).
  *  - `Eps07` = 0.005 in: 07 has no virtual nodes; once the **true**
  *    `arrow_length_normal` (≈11.53, not nominal 10) is clipped, its
  *    spline is byte-exact to Graphviz (measured 0.00000) — tightened
  *    6× from 0.03 to lock that.
  *  - `EpsExact` = 0.005 in: the directed corpus (01/07/04) spline is now
  *    byte-exact end-to-end (the M5/M7 arrow-length residual is closed);
  *    measured worst 0.00004 in — this bound is pure float margin.
  */
class SplineSpec extends FunSuite:

  private val Eps      = 0.04
  private val Eps07    = 0.005
  private val EpsExact = 0.005

  private val EdgeLine = """(?m)^edge (\S+) (\S+) (\d+) (.+)$""".r
  private val NodeLine = """(?m)^node (\S+) (\S+) (\S+) .*$""".r

  private def goldenEdges(name: String): Map[(String, String), Vector[(Double, Double)]] =
    EdgeLine.findAllMatchIn(OracleHarness.golden(name, "plain")).map { m =>
      val t = m.group(1); val h = m.group(2); val n = m.group(3).toInt
      val nums = m.group(4).trim.split("\\s+").take(2 * n).map(_.toDouble)
      (t, h) -> nums.grouped(2).map(p => (p(0), p(1))).toVector
    }.toMap

  // Spline.splines is now keyed by the g.edges index (parallel edges no
  // longer collapse). 01/06/07 have unique (tail,head) ⇒ re-key for the
  // existing whole-drawing gates; 04's parallels get their own test below.
  private def ourEdges(name: String): Map[(String, String), Vector[(Double, Double)]] =
    val g  = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)
    val sp = Spline.splines(g)
    g.edges.zipWithIndex.flatMap { case (e, ix) =>
      sp.get(ix).map(pts => (e.tail, e.head) -> pts.map(p => (p.x / 72.0, p.y / 72.0)))
    }.toMap

  /** Ordered golden `plain` edge splines (file order ⇒ declaration order),
    * for graphs whose `(tail,head)` is not unique (04's two parallels). */
  private def goldenEdgeList(name: String): Vector[Vector[(Double, Double)]] =
    EdgeLine.findAllMatchIn(OracleHarness.golden(name, "plain")).map { m =>
      val n    = m.group(3).toInt
      val nums = m.group(4).trim.split("\\s+").take(2 * n).map(_.toDouble)
      nums.grouped(2).map(p => (p(0), p(1))).toVector
    }.toVector

  /** Mirror axis used by XCoordSpec: W = (golden node x) max + min. */
  private def mirrorW(name: String): Double =
    val xs = NodeLine.findAllMatchIn(OracleHarness.golden(name, "plain")).map(_.group(2).toDouble).toVector
    xs.max + xs.min

  private def cubic(p: Vector[(Double, Double)], s: Int, t: Double): (Double, Double) =
    val u = 1 - t
    val (x0, y0) = p(s); val (x1, y1) = p(s + 1); val (x2, y2) = p(s + 2); val (x3, y3) = p(s + 3)
    val b0 = u * u * u; val b1 = 3 * t * u * u; val b2 = 3 * t * t * u; val b3 = t * t * t
    (b0 * x0 + b1 * x1 + b2 * x2 + b3 * x3, b0 * y0 + b1 * y1 + b2 * y2 + b3 * y3)

  private def sample(ctrl: Vector[(Double, Double)], per: Int): Vector[(Double, Double)] =
    val segs = (ctrl.length - 1) / 3
    (0 until segs).flatMap(s => (0 to per).map(i => cubic(ctrl, 3 * s, i.toDouble / per))).toVector

  private def d(a: (Double, Double), b: (Double, Double)): Double =
    math.hypot(a._1 - b._1, a._2 - b._2)

  private def directed(as: Vector[(Double, Double)], bs: Vector[(Double, Double)]): Double =
    as.iterator.map(a => bs.iterator.map(b => d(a, b)).min).max

  private def hausdorff(a: Vector[(Double, Double)], b: Vector[(Double, Double)]): Double =
    val sa = sample(a, 64); val sb = sample(b, 64)
    math.max(directed(sa, sb), directed(sb, sa))

  /** Whole-drawing deviation under a coordinate transform (identity / mirror),
    * = max edge Hausdorff. Mirroring is applied consistently to every edge
    * (not per-edge) so it cannot mask a real routing defect.
    */
  private def maxDev(name: String, tx: ((Double, Double)) => (Double, Double)): Double =
    val gold = goldenEdges(name)
    val ours = ourEdges(name)
    gold.keysIterator.map { k =>
      val op = ours.getOrElse(k, fail(s"$name: missing edge $k"))
      hausdorff(op.map(tx), gold(k))
    }.max

  private def deviation(name: String): Double =
    val w = mirrorW(name)
    math.min(maxDev(name, identity), maxDev(name, { case (x, y) => (w - x, y) }))

  // Structural: every edge is a well-formed piecewise cubic Bézier.
  List("01-minimal", "06-undirected", "07-cross").foreach { name =>
    test(s"$name: edges are well-formed piecewise cubic béziers"):
      val gold = goldenEdges(name)
      val ours = ourEdges(name)
      gold.keys.foreach { k =>
        val op = ours.getOrElse(k, fail(s"$name: missing edge $k"))
        assert(op.length >= 4 && (op.length - 1) % 3 == 0, s"$name $k malformed: ${op.length}")
      }
  }

  // 07 has NO virtual nodes ⇒ the raw routesplines spline is byte-exact to
  // Graphviz; only the sub-2px endpoint clip differs. Tight regression guard.
  test("07-cross: edge geometry geometrically exact (no-virtual-node guard)"):
    assert(maxDev("07-cross", identity) <= Eps07, s"07 dev=${maxDev("07-cross", identity)}")

  // 01 (curved long edge a→c) and 06 (all straight; X mirrored) — strict
  // curve-deviation gate, mirror allowed (layout-equivalent, cf. XCoordSpec).
  List("01-minimal", "06-undirected").foreach { name =>
    test(s"$name: spline geometry within ε of the plain golden (mirror allowed)"):
      assert(deviation(name) <= Eps, s"$name dev=${deviation(name)} (eps=$Eps)")
  }

  // The whole point of M5: a→c is the box-fit curve (3 cubics bowing through
  // the virtual-node region), NOT a straight 2-leg approximation.
  test("01-minimal: a→c is the box-fit curve through the virtual region"):
    val ac = ourEdges("01-minimal")(("a", "c"))
    assertEquals(ac.length, 10, s"a→c should be 3 cubics (10 ctrl pts): $ac")
    val maxX = ac.iterator.map(_._1).max
    assert(maxX > ac.head._1 && maxX > ac.last._1, s"a→c should bow right: $ac")

  // ── 04 record ports: the de-merged, port-box-routed splines ──────────────
  // 04 is TB (struct1 above struct2) and NOT mirrored — assert that here
  // rather than allowing a mirror (per the task's "verify, don't assume").
  // Two parallel struct1→struct2 edges (f0→a, f2:s→b:n) keyed by edge index;
  // matched to the golden by start-point and gated on curve Hausdorff.
  test("04-ports-compass: both record-port edges within ε of the plain golden"):
    val g  = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource("04-ports-compass")).toOption.get)
    val sp = Spline.splines(g)
    val ours = g.edges.indices.flatMap(ix =>
      sp.get(ix).map(_.map(p => (p.x / 72.0, p.y / 72.0)))
    ).toVector
    assertEquals(ours.length, 2, s"04 should have 2 de-merged edges, got ${ours.length}")
    val gold = goldenEdgeList("04-ports-compass")
    assertEquals(gold.length, 2, "04 golden should list 2 edges")
    // 04 is TB & unmirrored: the X axis must match directly (no mirror).
    val nodeXs = NodeLine.findAllMatchIn(OracleHarness.golden("04-ports-compass", "plain"))
      .map(_.group(2).toDouble).toVector
    // pair each golden edge with the nearest-start our edge (unambiguous:
    // f0 start x≈0.24in vs f2:s start x≈1.54in).
    gold.foreach { ge =>
      val best = ours.minBy(oe => d(oe.head, ge.head))
      val dev  = hausdorff(best, ge)
      assert(dev <= EpsExact, s"04 edge (start ${ge.head}) dev=$dev (eps=$EpsExact)")
    }
    // each our edge must be the closest match for exactly one golden edge
    val assigned = gold.map(ge => ours.minBy(oe => d(oe.head, ge.head)))
    assert(assigned.toSet.size == 2, s"04 edges collapsed (parallel-edge de-merge failed): $assigned")

  // Arrow-length close (arrow_length_normal ≈11.53, not nominal 10): the
  // directed corpus's edge splines are now **byte-exact** to the `plain`
  // golden end-to-end (incl. 01 `a→c`, the M5 curved showcase, 0.024→
  // 0.00002 in). TB & unmirrored ⇒ identity (no mirror allowance). This
  // is the principled close of the long-deferred M5/M7 endpoint residual.
  List("01-minimal", "07-cross").foreach { name =>
    test(s"$name: directed splines byte-exact (true arrow length)"):
      val gold = goldenEdges(name)
      val ours = ourEdges(name)
      gold.foreach { case (k, gg) =>
        val dev = hausdorff(ours.getOrElse(k, fail(s"$name missing $k")), gg)
        assert(dev <= EpsExact, s"$name $k dev=$dev (eps=$EpsExact)")
      }
  }

  // Self-loops (`makeSelfEdge`→`selfRight`, no-port): self-edges don't rank
  // (excluded from acyclic/order), routed separately keyed by edge index.
  // 08 = `a -> a` + `a -> b`; both byte-exact vs the `plain` golden (the
  // true arrow-length close applies to the loop's clipped end too).
  test("08-selfloop: self-loop + normal edge byte-exact vs the golden"):
    val gold = goldenEdges("08-selfloop")
    val ours = ourEdges("08-selfloop")
    assert(gold.contains(("a", "a")) && gold.contains(("a", "b")), "08 golden edges")
    gold.foreach { case (k, gg) =>
      val dev = hausdorff(ours.getOrElse(k, fail(s"08 missing $k")), gg)
      assert(dev <= EpsExact, s"08 $k dev=$dev (eps=$EpsExact)")
    }
    // the loop is a 2-cubic (7 ctrl pts) bowing right of node a (centre
    // x=0.375in, rw=0.375 ⇒ right edge 0.75in); the golden bows to x=1.0in.
    val loop = ours(("a", "a"))
    assertEquals(loop.length, 7, s"a→a should be 2 cubics: $loop")
    assert(loop.iterator.map(_._1).max > 0.75, s"a→a must bow right of node a: $loop")

end SplineSpec
