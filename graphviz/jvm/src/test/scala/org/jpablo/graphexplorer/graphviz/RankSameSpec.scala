package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.{Rank, Coord, XCoord}
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** `rank=same` layout (11-ranksame probe): collapse_rankset — union-merge the
  * set members so the NS rank solve pins them to one rank. The probe forces a
  * real change: `a` (naturally rank 1 via top→a) is pulled to rank 2 to join
  * `b`. Additive: `rankConstraintLeader` is identity without rank constraints.
  */
class RankSameSpec extends FunSuite:
  private def g(n: String) = OracleHarness.corpusGraph(n)

  test("11: rank=same collapses a,b onto the bottom rank"):
    val r = Rank.assign(g("11-ranksame"))
    assertEquals(r("top"), 0); assertEquals(r("mid"), 1)
    assertEquals(r("a"), r("b")); assertEquals(r("a"), 2)

  test("11: node positions byte-exact vs the plain golden"):
    val gr = g("11-ranksame"); val ranks = Rank.assign(gr)
    val (_, yOf) = Coord.rankY(gr); val xs = XCoord.xCoords(gr)
    val golden = Map("top"->(58.0,162.0),"a"->(27.0,18.0),"mid"->(90.0,90.0),"b"->(99.0,18.0))
    gr.nodes.foreach { n =>
      val (gx, gy) = golden(n.id)
      assertEquals(xs(n.id).value, gx, s"${n.id} x"); assertEquals(yOf(ranks(n.id)).value, gy, s"${n.id} y")
    }

  test("11: json0 byte-exact"):
    assertEquals(Output.json0(g("11-ranksame")), OracleHarness.golden("11-ranksame", "json0"))

  test("11: svg byte-exact"):
    assertEquals(Svg.svg(g("11-ranksame")), OracleHarness.golden("11-ranksame", "svg"))

  test("11: dot_json byte-exact (rank-only subgraph omits label — write_attrs)"):
    assertEquals(Output.dotJson(g("11-ranksame")), OracleHarness.golden("11-ranksame", "dot_json"))
