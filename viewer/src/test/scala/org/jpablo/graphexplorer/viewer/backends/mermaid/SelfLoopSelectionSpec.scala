package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.MermaidSelectionStrategy
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.models.ArrowId
import org.scalajs.dom

/** Self-loops (`a --> a`) are the one edge shape Mermaid does NOT render as a
  * single `L_a_b_0` path. It emits THREE sibling paths — `a-cyclic-special-1`,
  * `-mid`, `-2` — whose ids match neither `L_A_B_0` nor the older `L-A-B-0`.
  *
  * Two user-visible bugs came out of that. `extractArrowId` fell through to its
  * `edge-${hashCode}` fallback, producing an id matching no arrow in the model,
  * so Backspace deleted nothing. And because each segment resolved to a
  * DIFFERENT garbage id, selecting the loop marked only the segment actually
  * clicked — the casing covered a third of the loop and the rest stayed grey.
  *
  * Note what the DOM can and cannot say: the id is keyed on the NODE, not the
  * edge, so two `a --> a` edges produce one set of paths and one drawn loop.
  * There is no sequence to recover, hence seq 1.
  */
class SelfLoopSelectionSpec extends FunSuite:

  /** Trimmed Mermaid 11 output for `flowchart TB / a --> a`. */
  private def fixture(): dom.svg.SVG =
    val svgText =
      """<svg xmlns="http://www.w3.org/2000/svg">
        |  <g class="root">
        |    <g class="edgePaths">
        |      <path d="M33,62 C31,70 27,78 24,90" id="a-cyclic-special-1" class="edge-thickness-normal flowchart-link"/>
        |      <path d="M24,112 C24,120 24,130 24,140" id="a-cyclic-special-mid" class="edge-thickness-normal flowchart-link"/>
        |      <path d="M42,162 C48,153 53,145 60,140" id="a-cyclic-special-2" class="edge-thickness-normal flowchart-link"/>
        |    </g>
        |  </g>
        |</svg>""".stripMargin
    parseSVG(svgText).ref

  private def segments(svg: dom.svg.SVG): Seq[dom.Element] =
    Seq("#a-cyclic-special-1", "#a-cyclic-special-mid", "#a-cyclic-special-2").map(svg.querySelector(_))

  test("a self-loop segment resolves to the real arrow, not a hashCode id"):
    val svg = fixture()
    val id  = MermaidSelectionStrategy.extractArrowId(segments(svg).head)
    assertEquals(id, ArrowId("a->a/1"))

  test("every segment of a self-loop resolves to the SAME arrow"):
    // This is what makes the whole loop light up: the casing is applied per
    // selected element, so three ids means one third of a loop.
    val svg = fixture()
    val ids = segments(svg).map(MermaidSelectionStrategy.extractArrowId).distinct
    assertEquals(ids, Seq(ArrowId("a->a/1")))

  test("the node id is taken whole, hyphens and all"):
    // `my-node-cyclic-special-1` must be `my-node`, not `my`. A greedy prefix
    // match is the difference between selecting an edge and inventing one.
    val svgText =
      """<svg xmlns="http://www.w3.org/2000/svg">
        |  <g class="edgePaths">
        |    <path d="M0,0 L1,1" id="my-node-cyclic-special-mid" class="flowchart-link"/>
        |  </g>
        |</svg>""".stripMargin
    val svg = parseSVG(svgText).ref
    val id  = MermaidSelectionStrategy.extractArrowId(svg.querySelector("#my-node-cyclic-special-mid"))
    assertEquals(id, ArrowId("my-node->my-node/1"))

  test("a normal edge is unaffected"):
    val svgText =
      """<svg xmlns="http://www.w3.org/2000/svg">
        |  <g class="edgePaths">
        |    <path d="M0,0 L1,1" id="L_a_b_0" class="flowchart-link"/>
        |  </g>
        |</svg>""".stripMargin
    val svg = parseSVG(svgText).ref
    assertEquals(MermaidSelectionStrategy.extractArrowId(svg.querySelector("#L_a_b_0")), ArrowId("a->b/1"))
