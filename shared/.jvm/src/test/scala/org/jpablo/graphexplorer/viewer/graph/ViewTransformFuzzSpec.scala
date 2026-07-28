package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotJsonToViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

import java.io.File
import scala.io.Source

/** The corpus guarantees byte-exactness on FIXED inputs; the app's view
  * operations (hide, collapse) are graph-shape GENERATORS that reach shapes no
  * corpus file has — which is exactly where the `key not found: -1` crash
  * lived (a collapse lifted a labelled flat edge onto the top rank; a hide
  * could have done the same). This spec walks that generator space: seeded
  * hide/collapse combinations over real clustered diagrams, each fed through
  * the app's own path (transform → serialize → layout), asserting the engine
  * renders. Deterministic — a failure names its file, seed, and inputs, and
  * reproduces exactly.
  */
class ViewTransformFuzzSpec extends FunSuite:

  private lazy val repoRoot: File =
    Iterator
      .iterate(File(sys.props("user.dir")).getCanonicalFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => File(d, "build.sbt").isFile)
      .getOrElse(sys.error("repo root not found"))

  private def corpus(name: String): String =
    val f = File(repoRoot, s"graphviz/corpus/$name.dot")
    val s = Source.fromFile(f, "UTF-8")
    try s.mkString
    finally s.close()

  private def load(text: String): ViewerGraph =
    val result = ScalaGraphviz.renderFormats(text, Seq("dot_json"))
    val json   = result.output.getOrElse("dot_json", fail(s"parse failed: ${result.errors.mkString("; ")}"))
    dotJsonToViewerGraph(read[SimpleGraph](json))

  /** Deterministic 64-bit LCG (Knuth MMIX constants). */
  private final class Rng(seed: Long):
    private var s = seed
    def next(): Long = { s = s * 6364136223846793005L + 1442695040888963407L; s }
    /** uniform in [0, 1) */
    def double(): Double = ((next() >>> 11).toDouble) / (1L << 53).toDouble
    def pick[A](xs: Vector[A], fraction: Double): Vector[A] =
      xs.filter(_ => double() < fraction)

  /** A spread of clustered shapes: nested clusters, chains through clusters,
    * cluster styles, groups, and the two big real-world graphs.
    */
  private val files = Vector(
    "03-subgraph-cluster",
    "94-cluster-contig",
    "95-cluster-chains",
    "96-nested-cluster",
    "162-cluster-style",
    "163-groups",
    "177-genetic-programming",
    "191-scala-type-graph",
    "192-rank-gap-callgraph"
  )

  private val CombosPerFile = 4

  for name <- files do
    test(s"$name: baseline + $CombosPerFile seeded hide/collapse combos render"):
      val graph  = load(corpus(name))
      val nodes  = graph.nodeIds.toVector.sortBy(_.value)
      val groups = (graph.groupIds - ViewerGraphElements.defaultRootId).toVector.sortBy(_.value)

      def render(hidden: Set[NodeId], collapsed: Set[GroupId], what: String): Unit =
        val visible = graph.toVisibleGraph(ElementIds(hidden.toSet[ElementId]), collapsed)
        val text    = viewerGraphToText(visible, omitInternal = true)
        val result  = ScalaGraphviz.renderFormats(text, Seq("svg"))
        assert(
          result.errors.isEmpty,
          s"""$name [$what] render failed: ${result.errors.map(_.message).mkString("; ")}
             |hidden (${hidden.size}): ${hidden.toVector.sortBy(_.value).take(20).map(_.value).mkString(",")}
             |collapsed: ${collapsed.toVector.sortBy(_.value).map(_.value).mkString(",")}
             |--- serialized ---
             |$text""".stripMargin
        )

      // baseline: the round trip itself, before any transform
      render(Set.empty, Set.empty, "baseline")
      // every group collapsed at once — the heaviest deterministic case
      render(Set.empty, groups.toSet, "collapse-all")

      for it <- 0 until CombosPerFile do
        val rng       = Rng(name.hashCode.toLong * 1000003L + it)
        val hidden    = rng.pick(nodes, 0.10 + 0.15 * rng.double()).toSet
        val collapsed = rng.pick(groups, 0.5).toSet
        render(hidden, collapsed, s"seed=$it")
