package org.jpablo.graphexplorer.viewer.backends.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElement}
import org.jpablo.graphexplorer.viewer.models.ArrowId
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.scalajs.dom

/** DOT-mode edge hit halos (Graphviz.addEdgeHitAreas): an invisible wide clone of the
  * spline INSIDE each g.edge group. Resolution rides `closest("g.edge")`, so the halo
  * needs no id plumbing — but it goes in BEFORE the rendered spline, because a selected
  * edge paints it as the casing and a band drawn over the spline would tint the colour
  * the casing exists to preserve. Geometry consumers therefore name the spline by CLASS
  * ([[SelectableElement.splineSelector]]); "the group's first path" is no longer it.
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
    parseSVG(svgText).ref

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

  test("the halo goes in UNDER the spline — a casing must not paint over it"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)

    val group = svg.querySelector("g.edge")
    assert(
      group.querySelector("path").classList.contains(SelectableElement.hitAreaClass),
      "the halo comes first in the group: svg paints in document order, and a selected " +
        "edge shows this clone as the casing"
    )

  test("the spline is found by class, not by being first"):
    val svg = fixture()
    Graphviz.addEdgeHitAreas(svg)

    // What the endpoint-drag preview and the layout tween both ask for.
    val spline = svg.querySelector("g.edge").querySelector(SelectableElement.splineSelector)
    assert(!spline.classList.contains(SelectableElement.hitAreaClass))
    assertEquals(spline.getAttribute("stroke-dasharray"), "5,2", "the REAL spline keeps its dashes")

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
