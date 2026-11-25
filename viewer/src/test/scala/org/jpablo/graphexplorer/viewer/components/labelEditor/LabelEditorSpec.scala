package org.jpablo.graphexplorer.viewer.components.labelEditor

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.scalajs.dom
import upickle.default.read

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.Try

/** The editor of the label dialogs shows the label as the canvas draws it.
  *
  * Before, the dialog had only a textarea with the source of the label. An
  * HTML-like label showed as markup, and a mistake in the markup showed only
  * on the canvas, after Ok. The editor now has:
  *   - a source editor (CodeMirror), with Enter to save, Shift+Enter for a new
  *     line, and Escape to cancel, as before
  *   - a live preview, drawn with the engine of the canvas
  *   - the parse error of an HTML-like label, below the source
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class LabelEditorSpec extends FunSuite:

  override def munitTimeout = scala.concurrent.duration.Duration(20, "s")

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  private val diagram =
    """digraph G {
      |  node [shape=box, style="rounded,filled"];
      |  split [fillcolor="#EBF6F3", label=<<B>Range.split[0, 3]</B>>];
      |}""".stripMargin

  private val graph: ViewerGraph =
    ScalaGraphviz.structureJson(diagram) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => throw AssertionError(s"setup: the fixture does not parse: $err")

  /** The port draws the DOT, as the canvas does. */
  private def renderSvg(dot: String): Try[dom.svg.SVG] = Try:
    val r = ScalaGraphviz.renderFormats(dot, Seq("svg"))
    if r.status != "success" then throw Exception(r.errors.toString)
    dom.DOMParser()
      .parseFromString(r.output("svg"), dom.MIMEType.`image/svg+xml`)
      .documentElement
      .asInstanceOf[dom.svg.SVG]

  /** Completes when `cond` is true, or fails after `ms` with `what`. */
  private def eventually(ms: Double, what: => String)(cond: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = dom.window.performance.now()
    def poll(): Unit =
      if cond then p.success(())
      else if dom.window.performance.now() - start > ms then p.failure(AssertionError(what))
      else js.timers.setTimeout(20)(poll())
    poll()
    p.future

  private final class Fixture(val text: Var[String], val box: dom.html.Div, val root: RootNode):
    var saves   = 0
    var cancels = 0
    def preview: Option[dom.Element] = Option(box.querySelector(".gx-label-preview svg"))
    def previewText: String          = preview.map(_.textContent).getOrElse("")
    def error: String                = Option(box.querySelector(".gx-label-error")).map(_.textContent).getOrElse("")
    def editor: Option[dom.Element]  = Option(box.querySelector(".cm-content"))
    /** Select the tab "Source". A label that the formatted editor can edit
      * opens in the tab "Formatted" (see FormattedEditorSpec).
      */
    def showSource(): Unit =
      box.querySelector("[data-gx-tab=source]").asInstanceOf[dom.html.Element].click()
    def close(): Unit =
      root.unmount()
      box.remove()

  private def mounted(initial: String, draw: String => Try[dom.svg.SVG] = renderSvg): Fixture =
    val text = Var(initial)
    val box  = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    dom.document.body.appendChild(box)
    var fixture: Fixture = null
    val element = LabelEditor(
      text     = text,
      graph    = Signal.fromValue(graph),
      element  = Signal.fromValue(NodeId("split")),
      render   = draw,
      onSave   = () => fixture.saves += 1,
      onCancel = () => fixture.cancels += 1
    )
    fixture = Fixture(text, box, render(box, element))
    fixture

  private def keydown(target: dom.Element, key: String, shift: Boolean = false): Unit =
    val init = js.Dynamic.literal(key = key, shiftKey = shift, bubbles = true, cancelable = true)
    target.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  private def withEditor(initial: String, draw: String => Try[dom.svg.SVG] = renderSvg)(body: Fixture => Future[Unit]): Future[Unit] =
    val f = mounted(initial, draw)
    body(f).transform(r => { f.close(); r })

  test("the preview draws the label"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, s"the preview does not show Hello: '${f.previewText}'")(f.previewText.contains("Hello"))

  test("the preview follows the text"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, "the first preview does not come")(f.previewText.contains("Hello")).flatMap: _ =>
        f.text.set("<I>World</I>")
        eventually(3000, s"the preview does not show World: '${f.previewText}'")(f.previewText.contains("World"))

  test("an HTML error shows below the source, and goes away when the label is correct"):
    withEditor("<B>Hello"): f =>
      eventually(3000, s"no error names <B>: '${f.error}'")(f.error.toLowerCase.contains("<b>")).flatMap: _ =>
        f.text.set("<B>Hello</B>")
        eventually(3000, s"the error stays: '${f.error}'")(f.error.trim.isEmpty)

  test("a plain label has no HTML error"):
    withEditor("a < b"): f =>
      eventually(3000, s"the preview does not come: '${f.previewText}'")(f.previewText.contains("a < b")).map: _ =>
        check(f.error.trim.isEmpty, s"a plain label shows an error: '${f.error}'")

  test("the source is a CodeMirror editor with the text"):
    withEditor("<B>Hello</B>"): f =>
      f.showSource()
      eventually(3000, "the editor has no CodeMirror content")(f.editor.isDefined).map: _ =>
        check(f.editor.exists(_.textContent.contains("<B>Hello</B>")), s"the editor does not show the text: ${f.editor.map(_.textContent)}")

  test("Enter saves, Shift+Enter does not save, and Escape cancels"):
    withEditor("<B>Hello</B>"): f =>
      f.showSource()
      eventually(3000, "the editor has no CodeMirror content")(f.editor.isDefined).map: _ =>
        val content = f.editor.get
        keydown(content, "Enter", shift = true)
        check(f.saves == 0, "Shift+Enter saved the label")
        keydown(content, "Enter")
        check(f.saves == 1, s"Enter did not save the label (saves: ${f.saves})")
        keydown(content, "Escape")
        check(f.cancels == 1, s"Escape did not cancel (cancels: ${f.cancels})")

  // The checks below are for the layout of the preview, the failure of a
  // render, and the focus of the editor.

  /** The size of `e` on the page, in CSS pixels. */
  private def size(e: dom.Element): (Double, Double) =
    val r = e.getBoundingClientRect()
    (r.width, r.height)

  /** The width attribute of a Graphviz SVG (in points), in CSS pixels. */
  private def naturalWidth(e: dom.Element): Double =
    e.getAttribute("width").stripSuffix("pt").toDouble * 4 / 3

  test("a tall label scales down to the maximum height, and keeps its aspect"):
    val tall = (1 to 40).map(i => s"line $i").mkString("<B>", "<BR/>", "</B>")
    withEditor(tall): f =>
      eventually(3000, "the preview does not come")(f.preview.isDefined).map: _ =>
        val svg     = f.preview.get
        val (w, h)  = size(svg)
        val viewBox = svg.getAttribute("viewBox").split(" ").map(_.toDouble)
        check(h <= 200.5, s"the preview is $h px high, more than 200 px")
        check(h > 150, s"the preview is only $h px high: it did not use the height")
        // The drawing keeps its aspect: the box of the SVG has the aspect of
        // the viewBox.
        val expected = viewBox(2) / viewBox(3)
        check(math.abs(w / h - expected) < 0.02, s"the aspect changed: ${w / h}, expected $expected")

  test("a wide label scales down to the width of the editor"):
    val wide = "<B>" + ("wide " * 60) + "</B>"
    withEditor(wide): f =>
      f.box.style.width = "300px"
      eventually(3000, "the preview does not come")(f.preview.isDefined).map: _ =>
        val (w, _) = size(f.preview.get)
        check(w <= 300.5, s"the preview is $w px wide, wider than the editor (300 px)")

  test("a small label keeps its size"):
    withEditor("<B>Hi</B>"): f =>
      eventually(3000, "the preview does not come")(f.preview.isDefined).map: _ =>
        val svg    = f.preview.get
        val (w, _) = size(svg)
        check(math.abs(w - naturalWidth(svg)) < 1, s"the preview is $w px wide, not ${naturalWidth(svg)} px")

  test("a label that does not draw keeps the last drawing, dimmed"):
    // The render fails for a label with the word "broken".
    def draw(dot: String): Try[dom.svg.SVG] =
      if dot.contains("broken") then scala.util.Failure(Exception("no drawing")) else renderSvg(dot)
    withEditor("<B>Hello</B>", draw): f =>
      def opacity = Option(f.box.querySelector(".gx-label-preview")).map(_.asInstanceOf[dom.html.Element].style.opacity).getOrElse("")
      eventually(3000, "the first preview does not come")(f.previewText.contains("Hello")).flatMap: _ =>
        check(opacity == "1", s"a good drawing is dimmed: opacity '$opacity'")
        f.text.set("<B>broken</B>")
        eventually(3000, s"the failed drawing is not dimmed: opacity '$opacity'")(opacity.nonEmpty && opacity.toDouble < 1).flatMap: _ =>
          check(f.previewText.contains("Hello"), s"the last drawing is gone: '${f.previewText}'")
          f.text.set("<B>again</B>")
          eventually(3000, s"a good drawing stays dimmed: opacity '$opacity'")(opacity == "1" && f.previewText.contains("again"))

  test("the editor has the focus, and Shift+Enter puts a line break at the end of the text"):
    withEditor("<B>Hello</B>"): f =>
      f.showSource()
      eventually(3000, "the editor has no focus")(f.editor.exists(_.contains(dom.document.activeElement))).map: _ =>
        keydown(f.editor.get, "Enter", shift = true)
        check(f.text.now() == "<B>Hello</B>\n", s"the text after Shift+Enter is '${f.text.now()}'")
        check(f.saves == 0, "Shift+Enter saved the label")

  test("the preview has no title, so it shows no tooltip"):
    withEditor("<B>Hello</B>"): f =>
      eventually(3000, "the first preview does not come")(f.previewText.contains("Hello")).map: _ =>
        val titles = f.box.querySelectorAll(".gx-label-preview title").length
        check(titles == 0, s"the preview has $titles <title> elements, which show as a tooltip")
