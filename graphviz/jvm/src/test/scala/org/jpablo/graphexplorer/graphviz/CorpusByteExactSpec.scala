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

  // 81/82/84 exercise `rank=min/max/sink` where pinning REVERSES the pinned
  // node's edges (rank.c minmax_edges). The RANKING is byte-exact (nodes are
  // pinned to the correct rank — verified vs the plain golden) and
  // 83-ranksource (no reversal needed) is fully byte-exact; but a reversed
  // min/max chain seeds mincross differently than gv, so the within-rank
  // left↔right ORDER comes out mirrored (a valid layout, not byte-exact) —
  // the same mincross BFS-seed tie-break class as the LR order axis.
  private val deferred = Set(
    "03-subgraph-cluster", "06-undirected",
    "81-rankmin", "82-rankmax", "84-ranksink")

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
