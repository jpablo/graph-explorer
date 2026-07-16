package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import java.io.File

/** Byte-exact gate for the SHIPPED viewer examples — the diagrams a user
  * actually sees on the library page, read straight from the `.dot` files
  * under `viewer/src/main/resources/examples` (recursive) so the gate can
  * never drift from what the app serves.
  *
  * Scope: only examples the app routes to the Scala port — decided by the
  * SAME shared [[EngineRouting.usesDotEngine]] predicate the viewer ships.
  * Non-`dot` examples (neato/fdp/…) render via viz-js in the app and are
  * identical to the oracle by construction.
  *
  * Goldens come from `node graphviz/oracle/capture-examples.mjs` (pinned
  * viz-js 3.14.0 / Graphviz 13.0.1, engine=dot). A missing golden FAILS the
  * test (run the capture) — never silently skips.
  *
  * Divergent examples live in `deferred` — each is a REAL known gap (unlike
  * the corpus, these are found-in-the-wild inputs) and is guarded
  * fails-when-fixed so the list can't rot. Closing them is the worklist.
  */
class ExamplesByteExactSpec extends FunSuite:

  /** Known divergences (each guarded fails-when-fixed below). Re-triaged
    * 2026-07-16 with json0 diffs (the first dot_json-only triage blamed
    * everything on the bb line — dot_json carries no positions; don't repeat
    * that mistake). The translate_bb/canonical-bb transcription (GraphBB)
    * closed the bb *mechanism*; what remains per example is:
    *   - finite-state-machine — LR + edge labels: several node positions
    *     genuinely differ (canonical-X / ordering divergence, e.g. golden
    *     327.73,178.03 has no counterpart at ours). Needs an instrumented
    *     XCoord/mincross chase on LR+edgelabel graphs.
    *   - sbt-project-dependencies — 36 nodes sized height 0.69444in (50pt,
    *     golden) vs 0.81111in (58.4pt, ours): node sizing with the
    *     "Helvetica,Arial,sans-serif" font list + multi-line labels.
    *   - data-structures — record `rects` under LR come out malformed
    *     (x1 > x2, e.g. ours "35.551,226.35,8.3508,297.45") and record y
    *     positions shift: the record-rect rankdir transform is wrong.
    *   - logo — LR layout divergence (nodesep=0.42, pad: node positions
    *     differ, e.g. golden 90,84 vs ours 162,99). Needs its own chase.
    *   - html — HTML-table label example renders differently (user-reported
    *     2026-07-16; cell sizing/structure vs viz-js).
    *   - unsupported/multiple-edges-with-commas — DotParser rejects the
    *     `a -> b, c` edge-list syntax (valid DOT; viz-js renders it). */
  private val deferred = Set(
    "data-structures",
    "finite-state-machine",
    "sbt-project-dependencies",
    "logo",
    "html",
    "unsupported/multiple-edges-with-commas")

  /** Not gateable: the ORACLE itself fails on these (no golden exists).
    *   - failing/leading-newline: viz-js 3.14.0 crashes with "RuntimeError:
    *     table index is out of bounds" on dot_json/svg (hence the folder
    *     name) — there is nothing to be byte-exact against. */
  private val oracleBroken = Set(
    "failing/leading-newline")

  private lazy val examplesDir: File =
    File(OracleHarness.repoRoot, "viewer/src/main/resources/examples")
  private lazy val goldenDir: File =
    File(OracleHarness.moduleDir, "golden-examples")

  /** All shipped .dot examples, as `relative/path` names without extension. */
  private def exampleNames: Vector[String] =
    def walk(d: File, prefix: String): Vector[String] =
      Option(d.listFiles()).getOrElse(Array.empty[File]).toVector.sortBy(_.getName).flatMap { f =>
        if f.isDirectory then walk(f, s"$prefix${f.getName}/")
        else if f.getName.endsWith(".dot") then Vector(prefix + f.getName.stripSuffix(".dot"))
        else Vector.empty
      }
    walk(examplesDir, "")

  private def source(name: String): String =
    OracleHarness.readFile(File(examplesDir, s"$name.dot"))

  private def golden(name: String, fmt: String): String =
    val f = File(File(goldenDir, name), fmt)
    assert(f.isFile, s"missing golden $name/$fmt — run `node graphviz/oracle/capture-examples.mjs`")
    OracleHarness.readFile(f)

  /** dot-engine examples only — the slice the Scala port must reproduce. */
  private lazy val dotEngineExamples: Vector[String] =
    exampleNames.filterNot(oracleBroken).filter(n => EngineRouting.usesDotEngine(source(n)))

  private def allThreeExact(name: String): Boolean =
    val r = Graphviz.renderFormats(source(name), Seq("dot_json", "json0", "svg"))
    r.status == "success" &&
      Seq("dot_json", "json0", "svg").forall(f => r.output(f) == golden(name, f))

  dotEngineExamples.filterNot(deferred).foreach { name =>
    test(s"example $name: renderFormats byte-exact (dot_json + json0 + svg)"):
      val r = Graphviz.renderFormats(source(name), Seq("dot_json", "json0", "svg"))
      assertEquals(r.status, "success", r.errors.toString)
      assertEquals(r.output("dot_json"), golden(name, "dot_json"), s"$name dot_json")
      assertEquals(r.output("json0"), golden(name, "json0"), s"$name json0")
      assertEquals(r.output("svg"), golden(name, "svg"), s"$name svg")
  }

  // Guard the deferral list: each entry must still differ, so it is removed
  // (and promoted into the gate) the moment it is actually closed.
  deferred.foreach { name =>
    test(s"example $name: still a documented deferral (fails-when-fixed)"):
      assert(!allThreeExact(name), s"example $name is now byte-exact — remove it from `deferred`")
  }

end ExamplesByteExactSpec
