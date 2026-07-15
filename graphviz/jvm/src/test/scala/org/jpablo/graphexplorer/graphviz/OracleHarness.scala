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
    *     gate byte-exact vs the 03b goldens in ClusterSpec instead. */
  val deferredCorpus: Set[String] = Set("03-subgraph-cluster")

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
