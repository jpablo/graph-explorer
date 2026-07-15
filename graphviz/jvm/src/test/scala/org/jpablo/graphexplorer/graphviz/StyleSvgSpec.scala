package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.Svg

/** SVG styling (09-styled probe) — `fill`/`stroke`/`stroke-dasharray`/text
  * `fill` from `fillcolor`/`color`/`style`/`fontcolor`. Previously every svg
  * was black-on-none regardless of styling ⇒ styled diagrams rendered
  * unstyled through the Scala backend. 09 is TB, ellipse nodes, normal
  * arrows ⇒ geometry is byte-exact, so the whole svg is asserted byte-exact.
  */
class StyleSvgSpec extends FunSuite:

  private def svg(name: String) =
    Svg.svg(OracleHarness.corpusGraph(name))

  test("09-styled: svg byte-exact (fill / stroke / dash / fontcolor)"):
    assertEquals(svg("09-styled"), OracleHarness.golden("09-styled", "svg"))

  test("10-box: svg byte-exact (shape=box <polygon> + box edge-clipping)"):
    assertEquals(svg("10-box"), OracleHarness.golden("10-box", "svg"))

  test("01-minimal: unstyled svg stays fill=none stroke=black (no regression)"):
    val s = svg("01-minimal")
    assert(s.contains("""<ellipse fill="none" stroke="black""""), "01 nodes unstyled")
    assert(!s.contains("stroke-dasharray"), "01 has no dashes")

  test("13-rounded: svg byte-exact (style=rounded ⇒ RBCONST=12 corner <path>)"):
    assertEquals(svg("13-rounded"), OracleHarness.golden("13-rounded", "svg"))

end StyleSvgSpec
