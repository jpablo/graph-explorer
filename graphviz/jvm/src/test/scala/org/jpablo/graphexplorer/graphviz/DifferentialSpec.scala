package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

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
  * the writer-level gate. The [[OracleHarness.deferredCorpus]] files (see its
  * doc) are asserted here only to degrade gracefully (return, never throw);
  * facade error-contract tests (malformed DOT, unsupported format) live in
  * [[GraphvizSpec]].
  */
class DifferentialSpec extends FunSuite:

  // Byte-exact end-to-end through the public facade for every non-image,
  // non-deferred corpus file — the viewer's exact call path.
  OracleHarness.corpusNames
    .filterNot(n => OracleHarness.deferredCorpus(n) || OracleHarness.hasImages(n))
    .foreach { name =>
      test(s"$name: Graphviz.renderFormats byte-exact (dot_json + json0 + svg)"):
        val r = Graphviz.renderFormats(OracleHarness.corpusSource(name), Seq("dot_json", "json0", "svg"))
        assertEquals(r.status, "success", r.errors.toString)
        assertEquals(r.output.keySet, Set("dot_json", "json0", "svg"))
        assertEquals(r.output("dot_json"), OracleHarness.golden(name, "dot_json"), s"$name dot_json")
        assertEquals(r.output("json0"), OracleHarness.golden(name, "json0"), s"$name json0")
        assertEquals(r.output("svg"), OracleHarness.golden(name, "svg"), s"$name svg")
    }

  test("deferred corpus files return through the facade (smoke: never throws)"):
    OracleHarness.deferredCorpus.foreach { name =>
      val r = Graphviz.renderFormats(OracleHarness.corpusSource(name), Seq("dot_json", "json0", "svg"))
      assert(r.status == "success" || r.status == "failure", s"$name returned a status")
    }

end DifferentialSpec
