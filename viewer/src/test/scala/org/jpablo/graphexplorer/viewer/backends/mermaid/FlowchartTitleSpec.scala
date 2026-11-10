package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A canvas edit keeps the title of a flowchart, and adds no title.
  *
  * A canvas edit prints the whole graph again, and the new text replaces the
  * source. The printer writes the front-matter `title:` from the graph label.
  * Thus the label must be the title that Mermaid draws: no less, no more, and
  * in a form that the YAML of the front matter keeps.
  *
  * These tests use the real Mermaid backend in the browser.
  */
class FlowchartTitleSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  private val titled =
    """---
      |title: My chart
      |---
      |flowchart TD
      |  A --> B""".stripMargin

  /** In the Playwright runner, a munit failure arrives with an empty message.
    * The console reaches the log, and an `AssertionError` keeps its message.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then
      println(s"[FlowchartTitleSpec] $clue")
      throw new AssertionError(clue)

  private def waitFor(condition: => Boolean, description: => String, timeoutMs: Int = 15000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        val message = s"Timed out after ${timeoutMs}ms waiting for $description"
        println(s"[FlowchartTitleSpec] $message")
        p.tryFailure(new AssertionError(message))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** The text of the title that Mermaid draws above the flowchart, or `None`
    * when Mermaid draws no title. Mermaid reads the front matter as YAML here,
    * so this shows what the front matter means, not what our scan reads.
    */
  private def drawnTitle(text: String): Future[Option[String]] =
    MermaidBackend()
      .textToSvg(text)
      .recover { case e => throw new AssertionError(s"Mermaid cannot draw this source: ${e.getMessage}\n$text") }
      .map: drawing =>
        Option(drawing.svg.ref.querySelector(".flowchartTitleText")).map(_.textContent.trim).filter(_.nonEmpty)

  /** Parse `source` in a viewer, add a node on the canvas, and give the new source. */
  private def editOnCanvas(id: String, source: String): Future[String] =
    Graphviz.build().flatMap: graphviz =>
      val state = ViewerState(ViewTarget.diagram(id), graphviz, initialSource = Some(source))
      waitFor(state.fullGraphNow().nodes.contains(NodeId("B")), "the Mermaid parse")
        .flatMap: _ =>
          afterMicrotasks:
            val nodes = state.fullGraphNow().nodes.keys
            check(nodes.size == 2, s"precondition: the graph has the nodes A and B only, but has $nodes")
            state.addNodeWithSmartConnection()
        .flatMap: _ =>
          waitFor(state.sourceText.now() != source, "the canvas edit to replace the source")
        .flatMap: _ =>
          afterMicrotasks:
            val text = state.sourceText.now()
            check(state.fullGraphNow().nodes.size == 3, s"the canvas edit did not add a node:\n$text")
            text

  /** The source of a flowchart A --> B, with `title` as the front-matter line. */
  private def withTitleLine(titleLine: String): String =
    s"---\n$titleLine\n---\nflowchart TD\n  A --> B"

  // ------------------------------------------------- the front-matter title

  test("the parsed graph has the front-matter title as its label"):
    MermaidBackend()
      .textToGraph(titled)
      .map: graph =>
        val text = MermaidBackend().graphToText(graph, omitInternal = false)
        check(text.contains("title: My chart"), s"the printed graph has no title:\n$text")

  test("a canvas edit on a flowchart keeps its front-matter title"):
    editOnCanvas("flowchart-title", titled).map: text =>
      check(text.contains("title: My chart"), s"the canvas edit removed the title:\n$text")

  // ------------------------------------------------- a title that needs quotes

  /** A title with `: ` or ` #` must stay in quotes. In plain YAML, `: ` makes
    * a second mapping, and ` #` starts a comment.
    */
  private def keepsQuotedTitle(id: String, title: String): Future[Unit] =
    val source = withTitleLine(s"title: \"$title\"")
    drawnTitle(source)
      .map: drawn =>
        check(drawn.contains(title), s"precondition: Mermaid draws the title '$title', but draws $drawn")
      .flatMap(_ => editOnCanvas(id, source))
      .flatMap: text =>
        drawnTitle(text).map: drawn =>
          check(drawn.contains(title), s"after the canvas edit, Mermaid draws $drawn, not '$title':\n$text")

  test("a canvas edit keeps a front-matter title that has a colon"):
    keepsQuotedTitle("flowchart-title-colon", "Deps: v2")

  test("a canvas edit keeps a front-matter title that has a hash sign"):
    keepsQuotedTitle("flowchart-title-hash", "Bug #1")

  // ------------------------------------------------- the accessible title

  test("a canvas edit does not make an accTitle into a visible title"):
    // `accTitle` is for screen readers. Mermaid does not draw it above the
    // flowchart, so a canvas edit must not print it as a front-matter title.
    val source = "flowchart TD\n  accTitle: Service map\n  A --> B"
    drawnTitle(source)
      .map: drawn =>
        check(drawn.isEmpty, s"precondition: Mermaid draws no title for an accTitle, but draws $drawn")
      .flatMap(_ => editOnCanvas("flowchart-acc-title", source))
      .flatMap: text =>
        check(!text.contains("title: Service map"), s"the canvas edit printed the accTitle as a front-matter title:\n$text")
        drawnTitle(text).map: drawn =>
          check(drawn.isEmpty, s"after the canvas edit, Mermaid draws the title $drawn:\n$text")
