package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotJsonToViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

import java.io.File
import scala.io.Source

/** Collapse has to survive the WHOLE pipeline, not just the graph transform:
  * DOT text → ViewerGraph → collapse → DOT text → layout. The unit spec
  * (CollapseOpsSpec) covers the transform on hand-built graphs; this one runs
  * real, heavily-clustered corpus diagrams through the engine that actually
  * draws them, which is where a malformed intermediate shows up as a crash.
  */
class CollapseRenderSpec extends FunSuite:

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

  /** DOT text → ViewerGraph, the same path the app uses on load. */
  private def load(text: String): ViewerGraph =
    val result = ScalaGraphviz.renderFormats(text, Seq("dot_json"))
    val json = result.output.getOrElse("dot_json", fail(s"parse failed: ${result.errors.mkString("; ")}"))
    dotJsonToViewerGraph(read[SimpleGraph](json))

  /** Collapse `g`, re-serialize, and lay the result out — the app's own steps. */
  private def collapseAndRender(graph: ViewerGraph, g: GroupId): Unit =
    val collapsed = graph.toVisibleGraph(ElementIds(), Set(g))
    val text      = viewerGraphToText(collapsed, omitInternal = true)
    val result    = ScalaGraphviz.renderFormats(text, Seq("svg"))
    assertEquals(result.errors, Vector.empty, s"render failed for $g\n--- serialized ---\n$text")
    assertEquals(result.status, "success", s"render did not succeed for $g")

  private def eachGroup(corpusName: String): Unit =
    val graph = load(corpus(corpusName))
    val groups = graph.groups.keySet
    assert(groups.nonEmpty, s"$corpusName has no clusters to collapse")
    groups.foreach(g => collapseAndRender(graph, g))

  test("every cluster of 191-scala-type-graph collapses and still lays out"):
    eachGroup("191-scala-type-graph")

  test("every cluster of 192-rank-gap-callgraph collapses and still lays out"):
    eachGroup("192-rank-gap-callgraph")

  test("collapsing ALL clusters at once still lays out"):
    for name <- Seq("191-scala-type-graph", "192-rank-gap-callgraph") do
      val graph     = load(corpus(name))
      val collapsed = graph.toVisibleGraph(ElementIds(), graph.groups.keySet)
      val text      = viewerGraphToText(collapsed, omitInternal = true)
      val result    = ScalaGraphviz.renderFormats(text, Seq("svg"))
      assertEquals(result.errors, Vector.empty, s"$name: all-collapsed render failed\n$text")
