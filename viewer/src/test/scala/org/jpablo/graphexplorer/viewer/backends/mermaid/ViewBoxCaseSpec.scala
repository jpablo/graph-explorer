package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.scalajs.dom

/** Mermaid's packet and radar renderers emit `viewbox` (lowercase) instead of `viewBox`.
  * SVG attribute names are case-sensitive, so the browser ignores the lowercase one: every
  * shape is drawn with correct coordinates, but the element declares no viewport and the
  * diagram displays as 0×0. [[MermaidBackend.normalizeRenderedSvg]] must promote the value
  * to the real attribute.
  */
class ViewBoxCaseSpec extends FunSuite:

  private def parse(svgText: String): dom.svg.SVG =
    dom.DOMParser()
      .parseFromString(svgText, dom.MIMEType.`image/svg+xml`)
      .documentElement
      .asInstanceOf[dom.svg.SVG]

  test("a lowercase viewbox (packet/radar output) is promoted to viewBox"):
    val svg = parse("""<svg xmlns="http://www.w3.org/2000/svg" width="100%" viewbox="0 0 1026 156"/>""")
    MermaidBackend.normalizeRenderedSvg(svg, None)
    assertEquals(svg.getAttribute("viewBox"), "0 0 1026 156")
    assert(!svg.hasAttribute("viewbox"), "the dead lowercase attribute must be removed")

  test("an existing proper viewBox is left untouched"):
    val svg = parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"/>""")
    MermaidBackend.normalizeRenderedSvg(svg, None)
    assertEquals(svg.getAttribute("viewBox"), "0 0 10 10")

  test("an svg with no viewport declaration at all is left alone"):
    val svg = parse("""<svg xmlns="http://www.w3.org/2000/svg"/>""")
    MermaidBackend.normalizeRenderedSvg(svg, None)
    assert(!svg.hasAttribute("viewBox"))
