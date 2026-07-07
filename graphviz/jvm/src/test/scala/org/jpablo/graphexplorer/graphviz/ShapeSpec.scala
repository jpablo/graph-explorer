package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Convex builtin polygon shapes (poly_init vertex generation + sizing).
  * Each probe is a single node with no edges, isolating the polygon geometry
  * from edge clipping. node size (dot_json bb) and the `<polygon>` vertices
  * are asserted byte-exact vs the gv 13.0.1 oracle. Coverage spans the axes:
  *   - diamond (orientation 45), triangle (odd sides, non-integer verts)
  *   - hexagon (6 sides), house (distortion −0.64), parallelogram (skew 0.6)
  */
class ShapeSpec extends FunSuite:
  private def g(n: String) = AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(n)).toOption.get)

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
