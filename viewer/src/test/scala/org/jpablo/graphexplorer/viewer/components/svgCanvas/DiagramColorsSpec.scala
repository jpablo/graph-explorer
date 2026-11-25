package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.color.{CssColor, DarkRemap, Oklab, Srgb}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The colors of a diagram on a dark theme (DiagramColors).
  *
  * The test page has no stylesheet of the app. A test that needs the theme sets
  * the CSS variables of the theme on the document itself.
  */
class DiagramColorsSpec extends FunSuite:

  private def lab(text: String): Oklab = Oklab.fromSrgb(CssColor.parse(text).get)

  /** The paper and the ink of the daisyUI `dark` theme. */
  private val dark = DarkRemap(paper = lab("#1d232a"), ink = lab("#ecf9ff"))

  private def css(c: Srgb) = c.css

  /** A drawing with each kind of color: Graphviz attributes, a label with no
    * fill, an inline style, a Mermaid `<style>`, and a gradient reference.
    */
  private val drawing =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
      |<style>#m .node rect{fill:#ECECFF;stroke:#9370DB;stroke-width:1px;}#face:hover{color:red}</style>
      |<g class="graph">
      |<polygon id="bg" fill="white" stroke="none" points="0,0 100,0 100,100"/>
      |<text id="label" x="10" y="10">a</text>
      |<ellipse id="node" fill="lightblue" stroke="black" cx="5" cy="5" rx="1" ry="1"/>
      |<rect id="styled" style="fill: #ff0000; stroke-width: 2px" width="1" height="1"/>
      |<path id="grad" fill="url(#face)" d="M0 0"/>
      |<g class="node"><title>r</title><ellipse fill="red" stroke="black" cx="50" cy="50" rx="9" ry="9"/><text id="onred" fill="white" x="50" y="50">r</text></g>
      |<g class="node"><title>y</title><ellipse fill="yellow" stroke="black" cx="80" cy="50" rx="9" ry="9"/><text id="onyellow" x="80" y="50">y</text></g>
      |</g>
      |</svg>""".stripMargin

  private def fresh(): dom.svg.SVG = parseSVG(drawing).ref

  /** The markup with each inline style in the form that the browser writes. A
    * `setProperty` writes `#ff0000` as `rgb(255, 0, 0)`: the color is the same.
    */
  private def canonical(svg: dom.Element): String =
    val copy = svg.cloneNode(deep = true).asInstanceOf[dom.Element]
    val all  = copy.querySelectorAll("[style]")
    (0 until all.length).map(all(_)).foreach: e =>
      e.setAttribute("style", e.asInstanceOf[dom.html.Element].style.cssText)
    copy.outerHTML
  private def byId(svg: dom.Element, id: String) = svg.querySelector(s"#$id")

  test("resolve reads a named color with the canvas of the browser"):
    assertEquals(DiagramColors.resolve("lightblue").map(css), Some("#add8e6"))
    assertEquals(DiagramColors.resolve("RED").map(css), Some("#ff0000"))
    assertEquals(DiagramColors.resolve("solid"), None)
    assertEquals(DiagramColors.resolve("none"), None)
    assertEquals(DiagramColors.resolve("transparent"), None)

  test("paint changes the attributes, the inline style, and the style sheet"):
    val svg = fresh()
    DiagramColors.paint(svg, Some(dark))
    assertEquals(byId(svg, "bg").getAttribute("fill"), "#1d232a")
    assertEquals(byId(svg, "bg").getAttribute("stroke"), "none")
    assertEquals(byId(svg, "node").getAttribute("stroke"), "#ecf9ff")
    assertEquals(byId(svg, "node").getAttribute("fill"), css(dark(CssColor.parse("#add8e6").get)))
    assertEquals(byId(svg, "grad").getAttribute("fill"), "url(#face)")
    val styled = byId(svg, "styled").asInstanceOf[dom.html.Element].style
    assertEquals(DiagramColors.resolve(styled.getPropertyValue("fill")).map(css), Some(css(dark(Srgb(1, 0, 0)))))
    assertEquals(styled.getPropertyValue("stroke-width"), "2px")
    val sheet = svg.querySelector("style").textContent
    assert(sheet.contains(s"fill:${css(dark(CssColor.parse("#ECECFF").get))}"), sheet)
    assert(sheet.contains("#m .node rect{"), s"the selector changed: $sheet")
    assert(sheet.contains("#face:hover{"), s"the selector changed: $sheet")
    assert(sheet.contains("stroke-width:1px"), sheet)

  test("paint gives the root a default fill, so a label with no fill is light"):
    val svg = fresh()
    DiagramColors.paint(svg, Some(dark))
    assertEquals(svg.getAttribute("fill"), "#ecf9ff")
    assert(svg.hasAttribute(DiagramColors.DarkAttr))

  test("a second paint starts from the original colors"):
    val once  = fresh()
    val twice = fresh()
    DiagramColors.paint(once, Some(dark))
    DiagramColors.paint(twice, Some(dark))
    DiagramColors.paint(twice, Some(dark))
    assertEquals(twice.outerHTML, once.outerHTML)

  test("paint with None puts back the original colors, with no markers"):
    val svg      = fresh()
    val original = canonical(svg)
    DiagramColors.paint(svg, Some(dark))
    assertNotEquals(canonical(svg), original)
    DiagramColors.paint(svg, None)
    assertEquals(canonical(svg), original)

  test("lightCopy has the original colors, and the canvas keeps the dark colors"):
    val svg      = fresh()
    val original = canonical(svg)
    DiagramColors.paint(svg, Some(dark))
    val painted = svg.outerHTML
    assertEquals(canonical(DiagramColors.lightCopy(svg)), original)
    assertEquals(svg.outerHTML, painted)

  test("a label with low contrast on its fill gets the ink or the paper"):
    val svg = fresh()
    def l(text: String) = Oklab.fromSrgb(DiagramColors.resolve(text).get).l
    DiagramColors.paint(svg, Some(dark))
    val red = byId(svg, "onred").parentNode.asInstanceOf[dom.Element].querySelector("ellipse").getAttribute("fill")
    val onRed = byId(svg, "onred").getAttribute("fill")
    // White text on red becomes dark text, with low contrast. The fix gives the ink.
    assertEquals(onRed, "#ecf9ff")
    assert(math.abs(l(onRed) - l(red)) >= DiagramColors.MinContrast, s"$onRed on $red")
    // A label with no fill on a light fill: the remap alone gives contrast.
    assert(!byId(svg, "onyellow").hasAttribute("fill"), byId(svg, "onyellow").outerHTML)

  // ---- follow: the theme of the page and the setting ----

  private def nextTask(): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(0)(p.success(()))
    p.future

  private def setTheme(name: String, paper: String, ink: String): Unit =
    val root = dom.document.documentElement.asInstanceOf[dom.html.Element]
    root.style.setProperty("--color-base-100", paper)
    root.style.setProperty("--color-base-content", ink)
    root.style.setProperty("--gx-canvas-bg", paper)
    root.setAttribute("data-theme", name)

  test("follow paints on a dark theme, and puts the colors back on a light theme or with the setting off"):
    setTheme("dark", "#1d232a", "#ecf9ff")
    val enabled = Var(true)
    val drawing = parseSVG(this.drawing)
    val svg     = drawing.ref
    val box     = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    val root = render(box, drawing.amend(DiagramColors.follow(svg, enabled.signal)))
    def bg   = byId(svg, "bg").getAttribute("fill")
    val result =
      for
        _ <- nextTask()
        _ = assertEquals(bg, "#1d232a", "dark theme")
        _ = enabled.set(false)
        _ = assertEquals(bg, "white", "the setting is off")
        _ = enabled.set(true)
        _ = assertEquals(bg, "#1d232a", "the setting is on again")
        _ = setTheme("light", "#ffffff", "#1f2937")
        _ <- nextTask()
        _ = assertEquals(bg, "white", "light theme")
        _ = setTheme("night", "#0f172a", "#c8cad0")
        _ <- nextTask()
      yield assertEquals(bg, "#0f172a", "a second dark theme")
    result.andThen: _ =>
      root.unmount()
      box.remove()
      val doc = dom.document.documentElement.asInstanceOf[dom.html.Element]
      List("--color-base-100", "--color-base-content", "--gx-canvas-bg").foreach(doc.style.removeProperty)
      doc.removeAttribute("data-theme")
