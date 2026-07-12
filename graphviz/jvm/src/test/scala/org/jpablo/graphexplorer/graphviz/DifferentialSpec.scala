package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import java.io.File

/** M8 end-to-end differential gate: the *full* pure pipeline reached only
  * through the public `Graphviz.renderFormats` facade — exactly the slice the
  * viewer's backend calls — diffed against the captured viz-js goldens.
  *
  * Now that the corpus is byte-exact through the writers ([[CorpusByteExactSpec]]),
  * this locks the FACADE path (parse → resolve → dispatch → error handling) so
  * it can't drift: for every non-image corpus file it must emit `dot_json` /
  * `json0` / `svg` **byte-exact**. Image files (an `<name>.images.json` sidecar
  * exists) are excluded — the facade takes only `dot: String`, no image channel,
  * so it correctly omits `<image>` elements the golden has; those are covered by
  * the writer-level gate. 03/06 are the two documented corpus residuals (03 is
  * gated vs its newrank oracle in [[ClusterSpec]]; 06 is the accepted spline
  * residual) — asserted here only to degrade gracefully (return, never throw).
  */
class DifferentialSpec extends FunSuite:

  // See CorpusByteExactSpec for why each is excluded (03 gated vs newrank
  // oracle; 06 accepted spline; 81/82/84 rank=min/max mincross-order mirror).
  private val residual = Set(
    "03-subgraph-cluster", "06-undirected",
    "81-rankmin", "82-rankmax", "84-ranksink", "93-flat-nonadj")

  private def corpusNames: Vector[String] =
    new File("graphviz/corpus").listFiles.filter(_.getName.endsWith(".dot"))
      .map(_.getName.dropRight(4)).sorted.toVector

  private def hasImages(name: String): Boolean =
    new File(s"graphviz/corpus/$name.images.json").exists()

  private def nodeNames(v: ujson.Value): Set[String] =
    v("objects").arr.iterator.map(_("name").str).toSet
  private def edgePairs(v: ujson.Value): Set[(Int, Int)] =
    v.obj.get("edges").map(_.arr.iterator.map(e => (e("tail").num.toInt, e("head").num.toInt)).toSet).getOrElse(Set.empty)

  // Byte-exact end-to-end through the public facade for every non-image,
  // non-residual corpus file — the viewer's exact call path.
  corpusNames.filterNot(n => residual(n) || hasImages(n)).foreach { name =>
    test(s"$name: Graphviz.renderFormats byte-exact (dot_json + json0 + svg)"):
      val r = Graphviz.renderFormats(OracleHarness.corpusSource(name), Seq("dot_json", "json0", "svg"))
      assertEquals(r.status, "success", r.errors.toString)
      assertEquals(r.output.keySet, Set("dot_json", "json0", "svg"))
      assertEquals(r.output("dot_json"), OracleHarness.golden(name, "dot_json"), s"$name dot_json")
      assertEquals(r.output("json0"), OracleHarness.golden(name, "json0"), s"$name json0")
      assertEquals(r.output("svg"), OracleHarness.golden(name, "svg"), s"$name svg")
  }

  test("residual corpus (03/06) degrades gracefully (returns, never throws)"):
    residual.foreach { name =>
      val r = Graphviz.renderFormats(OracleHarness.corpusSource(name), Seq("dot_json", "json0", "svg"))
      assert(r.status == "success" || r.status == "failure", s"$name returned a status")
    }

  test("unsupported format ⇒ failure, not exception"):
    val r = Graphviz.renderFormats("digraph { a -> b }", Seq("dot_json", "png"))
    assertEquals(r.status, "failure")
    assert(r.errors.exists(_.message.contains("png")), r.errors.toString)

  test("malformed DOT ⇒ failure with an error, not exception"):
    val r = Graphviz.renderFormats("digraph { a -> ", Seq("svg"))
    assertEquals(r.status, "failure")
    assert(r.errors.nonEmpty)

end DifferentialSpec
