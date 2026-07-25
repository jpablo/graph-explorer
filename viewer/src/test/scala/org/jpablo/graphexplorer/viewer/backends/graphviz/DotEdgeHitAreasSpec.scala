package org.jpablo.graphexplorer.viewer.backends.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElement}
import org.jpablo.graphexplorer.viewer.models.ArrowId
import org.scalajs.dom

/** DOT-mode edge hit halos (Graphviz.addEdgeHitAreas): an invisible wide clone of the
  * spline INSIDE each g.edge group. Resolution rides `closest("g.edge")`, so the halo
  * needs no id plumbing — but it must be appended AFTER the rendered spline, because
  * `querySelector("path")` on the group (endpoint-drag preview, extractArrowId
  * fallback) must keep returning the real one.
  */
class DotEdgeHitAreasSpec extends FunSuite:

  /** A trimmed Graphviz SVG: one edge group with spline, arrowhead and label. */
  private def fixture(): dom.svg.SVG =
    val svgText =
      """<svg xmlns="http://www.w3.org/2000/svg">
        |  <g class="edge" id="arrow:a-&gt;b/1">
        |    <title>a-&gt;b</title>
        |    <path d="M0,0C10,10 20,20 30,30" stroke="black" stroke-dasharray="5,2"/>
        |    <polygon points="30,30 28,24 34,26" stroke="black"/>
        |    <text x="15" y="12">f</text>
        |  </g>
        |</svg>""".stripMargin
    dom.DOMParser()
      .parseFromString(svgText, dom.MIMEType.`image/svg+xml`)
      .documentElement
      .asInstanceOf[dom.svg.SVG]

  test("addEdgeHitAreas appends an invisible halo inside the edge group"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)

    val group = svg.querySelector("g.edge")
    val halo  = group.querySelector(s"path.${SelectableElement.hitAreaClass}")
    assert(halo != null, "the edge group must gain a halo path")
    assertEquals(halo.getAttribute("d"), "M0,0C10,10 20,20 30,30")
    assert(halo.getAttribute("style").contains("stroke:transparent"))
    assert(halo.getAttribute("style").contains("pointer-events:stroke"))
    // dashed edges: the clone must hit-test along the whole curve, not just the dashes
    assert(halo.getAttribute("style").contains("stroke-dasharray:none"))
    assert(!halo.hasAttribute("id"), "the halo must not duplicate the group's child ids")

  test("querySelector(path) on the group still returns the rendered spline"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)

    val group = svg.querySelector("g.edge")
    val first = group.querySelector("path")
    assert(
      !first.classList.contains(SelectableElement.hitAreaClass),
      "the halo must be appended AFTER the spline: endpoint-drag clones the group's first path"
    )

  test("halo clicks resolve to the edge through the group, id extraction unchanged"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)

    val halo = svg.querySelector(s"path.${SelectableElement.hitAreaClass}")
    // click resolution path: target -> closest(edgeSelector) -> extractArrowId
    val resolved = halo.closest(GraphvizSelectionStrategy.edgeSelector)
    assertEquals(GraphvizSelectionStrategy.extractArrowId(resolved), ArrowId("a->b/1"))

  test("the halo does not add a selectable element"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)
    // findAll collects g.edge groups; the halo lives inside one and must not add another
    assertEquals(SelectableElement.findAll(svg, GraphvizSelectionStrategy).size, 1)
