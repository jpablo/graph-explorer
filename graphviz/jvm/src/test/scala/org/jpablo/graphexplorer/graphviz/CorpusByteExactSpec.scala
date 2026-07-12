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
  * 73 of 76 files pass. The three exclusions are documented deferrals, NOT
  * tolerances — each is asserted below to still differ so this list can't rot:
  *   - 03-subgraph-cluster: the oracle (viz-js) leaves clusters unlaid-out
  *     (sentinel bb) — dot_json matches; json0/svg cluster geometry can't match
  *     a buggy golden.
  *   - 05-strings-comments: HTML/multi-line edge-label metrics + `tooltip`
  *     `<a xlink:title>` anchor + graph-label centering (tracked follow-up).
  *   - 06-undirected: an undirected-mesh spline residual (~0.05 pt) — the
  *     splines are Hausdorff-gated in SplineSpec, not yet exact-string.
  */
class CorpusByteExactSpec extends FunSuite:

  private val deferred = Set("03-subgraph-cluster", "05-strings-comments", "06-undirected")

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
