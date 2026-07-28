package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.{NodeSize, Polygon}

/** `poly_inside` clips edge splines to the OUTLINE ring, and that ring sits
  * `penwidth/2` outside the drawn polygon — poly_init, shapes.c:2344:
  *
  * {{{
  * // add an outline at half the penwidth outside the outermost periphery
  * Q.x += cosx * penwidth / 2 / GAP;
  * Q.y += sinx * penwidth / 2 / GAP;
  * }}}
  *
  * with `penwidth = late_double(n, N_penwidth, DEFAULT_NODEPENWIDTH,
  * MIN_NODEPENWIDTH)` — the ATTRIBUTE, not `style=bold`/`setlinewidth(N)`,
  * which `gvrender_set_style` applies at render time and which never reach the
  * shape geometry.
  *
  * We hardcoded 1.0 here, which is invisible on a default-penwidth corpus and
  * wrong on any other: 192's `penwidth=2.35` Mdiamond pulled EIGHTEEN splines
  * ~2pt short, in both directions (its own out-edges' start points and its
  * in-edge's arrow tip), because the clip stopped at the drawn diamond instead
  * of the outline.
  */
class PenwidthOutlineSpec extends FunSuite:

  private def polyOf(corpus: String, node: String): Polygon.Poly =
    val g = OracleHarness.corpusGraph(corpus)
    val n = g.nodes.find(_.id == node).getOrElse(fail(s"$node not in $corpus"))
    NodeSize.polygon(n, g).getOrElse(fail(s"$node is not a convex builtin polygon"))

  test("the outline sits penwidth/2 out, not 1/2 — the drawn ring is unchanged"):
    // 192's start_worker_if_fits: Mdiamond, penwidth=2.35, 2.3603x0.5in.
    val p = polyOf("192-rank-gap-callgraph", "start_worker_if_fits")
    val drawnX = p.rings.last.map(_._1).max
    val drawnY = p.rings.last.map(_._2).max
    assertEqualsDouble(drawnX, 84.97, 0.01, "drawn half-width (json0 width 2.3603in)")
    assertEqualsDouble(drawnY, 18.0, 0.01, "drawn half-height (0.5in)")
    // Offsetting each side of a diamond outward by d moves the vertices by
    // d·hypot(a,b)/b and d·hypot(a,b)/a — 5.67pt and 1.20pt here, NOT 2.41/0.51
    // as a hardcoded penwidth of 1 would give.
    val d = 2.35 / 2.0
    val l = math.hypot(drawnX, drawnY)
    assertEqualsDouble(p.outline.map(_._1).max, drawnX + d * l / drawnY, 0.01)
    assertEqualsDouble(p.outline.map(_._2).max, drawnY + d * l / drawnX, 0.01)

  test("the default is still exactly 1.0"):
    val desc = Polygon.descOf("diamond").getOrElse(fail("diamond"))
    val p    = Polygon.init(50, 20, 50, 20, true, false, desc) // penwidth defaulted
    val ax   = p.rings.last.map(_._1).max
    val ay   = p.rings.last.map(_._2).max
    val l    = math.hypot(ax, ay)
    assertEqualsDouble(p.outline.map(_._1).max, ax + 0.5 * l / ay, 0.01)
    assertEqualsDouble(p.outline.map(_._2).max, ay + 0.5 * l / ax, 0.01)

  test("late_double semantics: absent, empty, negative and garbage all mean 1.0"):
    import org.jpablo.graphexplorer.graphviz.model.Attrs
    def pw(v: String*) = Polygon.attrPenwidth(Attrs(v.grouped(2).map(p => p(0) -> p(1)).toMap))
    assertEqualsDouble(pw(), 1.0, 0.0, "no attr")
    assertEqualsDouble(pw("penwidth", ""), 1.0, 0.0, "empty string")
    assertEqualsDouble(pw("penwidth", "-3"), 1.0, 0.0, "below MIN_NODEPENWIDTH")
    assertEqualsDouble(pw("penwidth", "wide"), 0.0, 0.0, "atof of garbage is 0, and 0 >= the floor")
    assertEqualsDouble(pw("penwidth", "2.35"), 2.35, 0.0)

  test("penwidth=0 drops the outline ring entirely"):
    // `if (peripheries >= 1 && penwidth > 0) ++outp;` — with no extra ring,
    // poly_inside falls back to the outermost DRAWN periphery.
    val d = Polygon.descOf("diamond").getOrElse(fail("diamond"))
    val zero = Polygon.init(50, 20, 50, 20, true, false, d, penwidth = 0.0)
    assertEquals(zero.outline, zero.rings.last, "outline == the drawn ring")
    val one = Polygon.init(50, 20, 50, 20, true, false, d, penwidth = 1.0)
    assertNotEquals(one.outline, one.rings.last)
