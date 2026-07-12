package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.graphviz.model.AttrResolver
import org.jpablo.graphexplorer.graphviz.layout.{Coord, NodeSize, Rank, RankDir, XCoord}

/** `rankdir = LR` (02) — **fully byte-exact end-to-end** (dot_json/json0/svg).
  *
  * gv lays out canonically (TB) with `gv_nodesize(flip)` (swapped w/h —
  * `NodeSize.layoutSize`, byte-identical for TB so 01/04/05/06/07 are
  * untouched), then rotates the drawing once: `map_point` (`DrawTransform`)
  * = `ccwrotate(rankdir·90°) − Offset`, wired through Output.json0/dotJson +
  * Svg. The pieces that closed 02: the faithful NetworkSimplex (canonical
  * x-solve incl. the `go` edge-label vnode) + `map_point` transform + the
  * `vee`/crow arrowhead length (`arrow_length_crow`) + `limitBoxes`
  * (routespl.c) narrowing the channel to the spline corridor so
  * `recover_slack` gives the label vnode its exact `lw` (which clamps the long
  * `start→end` edge). Every node, bb, lp, spline, arrow and label matches gv.
  */
class RankDirSpec extends FunSuite:

  private def g(name: String) =
    AttrResolver.resolve(DotParser.parse(OracleHarness.corpusSource(name)).toOption.get)

  // ── §7 blocker (1) RESOLVED & locked: gv_nodesize(n, flip) ───────────────
  test("layoutSize: TB == nodeSize (byte-identical contract)"):
    List("01-minimal", "06-undirected", "07-cross", "04-ports-compass").foreach { n =>
      val gr = g(n)
      assert(!Rank.flip(gr), s"$n must be TB")
      gr.nodes.foreach { nd =>
        assertEquals(NodeSize.layoutSize(nd, gr), NodeSize.nodeSize(nd, gr),
          s"$n ${nd.id}: layoutSize must equal nodeSize when not flipped")
      }
    }

  test("layoutSize: LR swaps w/h (gv_nodesize flip); nodeSize stays true"):
    val gr = g("02-attrs")
    assert(Rank.flip(gr) && Rank.rankdir(gr) == RankDir.LR, "02 is rankdir=LR")
    gr.nodes.foreach { nd =>
      for t <- NodeSize.nodeSize(nd, gr); l <- NodeSize.layoutSize(nd, gr) do
        assertEquals(l.width, t.height, s"02 ${nd.id} layout w = true h")
        assertEquals(l.height, t.width, s"02 ${nd.id} layout h = true w")
    }

  // golden 02 final node positions (dot, points).
  private val golden02 = Map(
    "start"  -> (27.0, 52.0),
    "middle" -> (131.44, 52.0),
    "end"    -> (222.88, 18.0)
  )

  /** Canonical (layoutSize) coords + the verified LR `map_point`. */
  private def finalLR(name: String): Map[String, (Double, Double)] =
    val gr    = g(name)
    val xs    = XCoord.xCoords(gr)
    val (_, yOf) = Coord.rankY(gr)
    val ranks = Rank.assign(gr)
    var minX = Double.MaxValue; var maxY = -Double.MaxValue
    gr.nodes.foreach { nd =>
      for xPt <- xs.get(nd.id); sz <- NodeSize.layoutSize(nd, gr) do
        val x  = xPt.value
        val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
        val y  = yOf(ranks(nd.id)).value
        minX = math.min(minX, x - hw); maxY = math.max(maxY, y + hh)
    }
    gr.nodes.iterator.flatMap { nd =>
      xs.get(nd.id).map(xPt => nd.id -> ((maxY - yOf(ranks(nd.id)).value, xPt.value - minX)))
    }.toMap

  // STRICT GATE (promoted 2026-07-11): with the faithful NetworkSimplex +
  // decompose node order + make_edge_pairs order + initial-rank seeding, 02's
  // canonical x-solve matches gv byte-for-byte, so BOTH axes land byte-exact.
  test("02 LR: node positions byte-exact (rank + order axis)"):
    val f = finalLR("02-attrs")
    // compare at gv's output precision (2dp, as `plain`/`json0` emit) — the raw
    // canonical solve matches gv exactly; only the display rounding matters.
    def r2(d: Double) = f"$d%.2f"
    golden02.foreach { case (id, (gx, gy)) =>
      val (ox, oy) = f(id)
      assertEquals(r2(ox), r2(gx), s"02 $id rank-axis (final X)")
      assertEquals(r2(oy), r2(gy), s"02 $id order-axis (final Y)")
    }

  // FULL json0 byte-exact (2026-07-11): the map_point transform (DrawTransform)
  // is wired into Output.json0; the `vee` (crow) arrowhead length trims the
  // splines correctly; and `limitBoxes` (routespl.c) narrows the channel boxes
  // to the spline corridor so recover_slack gives the label vnode its exact
  // `lw` — which clamps the long `start→end` edge. Every node, bb, lp and edge
  // spline now matches the golden byte-for-byte.
  test("02 LR: json0 byte-exact (nodes + bb + lp + all splines)"):
    assertEquals(org.jpablo.graphexplorer.graphviz.output.Output.json0(g("02-attrs")),
                 OracleHarness.golden("02-attrs", "json0"))

  // Full svg: the transform is threaded through every svg coordinate
  // (node centres, spline points, arrow, edge-label text) and the `vee` head
  // renders as gv's 8-point crow polygon. rounded/filled boxes, gray/dashed
  // edges were already modelled — so 02's whole svg is byte-exact.
  test("02 LR: svg byte-exact (rotated layout + vee arrowheads)"):
    assertEquals(org.jpablo.graphexplorer.graphviz.output.Svg.svg(g("02-attrs")),
                 OracleHarness.golden("02-attrs", "svg"))

  test("02 LR: dot_json byte-exact"):
    assertEquals(org.jpablo.graphexplorer.graphviz.output.Output.dotJson(g("02-attrs")),
                 OracleHarness.golden("02-attrs", "dot_json"))

end RankDirSpec
