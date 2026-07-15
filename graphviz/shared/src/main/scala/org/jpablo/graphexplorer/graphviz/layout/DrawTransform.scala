package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph

/** `postproc.c` `map_point` / `translate_drawing`: the final canonical→drawing
  * coordinate transform applied once, after layout, before the writers.
  *
  * gv lays every graph out canonically (TB) — using `gv_nodesize(flip)` so a
  * flipped node occupies swapped w/h in that canonical frame — then rotates the
  * whole drawing: `p = ccwrotate(p, rankdir·90°); p -= Offset`, where `Offset`
  * is the min corner of the rotated node-extent bbox (so the drawing's lower-
  * left lands at the origin). TB is the identity; LR/BT/RL rotate 90/180/270°.
  *
  * Verified byte-exact for LR node positions vs instrumented gv 13.0.1
  * (RankDirSpec). BT/RL follow the same construction (no corpus exercises them).
  */
object DrawTransform:

  /** Whether a non-identity transform applies (any rankdir but TB). The
    * writers must use [[Output.finalBBox]] — the transformed node-extent box —
    * whenever this is true, not only for the flipped (LR/RL) axes: BT rotates
    * the drawing too (vertical flip) even though its rank axis is vertical. */
  def rotated(g: RGraph): Boolean = Rank.rankdir(g) != RankDir.TB

  /** Canonical→final point map for this graph's `rankdir`. Identity for TB
    * (bit-exact — the whole TB corpus must be untouched). Memoized: the
    * rotated-offset scan (layoutSize per node) otherwise re-runs once per
    * writer (dot_json, json0, svg). */
  private val ofMemo = GraphMemo[(Double, Double) => (Double, Double)]()
  def of(g: RGraph): (Double, Double) => (Double, Double) = ofMemo(g)(ofImpl(g))
  private def ofImpl(g: RGraph): (Double, Double) => (Double, Double) =
    val rd = Rank.rankdir(g)
    if rd == RankDir.TB then (x, y) => (x, y)
    else
      // ccwrotatepf(p, rd·90°) — gv's *custom* right-angle map (geom.c), which
      // is NOT the textbook rotation for 180/270: 90→(-y,x), 180→(x,-y) [a
      // vertical flip, not (-x,-y)], 270→(y,x) [a transpose, not (y,-x)].
      // Getting 180/270 wrong mirrors the BT/RL drawing off an axis.
      def ccw(x: Double, y: Double): (Double, Double) = rd match
        case RankDir.LR => (-y, x)
        case RankDir.BT => (x, -y)
        case RankDir.RL => (y, x)
        case RankDir.TB => (x, y)
      // Offset = min corner of the rotated canonical node-extent bbox (NORMAL
      // nodes ± layoutSize half-extents — the same box dot_compute_bb sees).
      val (_, yOf) = Coord.rankY(g)
      val ranks    = Rank.assign(g)
      val xs       = XCoord.xCoords(g)
      var minRx = Double.MaxValue
      var minRy = Double.MaxValue
      g.nodes.foreach { n =>
        for xp <- xs.get(n.id); sz <- NodeSize.layoutSize(n, g) do
          val cxv = xp.value; val cyv = yOf(ranks(n.id)).value
          val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
          List((-hw, -hh), (hw, -hh), (-hw, hh), (hw, hh)).foreach { case (dx, dy) =>
            val (rx, ry) = ccw(cxv + dx, cyv + dy)
            minRx = math.min(minRx, rx); minRy = math.min(minRy, ry)
          }
      }
      (x, y) => { val (rx, ry) = ccw(x, y); (rx - minRx, ry - minRy) }

end DrawTransform
