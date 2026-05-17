package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.NetworkSimplex
import org.jpablo.graphexplorer.graphviz.layout.NetworkSimplex.NSEdge

/** Unit tests for the network-simplex kernel — specifically the ranking-slack
  * cases the corpus cannot exercise (every corpus graph has a unique optimal
  * compact ranking, so end-to-end the 48 oracle tests already prove the
  * solver on real input; these probe the pivot/feasibility/optimality path).
  */
class NetworkSimplexSpec extends FunSuite:

  private def e(t: String, h: String, ml: Int = 1, w: Int = 1) = NSEdge(t, h, ml, w)

  private def cost(es: Seq[NSEdge], r: Map[String, Int]): Int =
    es.map(x => x.weight * (r(x.head) - r(x.tail))).sum

  private def feasible(es: Seq[NSEdge], r: Map[String, Int]): Boolean =
    es.forall(x => r(x.head) - r(x.tail) >= x.minlen)

  test("chain a→b→c: 0,1,2"):
    val r = NetworkSimplex.solve(Seq("a", "b", "c"), Seq(e("a", "b"), e("b", "c")))
    assertEquals(r, Map("a" -> 0, "b" -> 1, "c" -> 2))

  test("diamond: a0 b1 c1 d2"):
    val es = Seq(e("a", "b"), e("a", "c"), e("b", "d"), e("c", "d"))
    val r  = NetworkSimplex.solve(Seq("a", "b", "c", "d"), es)
    assertEquals(r, Map("a" -> 0, "b" -> 1, "c" -> 1, "d" -> 2))
    assert(feasible(es, r))

  test("long edge with slack: a→b, b→c, a→c ⇒ a0 b1 c2 (a→c slack=1)"):
    val es = Seq(e("a", "b"), e("b", "c"), e("a", "c"))
    val r  = NetworkSimplex.solve(Seq("a", "b", "c"), es)
    assertEquals(r, Map("a" -> 0, "b" -> 1, "c" -> 2))
    assert(feasible(es, r))

  test("non-unique optimum: minlen 1/3/1 ⇒ cost minimal (6), c=3, b feasible"):
    val es = Seq(e("a", "b", 1), e("a", "c", 3), e("b", "c", 1))
    val r  = NetworkSimplex.solve(Seq("a", "b", "c"), es)
    assertEquals(r("a"), 0)
    assertEquals(r("c"), 3)
    assert(r("b") >= 1 && r("b") <= 2, s"b=${r("b")}")
    assert(feasible(es, r))
    assertEquals(cost(es, r), 6) // any feasible b gives 6 — must reach the optimum

  test("minlen respected (a→b minlen 2)"):
    val r = NetworkSimplex.solve(Seq("a", "b"), Seq(e("a", "b", 2)))
    assertEquals(r("b") - r("a"), 2)

  test("weight pulls the heavier edge tight"):
    // a→m (w1), m→z (w1), a→z (w1, minlen 1). a0; z = max(m+1, a+1); m in [1, z-1].
    // total = (m) + (z-m) + (z) ; with z minimal = 2 (m=1): cost = 1+1+2 = 4.
    val es = Seq(e("a", "m"), e("m", "z"), e("a", "z"))
    val r  = NetworkSimplex.solve(Seq("a", "m", "z"), es)
    assert(feasible(es, r))
    assertEquals(r("a"), 0)
    assertEquals(r("z"), 2)
    assertEquals(cost(es, r), 4)

  test("disconnected components both ranked, normalised"):
    val es = Seq(e("a", "b"), e("x", "y"))
    val r  = NetworkSimplex.solve(Seq("a", "b", "x", "y"), es)
    assert(feasible(es, r))
    assertEquals(r.values.min, 0)

end NetworkSimplexSpec
