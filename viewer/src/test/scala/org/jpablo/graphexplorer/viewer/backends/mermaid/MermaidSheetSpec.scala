package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** A Mermaid drawing has a sheet behind it, as a Graphviz drawing has.
  *
  * Graphviz puts a white polygon behind its drawing. Mermaid draws with no
  * background, so the grid of the canvas showed through. A sequence diagram
  * has mostly thin lines and small text, so it was hard to read.
  *
  * These tests draw with the real Mermaid backend in the browser, as the
  * canvas does.
  */
class MermaidSheetSpec extends FunSuite with TestHelpers:

  /** In the Playwright runner, a munit failure arrives with an empty message.
    * The console reaches the log, and an `AssertionError` keeps its message.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then
      println(s"[MermaidSheetSpec] $clue")
      throw new AssertionError(clue)

  private val sequence =
    """sequenceDiagram
      |  participant A as systemd
      |  participant B as updater
      |  Note over A,B: 18:45:00 boot
      |  A->>B: start service
      |  B-->>A: ok""".stripMargin

  private val flowchart =
    """flowchart TD
      |  A["first"] --> B["second"]""".stripMargin

  private val stateDiagram =
    """stateDiagram-v2
      |  [*] --> Idle
      |  Idle --> Busy""".stripMargin

  private def attr(e: dom.Element, name: String): Double =
    Option(e.getAttribute(name)).flatMap(_.toDoubleOption).getOrElse(Double.NaN)

  private def drawAndCheck(name: String, text: String) =
    RealMermaid.drawWithMermaid(text).map:
      case Failure(e) => check(false, s"$name: Mermaid failed: ${RealMermaid.firstLine(e)}")
      case Success(svg) =>
        dom.document.body.appendChild(svg)
        try
          val sheets = svg.querySelectorAll("rect.gx-sheet")
          check(sheets.length == 1, s"$name: ${sheets.length} sheets, expected 1")
          val sheet = sheets(0)

          // Behind all of the drawing: the first element child of the SVG that
          // is not a style or a defs element.
          val firstDrawn =
            (0 until svg.children.length)
              .map(svg.children(_))
              .find(c => !Set("style", "defs", "title", "desc").contains(c.tagName.toLowerCase))
          check(firstDrawn.contains(sheet), s"$name: the sheet is not the first drawn child: ${firstDrawn.map(_.outerHTML.take(80))}")

          // The sheet covers the full viewBox, which holds the drawing and its margin.
          val vb = svg.viewBox.baseVal
          check(
            attr(sheet, "x") == vb.x && attr(sheet, "y") == vb.y &&
              attr(sheet, "width") == vb.width && attr(sheet, "height") == vb.height,
            s"$name: sheet ${sheet.outerHTML} does not cover the viewBox ${vb.x} ${vb.y} ${vb.width} ${vb.height}"
          )

          // A click goes through the sheet, so a click on the empty sheet acts as
          // a click on the canvas (for example, it clears the selection).
          val pointerEvents = dom.window.getComputedStyle(sheet).getPropertyValue("pointer-events")
          check(pointerEvents == "none", s"$name: the sheet has pointer-events $pointerEvents")

          // The sheet has a fill.
          val fill = dom.window.getComputedStyle(sheet).getPropertyValue("fill")
          check(fill.nonEmpty && fill != "none" && !fill.contains("rgba(0, 0, 0, 0)"), s"$name: the sheet has no fill ($fill)")
        finally svg.remove()

  test("a sequence diagram has a sheet behind the drawing"):
    drawAndCheck("sequence", sequence)

  test("a flowchart has a sheet behind the drawing"):
    drawAndCheck("flowchart", flowchart)

  test("a state diagram has a sheet behind the drawing"):
    drawAndCheck("state", stateDiagram)

  /** The text of each `<style>` element of `svg`. */
  private def styleText(svg: dom.svg.SVG): String =
    val styles = svg.querySelectorAll("style")
    (0 until styles.length).map(styles(_).textContent).mkString("\n")

  private def drawn(name: String, text: String)(body: dom.svg.SVG => Unit) =
    RealMermaid.drawWithMermaid(text).map:
      case Failure(e) => check(false, s"$name: Mermaid failed: ${RealMermaid.firstLine(e)}")
      case Success(svg) =>
        dom.document.body.appendChild(svg)
        try body(svg)
        finally svg.remove()

  test("the background of the init directive is the color of the sheet, also in an export with no sheet"):
    val text = """%%{init: {"themeVariables": {"background": "#fef3c7"}}}%%""" + "\n" + flowchart
    drawn("background", text): svg =>
      val sheet = svg.querySelector("rect.gx-sheet")
      check(sheet != null, "no sheet")
      val fill = dom.window.getComputedStyle(sheet).getPropertyValue("fill")
      check(fill == "rgb(254, 243, 199)", s"the sheet has the fill $fill, not the background: ${sheet.outerHTML}")
      check(sheet.hasAttribute(MermaidBackend.backgroundAttr), s"the sheet has no background mark: ${sheet.outerHTML}")
      val exported = MermaidBackend.exportText(svg, keepSheet = false)
      check(exported.contains("gx-sheet"), "the export with no sheet dropped the background")

  test("a sheet with no background in the directive keeps the plain fill"):
    drawn("plain", flowchart): svg =>
      val sheet = svg.querySelector("rect.gx-sheet")
      check(sheet != null, "no sheet")
      check(!sheet.hasAttribute(MermaidBackend.backgroundAttr), s"a plain sheet has the background mark: ${sheet.outerHTML}")
      check(!MermaidBackend.exportText(svg, keepSheet = false).contains("gx-sheet"), "the export with no sheet kept the plain sheet")

  test("the dark theme of the directive gets a dark sheet"):
    drawn("dark", """%%{init: {"theme": "dark"}}%%""" + "\n" + flowchart): svg =>
      val sheet = svg.querySelector("rect.gx-sheet")
      check(sheet != null, "no sheet")
      val fill = dom.window.getComputedStyle(sheet).getPropertyValue("fill")
      check(fill == "rgb(51, 51, 51)", s"the dark theme has the sheet $fill")

  // MermaidBackend.ensureInitialized gives Mermaid the theme "default". The
  // directive of the source must win over it.
  test("the theme and the fonts of the directive win over the theme of the app"):
    drawn("default", flowchart): plain =>
      val plainStyle = styleText(plain)
      val text =
        """%%{init: {"theme": "dark", "themeVariables": {"fontSize": "23px", "fontFamily": "Georgia"}}}%%""" + "\n" + flowchart
      drawn("themed", text): themed =>
        val style = styleText(themed)
        check(style != plainStyle, "the dark theme draws the same style as the default theme")
        check(style.contains("font-size:23px"), s"the style has no font size 23px: ${style.take(300)}")
        check(style.contains("font-family:Georgia"), s"the style has no font Georgia: ${style.take(300)}")
