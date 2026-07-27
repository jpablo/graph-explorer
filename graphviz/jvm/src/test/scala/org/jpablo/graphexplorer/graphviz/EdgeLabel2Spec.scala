package org.jpablo.graphexplorer.graphviz
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.output.{Output, Svg}

/** Edge labels increment 2 — the asymmetric label vnode (class2 label_vnode:
  * lw=nodesep, rw=labelWidth) so a label reserves order-axis space and pushes
  * its rank neighbour. 15-elbranch (a->b[WIDE]; a->c).
  *
  * Now fully byte-exact (2026-07-11): ported `maximal_bbox`'s label-vnode clamp
  * ("leave room for our own label" — right bound `x+10` then `-= rw`) and
  * `recover_slack`/`resize_vn` (dotsplines.c), which snap the label vnode to the
  * RIGHT edge of the box its edge threads, so a→b bows around the wide label
  * (2 cubics) AND a→c's box starts past the label. lp = routed vnode x +
  * labelWidth/2 (place_vnlabel), read off the byte-exact routed spline. */
class EdgeLabel2Spec extends FunSuite:
  private def g(n: String) = OracleHarness.corpusGraph(n)
  private def nodePos(dj: String): Map[String, String] =
    ujson.read(dj)("objects").arr.iterator
      .filter(o => o.obj.contains("pos") && !o.obj.contains("nodes"))
      .map(o => o("name").str -> o("pos").str).toMap
  test("15: node positions byte-exact (label vnode pushes neighbour correctly)"):
    val o = Output.json0(g("15-elbranch"))
    assertEquals(nodePos(o), nodePos(OracleHarness.golden("15-elbranch", "json0")))
  test("15: dot_json byte-exact (structure/attrs)"):
    assertEquals(Output.dotJson(g("15-elbranch")), OracleHarness.golden("15-elbranch", "dot_json"))
  test("15: json0 byte-exact (edge lp + spline pos around the label)"):
    assertEquals(Output.json0(g("15-elbranch")), OracleHarness.golden("15-elbranch", "json0"))
  test("15: svg byte-exact (label <text> + splines)"):
    assertEquals(Svg.svg(g("15-elbranch")), OracleHarness.golden("15-elbranch", "svg"))

  // ── flat-edge labels: the NON-ADJACENT case (flat.c flat_node) ──────────
  //
  // A labelled FLAT (same-rank) edge is handled two different ways by gv:
  //
  //   adjacent    — nothing between the endpoints: the label's width is folded
  //                 into the pair's separation (`ED_dist`), no new node.
  //   NON-adjacent — something real sits between them: `dot_position` splices a
  //                 virtual node carrying the label into rank `rank(tail) - 1`
  //                 at the slot `flat_limits` picks. That node is a full
  //                 participant in the rank's LR chain.
  //
  // We only had the first. 191 has 9 non-adjacent labelled flat edges, 7 of
  // them on one rank, and the missing slots cost exactly 108pt of width there —
  // which is the whole graph's widest rank, so the drawing came out 108pt short.
  //
  // "Adjacent" is gv's notion, not order-index arithmetic: `checkFlatAdjacent`
  // stops only at a NORMAL node or a LABELLED virtual, so plain chain vnodes
  // between the endpoints are transparent.

  test("191: labelled non-adjacent flat edges take a slot in the rank above"):
    val res = layout.Order.order(g("191-scala-type-graph"))
    val ded = g("191-scala-type-graph").edges.filter(e => e.tail != e.head)
    val placed = res.order.toVector.sortBy(_._1).flatMap { (r, ids) =>
      ids.zipWithIndex.collect { case (layout.LayoutNode.FlatLabel(d), o) =>
        (r, o, s"${ded(d).tail}>${ded(d).head}")
      }
    }
    // rank, slot and identity as gv's own `flat_node` probe reports them
    assertEquals(placed, Vector(
      (3,  8, "ModuleBound>ProgramCompanion"),
      (5,  6, "RuntimeContext>ProgramType"),
      (7, 16, "ProgramRunnerGiven>DspyError"),
      (7, 18, "PredictorState>ProgramPredictorsGiven"),
      (7, 19, "ProgramPredictorsGiven>PredictorView"),
      (7, 20, "ProgramPredictorsGiven>PredictorView"),
      (7, 21, "PredictorState>ParaCategoryGiven"),
      (7, 23, "ParaCategoryGiven>PredictorState"),
      (7, 27, "SignatureIO>SignatureLayoutType")))

  test("191: the graph bb matches gv exactly (the flat labels' 108pt)"):
    // The end-to-end consequence: 191's widest rank is the one carrying seven
    // flat labels, so the whole drawing's extent depends on them. (191 is still
    // a corpus deferral — some within-rank offsets differ — but the EXTENT is
    // now byte-exact, and this gate is what keeps it that way.)
    val name = "191-scala-type-graph"
    assertEquals(ujson.read(Output.json0(g(name)))("bb").str,
                 ujson.read(OracleHarness.golden(name, "json0"))("bb").str)
