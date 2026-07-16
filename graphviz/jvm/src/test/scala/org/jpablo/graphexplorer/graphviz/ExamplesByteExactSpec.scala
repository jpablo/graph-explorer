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

  /** Known divergences (each guarded fails-when-fixed below). Third triage
    * 2026-07-16 (position-recovered rank orderings): every remaining gap is
    * layout-ORDER/X, all sizes byte-correct after the size_html_txt
    * transcription:
    *   - finite-state-machine — within-rank ORDER IDENTICAL to gv; three
    *     nodes' canonical-X differ (LR_7/LR_8 −18 = nodesep, LR_4 −35).
    *     Pure XCoord aux-graph divergence on an LR + edge-label graph —
    *     needs the instrumented-gv xcoord dump (task #46 methodology).
    *   - data-structures — ONE rank's within-rank order differs
    *     (golden [node12,node11,node9,node7] vs ours [node11,node12,node7,
    *     node9]): a mincross divergence (records + LR); X follows the order.
    *     (Sizing + record rects already fixed.)
    *   - sbt-project-dependencies — 9/14 ranks order differently: mincross
    *     with duplicate/parallel edges (the file declares some edges 2-3×).
    *     Heights now byte-correct (50pt) after size_html_txt.
    * CLOSED 2026-07-16 (same session, in order):
    *   - unsupported/multiple-edges-with-commas — cgraph nodelist grammar
    *     (`nodelist : node | nodelist ',' node`) ported + edge endpoints
    *     declare their node in textual order (appendnode).
    *   - logo — five fixes: constraint=false edges excluded from ranking;
    *     acyclic DFS iterates out-edges in agfstout order; `pad` attr sets
    *     the svg canvas pad; arrowhead=none ⇒ no trim/ep; flat edges clip
    *     in the order-normalized WORKING direction (swap_ends_p tie-break);
    *     penwidth-inflated clip outline + stroke-width/bgcolor/hex-lowercase
    *     in svg.
    *   - html — layout had already converged; the svg HTML renderer was
    *     hardcoding FontSize/Times while the sizing honored the node's
    *     fontsize/fontname — base font now threads through htmlText/htmlTable
    *     (this was the user-reported oversized-tasks rendering). */
  private val deferred = Set(
    "data-structures",
    "finite-state-machine",
    "sbt-project-dependencies")

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
