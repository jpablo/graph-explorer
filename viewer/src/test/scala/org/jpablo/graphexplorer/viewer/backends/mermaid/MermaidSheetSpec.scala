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
