package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Convex builtin polygon shapes (poly_init vertex generation + sizing).
  * Each probe is a single node with no edges, isolating the polygon geometry
  * from edge clipping. node size (dot_json bb) and the `<polygon>` vertices
  * are asserted byte-exact vs the gv 13.0.1 oracle. Coverage spans the axes:
  *   - diamond (orientation 45), triangle (odd sides, non-integer verts)
  *   - hexagon (6 sides), house (distortion −0.64), parallelogram (skew 0.6)
  */
class ShapeSpec extends FunSuite:
  private def g(n: String) = OracleHarness.corpusGraph(n)

  private val shapes = List(
    "16-diamond", "17-triangle", "18-hexagon", "19-house", "20-parallelogram",
    // orientation-180 (inv*), positive distortion (trapezium), plain n-gons
    "21-invtriangle", "22-trapezium", "23-pentagon", "24-octagon", "25-invhouse"
  )

  shapes.foreach { name =>
    test(s"$name: dot_json byte-exact (bb from polygon size)"):
      assertEquals(Output.dotJson(g(name)), OracleHarness.golden(name, "dot_json"))
    test(s"$name: svg byte-exact (<polygon> vertices)"):
      assertEquals(Svg.svg(g(name)), OracleHarness.golden(name, "svg"))
  }

  // Edge INTO a polygon node: the spline clips to the polygon outline
  // (poly_inside same_side test), not the ellipse/box fallback.
  test("26-diamondedge: svg byte-exact (spline clips to diamond outline)"):
    assertEquals(Svg.svg(g("26-diamondedge")), OracleHarness.golden("26-diamondedge", "svg"))

  // Angled edges into mixed polygons (triangle → hexagon / diamond branch):
  // the same_side clip must resolve the crossing on a slanted outline edge.
  test("27-polymix: svg byte-exact (angled clips, mixed shapes)"):
    assertEquals(Svg.svg(g("27-polymix")), OracleHarness.golden("27-polymix", "svg"))
  test("27-polymix: dot_json byte-exact"):
    assertEquals(Output.dotJson(g("27-polymix")), OracleHarness.golden("27-polymix", "dot_json"))

  // peripheries=2 (doublecircle): two concentric ellipses, size grown by 2*GAP.
  test("28-doublecircle: svg byte-exact (two concentric rings)"):
    assertEquals(Svg.svg(g("28-doublecircle")), OracleHarness.golden("28-doublecircle", "svg"))
  test("28-doublecircle: dot_json byte-exact (bb grown by peripheries)"):
    assertEquals(Output.dotJson(g("28-doublecircle")), OracleHarness.golden("28-doublecircle", "dot_json"))
