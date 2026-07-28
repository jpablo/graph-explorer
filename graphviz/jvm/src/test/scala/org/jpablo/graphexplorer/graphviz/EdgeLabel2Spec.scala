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
    // rank, slot and identity as gv's own `flat_node` probe reports them. The
    // rank numbers ARE gv's: they used to be ours+2, because a slack node was
    // anchoring scan_and_normalize and pushing every real rank down (see
    // SlackNormalizeSpec) — the slots never moved.
    assertEquals(placed, Vector(
      (1,  8, "ModuleBound>ProgramCompanion"),
      (3,  6, "RuntimeContext>ProgramType"),
      (5, 16, "ProgramRunnerGiven>DspyError"),
      (5, 18, "PredictorState>ProgramPredictorsGiven"),
      (5, 19, "ProgramPredictorsGiven>PredictorView"),
      (5, 20, "ProgramPredictorsGiven>PredictorView"),
      (5, 21, "PredictorState>ParaCategoryGiven"),
      (5, 23, "ParaCategoryGiven>PredictorState"),
      (5, 27, "SignatureIO>SignatureLayoutType")))

  test("191: dot_json is byte-exact — the whole LAYOUT matches gv"):
    // The strongest 191 gate there is: dot_json carries every node's position,
    // every cluster's box, and the graph bb. Reaching it took the flat-label
    // subsystem end to end — the vnodes themselves (flat_node), their
    // make_edge_pairs slack pairs, their keepout_othernodes edge, and finally
    // make_LR_constraints' "constraints from labels of flat edges on previous
    // rank", which is what actually pins the edge's two endpoints around the
    // label. 191's aux graph is now identical to gv's: 648 constraints, same
    // values, same build order, same seed, same solution.
    //
    // json0 and svg still differ: the SPLINES for labelled non-adjacent flat
    // edges are not routed yet (make_flat_edge's label branch). That is a
    // dotsplines gap, not a layout one — every coordinate they route between
    // is now correct.
    assertEquals(Output.dotJson(g("191-scala-type-graph")),
                 OracleHarness.golden("191-scala-type-graph", "dot_json"))

  test("191: the graph bb matches gv exactly (the flat labels' 108pt)"):
    // The end-to-end consequence: 191's widest rank is the one carrying seven
    // flat labels, so the whole drawing's extent depends on them. (191 is still
    // a corpus deferral — some within-rank offsets differ — but the EXTENT is
    // now byte-exact, and this gate is what keeps it that way.)
    val name = "191-scala-type-graph"
    assertEquals(ujson.read(Output.json0(g(name)))("bb").str,
                 ujson.read(OracleHarness.golden(name, "json0"))("bb").str)
