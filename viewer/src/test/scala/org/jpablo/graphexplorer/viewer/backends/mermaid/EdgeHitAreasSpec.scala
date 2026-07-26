package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.{MermaidSelectionStrategy, SelectableElement}
import org.jpablo.graphexplorer.viewer.models.ArrowId
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.scalajs.dom

/** Edge hit targets in Mermaid mode: the invisible halo clone along the path, and the
  * invisible rect over the edge LABEL. The label rect exists because label text lives in
  * a foreignObject — a click on the glyphs targets an XHTML element, which the selection
  * machinery's SVG-namespace filter drops, so the most prominent part of an edge used to
  * select nothing.
  */
class EdgeHitAreasSpec extends FunSuite:

  /** A trimmed Mermaid 11 flowchart SVG: one edge with a labelled foreignObject label.
    * data-id on g.label is mermaid's own edge-id stamp — the correlation the rect uses.
    */
  private def fixture(): dom.svg.SVG =
    val svgText =
      """<svg xmlns="http://www.w3.org/2000/svg">
        |  <g class="root">
        |    <g class="edgePaths">
        |      <path d="M0,0 C10,10 20,20 30,30" id="L_a_b_0" class="edge-thickness-normal flowchart-link"/>
        |    </g>
        |    <g class="edgeLabels">
        |      <g class="edgeLabel" transform="translate(15, 15)">
        |        <g class="label" data-id="L_a_b_0" transform="translate(-10, -5)">
        |          <foreignObject width="20" height="10">
        |            <div xmlns="http://www.w3.org/1999/xhtml"><span class="edgeLabel"><p>f</p></span></div>
        |          </foreignObject>
        |        </g>
        |      </g>
        |    </g>
        |  </g>
        |</svg>""".stripMargin
    parseSVG(svgText).ref

  test("addEdgeHitAreas covers the edge label with a rect resolving to the edge"):
    val svg = fixture()
    MermaidBackend.addEdgeHitAreas(svg)

    val rect = svg.querySelector(s"rect.${SelectableElement.edgeLabelHitClass}")
    assert(rect != null, "the label must gain a hit rect")
    assertEquals(rect.getAttribute("data-edge-id"), "L_a_b_0")
    assertEquals(rect.getAttribute("width"), "20")
    assertEquals(rect.getAttribute("height"), "10")
    val labelGroup: dom.Node = svg.querySelector("g.label[data-id]")
    assert(
      rect.parentNode eq labelGroup,
      "the rect must live inside g.label so it inherits the label's transform"
    )
    // Paint (and hit-test) order: the rect must come after the foreignObject
    assertEquals(rect.previousElementSibling.tagName.toLowerCase, "foreignobject")

  test("the label rect and the halo clone resolve to the SAME ArrowId as the path"):
    val svg = fixture()
    MermaidBackend.addEdgeHitAreas(svg)

    val path = svg.querySelector("path.flowchart-link:not(.edge-hit-area)")
    val halo = svg.querySelector("path.flowchart-link.edge-hit-area")
    val rect = svg.querySelector(s"rect.${SelectableElement.edgeLabelHitClass}")

    val fromPath = MermaidSelectionStrategy.extractArrowId(path)
    assertEquals(MermaidSelectionStrategy.extractArrowId(halo), fromPath)
    assertEquals(MermaidSelectionStrategy.extractArrowId(rect), fromPath)
    assertEquals(fromPath, ArrowId("a->b/1"))

  test("the label rect is an edge for hit-testing but not a canonical element"):
    val svg = fixture()
    MermaidBackend.addEdgeHitAreas(svg)

    val rect = svg.querySelector(s"rect.${SelectableElement.edgeLabelHitClass}")
    assert(MermaidSelectionStrategy.isEdge(rect), "clicks on the rect must resolve as edge clicks")
    assert(rect.matches(MermaidSelectionStrategy.edgeSelector), "closest(edgeSelector) must match the rect")

    // findAll must expose exactly ONE edge element (the rendered path) — helpers with
    // duplicate ids would otherwise win headOption lookups and break geometry consumers.
    val edges = SelectableElement.findAll(svg, MermaidSelectionStrategy).filter(_.isInstanceOf[org.jpablo.graphexplorer.viewer.components.selection.EdgeElement])
    assertEquals(edges.size, 1)

  test("an unlabelled edge (zero-width foreignObject) gains no label rect"):
    val svg = fixture()
    svg.querySelector("foreignObject").setAttribute("width", "0")
    MermaidBackend.addEdgeHitAreas(svg)
    assertEquals(svg.querySelector(s"rect.${SelectableElement.edgeLabelHitClass}"), null)
