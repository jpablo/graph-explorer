package org.jpablo.graphexplorer.graphviz

import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.{AttrResolver, RGraph}
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

import java.io.File
import scala.io.Source

/** JVM-only test infrastructure for differential testing against the pinned
  * `@viz-js/viz` oracle (Graphviz 13.0.1). Locates the corpus/golden trees and
  * provides tolerance-aware numeric comparison for later layout milestones.
  *
  * Goldens are produced by `node graphviz/oracle/capture.mjs` (PORT.md §2.1).
  */
object OracleHarness:

  /** Repo root = nearest ancestor of cwd containing `build.sbt`. Robust to
    * whichever sub-project's baseDirectory sbt uses as the working dir.
    */
  lazy val repoRoot: File =
    Iterator
      .iterate(File(sys.props("user.dir")).getCanonicalFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => File(d, "build.sbt").isFile)
      .getOrElse(sys.error("repo root (build.sbt) not found"))

  lazy val moduleDir: File = File(repoRoot, "graphviz")
  lazy val corpusDir: File = File(moduleDir, "corpus")
  lazy val goldenDir: File = File(moduleDir, "golden")

  def readFile(f: File): String =
    val s = Source.fromFile(f, "UTF-8"); try s.mkString
    finally s.close()

  /** All corpus base names (e.g. `01-minimal`), sorted. */
  def corpusNames: List[String] =
    Option(corpusDir.listFiles())
      .getOrElse(Array.empty[File])
      .filter(_.getName.endsWith(".dot"))
      .map(_.getName.stripSuffix(".dot"))
      .toList
      .sorted

  def corpusSource(name: String): String = readFile(File(corpusDir, s"$name.dot"))

  /** Parse + resolve a corpus file — the pipeline entry every spec needs —
    * with the image sidecar applied when one exists (a no-op otherwise). */
  def corpusGraph(name: String): RGraph =
    AttrResolver.resolve(DotParser.parse(corpusSource(name)).toOption.get)
      .copy(images = corpusImages(name))

  /** Whether a corpus file has an `<name>.images.json` size sidecar. */
  def hasImages(name: String): Boolean = File(corpusDir, s"$name.images.json").isFile

  /** Corpus files excluded from the byte-exact gates. The single home of the
    * deferral list — CorpusByteExactSpec (which also guards each entry with a
    * fails-when-fixed test) and DifferentialSpec both read it.
    *   - 03-subgraph-cluster: its goldens are gv's own DEFAULT-mode cluster
    *     corruption; we lay it out correctly under `newrank` semantics and
    *     gate byte-exact vs the 03b goldens in ClusterSpec instead.
    *   - 191-scala-type-graph: OPEN WORK, added 2026-07-26. A user diagram
    *     (10 clusters, HTML-table nodes with ports, rankdir=LR). It arrived
    *     failing on the cluster-LABEL subsystem — each cluster sets its own
    *     `fontsize=11`, `labeljust=l`, `style="filled,rounded"`, which nothing
    *     else in the corpus does — and that half is CLOSED (2026-07-27): the
    *     label box is measured in the cluster's own font, a flipped root
    *     reserves its label space on the canonical X axis (`contain_nodes`
    *     side borders) and its label WIDTH in rank space (`adjustRanks`/
    *     `adjustSimple`), `lp` comes from `place_flip_graph_label` with
    *     `labeljust`, and the svg emits `round_corners`' bezier path,
    *     colxlate'd paint and the cluster's own font-size. All ten clusters
    *     now match the golden on `lwidth`/`lheight`, on their label's offset
    *     inside their own box, and on their rank-axis span — pinned by three
    *     position-independent tests in [[ClusterSpec]].
    *     RANKING is closed too (2026-07-27): the dot1 cluster-interior
    *     `acyclic` was seeding its DFS in declaration order, but gv runs
    *     `decompose(subg,0)` BEFORE `acyclic(subg)`, so the seeds walk the
    *     decompose order — which is what picks the other back edge of 191's
    *     SignatureLayoutType -> FieldSpecType -> SignatureLayoutCompanion
    *     triangle. All 32 nodes now land on gv's ranks (RankSpec).
    *     MINCROSS is closed down to one structural difference (2026-07-27,
    *     instrumented-gv chase — PORT.md §0): the collapsed skeleton pass now
    *     reproduces gv's order and crossing trajectory EXACTLY, after (a)
    *     building the collapsed adjacency in class2 EMISSION order (gv creates
    *     an inter-cluster chain while processing the original edge, so the
    *     leader's ND_out is in edge order, not chain-owner order — worth
    *     exactly one crossing here, 5041 vs 5040) and (b) porting reorder's
    *     `###` sawclust rule. The cluster blocks now land in gv's within-rank
    *     order (pinned in [[ClusterSpec]]) and 8 of 10 cluster boxes have the
    *     right SIZE.
    *     LAZY CLUSTER EXPANSION landed 2026-07-27: `orderClustered` now loops
    *     expand-then-refine one cluster at a time over a live `CNode`
    *     adjacency, like gv's mincross_clust loop, and 191's first cluster
    *     refine starts at gv's exact 52 crossings (was 237). Also fixed with
    *     it: the interior mincross_step's boundary sweep ranks, the
    *     transpose out_cross gate, and the VAL (MC_SCALE*order + port) median
    *     key. The interior step is CLOSED too, by two more fixes: an edge end
    *     with NO port spec carries `port.order` 0, not MC_SCALE/2 (128 is what
    *     compassPort gives a SPECIFIED port resolving to the node centre), and
    *     in_cross/out_cross are ED_xpenalty-WEIGHTED with a port-p.x tie-break.
    *     EVERY crossing count now matches gv end to end — the collapsed pass
    *     step for step, and all ten cluster refines iteration for iteration —
    *     and 5 of 6 ranks have gv's exact within-rank order (ClusterSpec).
    *     The XCoord lead proved a red herring: instrumenting set_xcoords
    *     showed gv's canonical x IS integral and its final offset IS uniform
    *     (-51.1574) - the golden's apparent two fractional families (499.16 vs
    *     2003.20) are just %.5g formatting - and a large group of our nodes
    *     already carries gv's exact canonical x. The residue is still
    *     mincross: a THIRD copy of `medians` (in ReMincross) was keyed on raw
    *     order instead of VAL. Fixing it made that pass shadow gv to within
    *     ONE crossing per iteration; it had been converging to 119, BETTER
    *     than gv's 124, i.e. differently constrained.
    *     MINCROSS IS CLOSED (2026-07-27): every rank's within-rank order now
    *     matches gv exactly (gated in ClusterSpec). The last two were the
    *     cluster-interior `transpose` missing gv's `candidate` gating (which
    *     decides ties by changing the visit order), and `medians` missing its
    *     `flat_mval`/`hasfixed` tail — 191's PredictorView/PredictorState have
    *     NO fast edges at all, only FLAT ones, so nothing seated them and they
    *     sat where they started.
    *     XCoord chase started 2026-07-27. `selfRightSpace` was reading port
    *     sides from the COMPASS only, so a named record/HTML port came out as
    *     side 0 and the test flipped — 191's SignatureLayoutType self-loop
    *     claimed 28.8pt of right-hand space gv does not give it (and Coord's
    *     side-bit constants did not match geom.h either). With it resolved
    *     through PortAnchor's GvPort: all 22 real-to-real aux constraints
    *     match gv, canonical x is exact for 11 of 32 nodes (was 1), and 9 of
    *     10 cluster boxes are exactly gv's size.
    *     What REMAINS is one cluster: programs_para's canonical-x extent is
    *     108pt short (709 vs 817), and its members plus everything downstream
    *     shift with it. bb 2329.1x2200 vs 2329.1x2308.2, rank axis exact. */
  val deferredCorpus: Set[String] = Set("03-subgraph-cluster", "191-scala-type-graph")

  /** Image-dimension sidecar for a corpus file (`<name>.images.json`), mirroring
    * the `images` option passed to viz-js at capture time. Returns natural sizes
    * (points) keyed by `src`. A `px` value is converted to points (× 72/96);
    * `pt`/bare stay as-is. Empty when there is no sidecar. */
  def corpusImages(name: String): Map[String, org.jpablo.graphexplorer.graphviz.html.ImageDim] =
    import org.jpablo.graphexplorer.graphviz.html.ImageDim
    val f = File(corpusDir, s"$name.images.json")
    if !f.isFile then Map.empty
    else
      def toPt(v: String): Double =
        val num = v.replaceAll("[a-zA-Z]+\\s*$", "").trim.toDouble
        if v.trim.toLowerCase.endsWith("px") then num * ImageDim.Scale else num
      ujson.read(readFile(f)).arr.iterator.map { o =>
        o("name").str -> ImageDim(toPt(o("width").str), toPt(o("height").str))
      }.toMap

  /** A captured oracle output, e.g. `golden("01-minimal", "plain")`. */
  def golden(name: String, format: String): String =
    readFile(File(File(goldenDir, name), format))

  def metaJson: String = readFile(File(goldenDir, "_meta.json"))

  /** The Graphviz version the goldens were captured from. */
  def goldenGraphvizVersion: Option[String] =
    ujson.read(metaJson).obj.get("graphvizVersion").map(_.str)

  // ── Tolerance helpers (used from M2 onward to diff layout coordinates) ──────
  final case class Tol(abs: Double = 2.0, rel: Double = 0.02)

  def close(a: Double, b: Double, t: Tol = Tol()): Boolean =
    val diff = math.abs(a - b)
    diff <= t.abs || diff <= t.rel * math.max(math.abs(a), math.abs(b))

  // ── `plain`-golden parsing (shared by CoordSpec / XCoordSpec / …) ──────────

  /** Strip the quotes Graphviz `plain` puts around names with special chars. */
  def unquote(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else s

  // plain: `node <name> <x> <y> <w> <h> ...` (name quoted if it has spaces)
  private val PlainNode =
    """(?m)^node ("(?:[^"\\]|\\.)*"|\S+) (\S+) (\S+) """.r

  /** Node lines of a `plain` golden: name → (x, y) in inches. */
  def plainNodePositions(name: String): Map[String, (Double, Double)] =
    PlainNode
      .findAllMatchIn(golden(name, "plain"))
      .map(m => unquote(m.group(1)) -> (m.group(2).toDouble, m.group(3).toDouble))
      .toMap

  /** Symmetric Hausdorff distance between two point sets (spline-shape
    * comparison; shared by SvgSpec / OutputSpec). */
  def hausdorff(a: Vector[(Double, Double)], b: Vector[(Double, Double)]): Double =
    def dir(p: Vector[(Double, Double)], q: Vector[(Double, Double)]) =
      p.iterator.map(x => q.iterator.map(y => math.hypot(x._1 - y._1, x._2 - y._2)).min).max
    math.max(dir(a, b), dir(b, a))

  /** Assert the writers reproduce this corpus file's goldens byte-for-byte —
    * the per-file triple the feature specs re-encoded ~9 times over. */
  def assertGoldenExact(name: String, g: RGraph,
                        formats: Seq[String] = Seq("dot_json", "json0", "svg")): Unit =
    formats.foreach { fmt =>
      val ours = fmt match
        case "dot_json" => Output.dotJson(g)
        case "json0"    => Output.json0(g)
        case "svg"      => Svg.svg(g)
        case other      => sys.error(s"unknown format $other")
      munit.Assertions.assertEquals(ours, golden(name, fmt), s"$name $fmt")
    }

end OracleHarness
