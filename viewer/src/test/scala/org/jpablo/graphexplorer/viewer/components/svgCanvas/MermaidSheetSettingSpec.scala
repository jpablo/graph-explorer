package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The setting `mermaidSheet` controls the sheet behind a Mermaid drawing.
  *
  * The renderer always adds the sheet. With the setting off, a class on the
  * canvas hides the sheet, and the drawing does not render again. An export of
  * the full diagram follows the setting.
  *
  * The test page has no stylesheet of the app. Thus the tests check that the
  * class of `style.scss` (`.gx-no-sheet rect.gx-sheet`) is on an ancestor of
  * the sheet. That is the condition of the rule.
  */
class MermaidSheetSettingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "MermaidSheetSettingSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val sequence =
    """sequenceDiagram
      |  participant A as systemd
      |  participant B as updater
      |  A->>B: start service
      |  B-->>A: ok""".stripMargin

  private final class Canvas(val state: ViewerState, val box: dom.html.Element, val copied: ListBuffer[String]):
    def container: dom.Element = box.querySelector("#canvas-container")
    def svg: dom.Element       = box.querySelector("svg")
    def sheet: Option[dom.Element] =
      Option(box.querySelector(s"rect.${MermaidBackend.sheetClass}"))
    def hidden: Boolean =
      sheet.exists(_.closest(s".${MermaidBackend.noSheetClass}") != null)

  /** Show `source` in a canvas in the document, and run `body` when the canvas shows the sheet. */
  private def withMermaidCanvas(id: String)(body: Canvas => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    val copied    = ListBuffer.empty[String]
    withGraphvizAsync: graphviz =>
      val state = ViewerState(
        ViewTarget.diagram(id),
        graphviz,
        writeText = text => copied += text,
        initialSource = Some(sequence)
      )(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root   = render(box, CanvasContainer(state, commands))
      val canvas = Canvas(state, box, copied)
      Waits
        .settle(canvas.sheet.isDefined, timeoutMs = 10000)
        .flatMap: _ =>
          check(canvas.sheet.isDefined, "precondition: the canvas does not show the sheet of the Mermaid drawing")
          body(canvas)
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  test("the sheet is in the group that the canvas pans and zooms, below the drawing"):
    withMermaidCanvas("sheet-group"): canvas =>
      val sheet = canvas.sheet.get
      val group = sheet.parentNode.asInstanceOf[dom.Element]
      check(group.classList.contains("gx-canvas-root"), s"the parent of the sheet is ${group.outerHTML.take(120)}")
      check(group.parentNode eq canvas.svg, "the group of the sheet is not a child of the svg")
      check(group.firstElementChild eq sheet, "the sheet is not the first child of the group")
      Future.unit

  test("with the setting off, the canvas hides the sheet, and the drawing does not render again"):
    withMermaidCanvas("sheet-hidden"): canvas =>
      check(canvas.state.mermaidSheet.now(), "precondition: the setting is on by default")
      check(!canvas.hidden, "the canvas hides the sheet with the setting on")
      val svgBefore   = canvas.svg
      val sheetBefore = canvas.sheet.get
      canvas.state.mermaidSheet.set(false)
      afterMicrotasks:
        check(
          canvas.container.classList.contains(MermaidBackend.noSheetClass),
          s"the canvas has no class ${MermaidBackend.noSheetClass}"
        )
        check(canvas.hidden, "the sheet is not in an element with the class that hides it")
        check(canvas.svg eq svgBefore, "the drawing rendered again")
        check(canvas.sheet.exists(_ eq sheetBefore), "the sheet is not the same element")
        canvas.state.mermaidSheet.set(true)
      .flatMap: _ =>
        afterMicrotasks:
          check(!canvas.hidden, "the canvas hides the sheet after the setting is on again")

  test("an export with the setting off has no sheet, and with the setting on has one"):
    withMermaidCanvas("sheet-export"): canvas =>
      canvas.state.copyAsFullDiagramSVG()
      check(canvas.copied.size == 1, s"precondition: the export copied ${canvas.copied.size} texts")
      check(canvas.copied.last.contains(MermaidBackend.sheetClass), "the export with the setting on has no sheet")
      canvas.state.mermaidSheet.set(false)
      canvas.state.copyAsFullDiagramSVG()
      check(canvas.copied.size == 2, s"precondition: the export copied ${canvas.copied.size} texts")
      val exported = canvas.copied.last
      check(!exported.contains(MermaidBackend.sheetClass), s"the export with the setting off has a sheet: ${exported.take(300)}")
      check(exported.contains("<svg"), s"the export is not an SVG: ${exported.take(300)}")
      check(canvas.sheet.isDefined, "the export removed the sheet from the canvas")
      Future.unit
