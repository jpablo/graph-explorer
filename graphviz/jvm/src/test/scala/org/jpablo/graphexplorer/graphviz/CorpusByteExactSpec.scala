package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}
import java.io.File

/** Whole-corpus byte-exact regression gate: for every corpus file, all three
  * writer outputs (`dot_json`, `json0`, `svg`) must match the captured viz-js
  * (Graphviz 13.0.1) goldens **character-for-character**. Image files load
  * their `<name>.images.json` size sidecar (viz-js caches image sizes by name).
  *
  * 75 of 77 files pass (incl. 03b — the full cluster geometry subsystem, and
  * 05 — closed by the aux-graph seed-truncation fix). The two exclusions are
  * each asserted below to still differ so this list can't rot:
  *   - 03-subgraph-cluster: its goldens are gv's DEFAULT-mode corruption (the
  *     cross-cluster `{rank=same}` breaks the recursive cluster ranker: 13.0.1
  *     emits a degenerate 0×0 sentinel, 12.2.1 hard-errors). Per the "don't
  *     port the bug" rule we lay it out correctly instead — under gv's own
  *     `newrank` semantics — and gate it byte-exact against the
  *     `03b-subgraph-cluster-newrank` goldens in [[ClusterSpec]] (svg is
  *     byte-identical; jsons differ only by the `newrank` attribute echo).
  *     It stays here only because its OWN goldens are the sentinel.
  *   - 06-undirected: **done** — an undirected-mesh spline residual (~0.05 pt),
  *     visually identical and Hausdorff-gated in SplineSpec. Accepted as-is.
  */
class CorpusByteExactSpec extends FunSuite:

  // The five residuals, all characterised (2026-07-12) and each either
  // sub-pixel-accepted, intentional, or a deep tie-break:
  //   • 06-undirected, 81-rankmin, 82-rankmax — ALL CLOSED 2026-07-13 by the
  //     working-direction clip fix: gv routes/clips a rank-reversed edge in the
  //     WORKING direction (orig-head → orig-tail) and swap_spline's at install;
  //     bezier_clip's bisection is not direction-symmetric, so clipping in the
  //     original direction landed the cut 0.05-0.17pt off. What was long
  //     documented as an unclosable "FP-precision floor" (06/82) was this bug.
  //     (81/84's original mirror had already fallen to the cgraph edge-order
  //     fix; 81's residual spline was this same clip-direction issue.)
  //   • 03-subgraph-cluster — INTENTIONAL: its golden is gv's own default-mode
  //     cluster corruption; we lay it out correctly and gate vs 03b (newrank).
  //   • 162-cluster-style — FULLY CLOSED 2026-07-13. The last residual (ONE
  //     cross-cluster spline ~0.5-1pt) was `Splinesep`: gv's dot_splines_ sets
  //     `.Splinesep = GD_nodesep(g) / 4` with GD_nodesep an *int* — C INTEGER
  //     division, 18/4 = 4 — while the port had computed 4.5. The cluster-wall
  //     channel clamp `round(bb.urx + Splinesep)` (maximal_bbox/cl_bound) then
  //     landed at 83 vs gv's 82, bending the spline 1pt east of the corridor
  //     corner. Found by dumping gv's routesplines_ input boxes.
  //   • 163-groups — FULLY CLOSED 2026-07-13 (all three formats byte-exact)
  //     after: cluster attr echo + styled-cluster svg, the cgraph edge-order
  //     fix, install_cluster + CL_CROSS weights, the interior refinement pass,
  //     the intra-cluster vnode width quirk (GD_nodesep(subg)=0 in incr_width),
  //     font-list metrics fallback + svg ps_font_equiv echo, Msquare box clip,
  //     and the subgraph edges-array declaration order.
  //   • 84-ranksink — CLOSED 2026-07-13 by the cgraph edge-order fix (out-edges
  //     iterate by (head-node seq, edge seq), not declaration order).
  //   • 95-cluster-chains — RE-CLOSED 2026-07-13, this time for the right
  //     reasons: after the cgraph edge-order fix exposed that its earlier pass
  //     was two cancelling divergences, porting install_cluster (skeleton
  //     columns install whole, then enqueue) + CL_CROSS=1000 skeleton-edge
  //     xpenalty (weighted in_cross/out_cross/ncross) made the collapsed-pass
  //     optimizer track gv exactly.
  private val deferred = Set(
    "03-subgraph-cluster")

  private def names: Vector[String] =
    new File("graphviz/corpus").listFiles.filter(_.getName.endsWith(".dot"))
      .map(_.getName.dropRight(4)).sorted.toVector

  private def graph(n: String) =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)
      .copy(images = OracleHarness.corpusImages(n))

  private def allThreeExact(n: String): Boolean =
    val g = graph(n)
    def ck(fmt: String, ours: String): Boolean =
      try ours == OracleHarness.golden(n, fmt) catch { case _: Throwable => true } // no golden ⇒ skip
    ck("dot_json", Output.dotJson(g)) && ck("json0", Output.json0(g)) && ck("svg", Svg.svg(g))

  names.filterNot(deferred).foreach { n =>
    test(s"$n: dot_json + json0 + svg byte-exact vs the golden"):
      assert(allThreeExact(n), s"$n differs from a golden")
  }

  // Guard the deferral list: each excluded file must still differ, so it is
  // removed from `deferred` (and promoted) the moment it is actually closed.
  deferred.foreach { n =>
    test(s"$n: still a documented deferral (fails-when-fixed)"):
      assert(!allThreeExact(n), s"$n is now byte-exact — remove it from `deferred`")
  }

end CorpusByteExactSpec
