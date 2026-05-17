package org.jpablo.graphexplorer.graphviz

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

  /** A captured oracle output, e.g. `golden("01-minimal", "plain")`. */
  def golden(name: String, format: String): String =
    readFile(File(File(goldenDir, name), format))

  def metaJson: String = readFile(File(goldenDir, "_meta.json"))

  /** The Graphviz version the goldens were captured from. */
  def goldenGraphvizVersion: Option[String] =
    """"graphvizVersion"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(metaJson).map(_.group(1))

  // ── Tolerance helpers (used from M2 onward to diff layout coordinates) ──────
  final case class Tol(abs: Double = 2.0, rel: Double = 0.02)

  def close(a: Double, b: Double, t: Tol = Tol()): Boolean =
    val diff = math.abs(a - b)
    diff <= t.abs || diff <= t.rel * math.max(math.abs(a), math.abs(b))

  /** Pull all numeric tokens out of a line of Graphviz `plain` output. */
  def numbers(line: String): Vector[Double] =
    """-?\d+(?:\.\d+)?""".r.findAllIn(line).map(_.toDouble).toVector

end OracleHarness
