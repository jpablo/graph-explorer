package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.units.Length.Pt

/** The root graph bounding box — gv computes it during LAYOUT
  * (`dot_compute_bb` + per-spline `update_bb_bz` growth + the graph-label
  * reservation) and the emitters only read it; this object is that single
  * home for the port (it lived in `Output` until the finalBBox transcription
  * needed it from [[DrawTransform]]).
  *
  *  - [[bbox]] — the CANONICAL-frame box (TB layout coords), all growth
  *    included. Byte-exact against the corpus through both writers.
  *  - [[finalBBox]] — `translate_bb` (postproc.c): the canonical box's
  *    corners mapped through `map_point` into the final (rotated) frame.
  *    gv does NOT re-derive a final-frame box from node extents — it
  *    transforms the canonical one, so spline overhang / self-edge space /
  *    label pad arrive in the final frame for free.
  */
object GraphBB:

  private[graphviz] val SelfEdgeSize = 18.0 // const.h SELF_EDGE_SIZE

  // ── update_bb_bz (emit.c): grow a bb by a spline's TIGHT bezier bbox ───────
  // dot grows GD_bb per installed spline (the graph box is the node/cluster/
  // label union with each edge's adaptively-subdivided curve extent), so an
  // edge that escapes the node span lifts the drawing. Regular-edge splines
  // stay inside their rank span ⇒ a no-op for them; a non-adjacent flat edge
  // arches above its rank, and THIS is what raises the graph height. The naive
  // control-hull bbox would overshoot (control points sit above the curve), so
  // gv subdivides until each segment is within `HW`=2pt of its chord, then
  // expands by the now-near-flat control points — recovering the true peak.
  private val HW2 = 4.0 // (HW = 2pt)²
  private def ptToLine2(a: Spline.XY, b: Spline.XY, p: Spline.XY): Double =
    val dx = b.x - a.x; val dy = b.y - a.y
    var a2 = (p.y - a.y) * dx - (p.x - a.x) * dy
    a2 *= a2
    if a2 < 1e-6 then 0.0 else a2 / (dx * dx + dy * dy)
  private def flatEnough(cp: Array[Spline.XY]): Boolean =
    ptToLine2(cp(0), cp(3), cp(1)) < HW2 && ptToLine2(cp(0), cp(3), cp(2)) < HW2
  /** de Casteljau split at t = 0.5 (`Bezier`, utils.c) → (left, right) quads. */
  private def bezierSplit(cp: Array[Spline.XY]): (Array[Spline.XY], Array[Spline.XY]) =
    def mid(a: Spline.XY, b: Spline.XY) = Spline.XY((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
    val m01 = mid(cp(0), cp(1)); val m12 = mid(cp(1), cp(2)); val m23 = mid(cp(2), cp(3))
    val mA = mid(m01, m12); val mB = mid(m12, m23); val c = mid(mA, mB)
    (Array(cp(0), m01, mA, c), Array(c, mB, m23, cp(3)))
  /** `bb` = mutable `[minX, minY, maxX, maxY]`, grown by one bezier segment. */
  private def updateBBbz(bb: Array[Double], cp: Array[Spline.XY]): Unit =
    val outside = cp.exists(p => p.x > bb(2) || p.x < bb(0) || p.y > bb(3) || p.y < bb(1))
    if outside then
      if flatEnough(cp) then
        cp.foreach { p =>
          if p.x > bb(2) then bb(2) = p.x else if p.x < bb(0) then bb(0) = p.x
          if p.y > bb(3) then bb(3) = p.y else if p.y < bb(1) then bb(1) = p.y
        }
      else
        val (l, r) = bezierSplit(cp)
        updateBBbz(bb, l); updateBBbz(bb, r)
  /** Grow `[minX,minY,maxX,maxY]` by every routed edge spline (update_bb_bz on
    * each 4-point segment). Splines are in layout coords, matching gv's grow
    * during routing (before the rankdir transform). */
  private def growBySplines(g: RGraph, minX: Double, minY: Double, maxX: Double, maxY: Double)
      : (Double, Double, Double, Double) =
    val spls = Spline.splinesEx(g)
    if spls.isEmpty then (minX, minY, maxX, maxY)
    else
      val bb = Array(minX, minY, maxX, maxY)
      spls.valuesIterator.foreach { es =>
        val pts = es.pts
        var i = 0
        while i + 3 < pts.length do
          updateBBbz(bb, Array(pts(i), pts(i + 1), pts(i + 2), pts(i + 3)))
          i += 3
      }
      (bb(0), bb(1), bb(2), bb(3))

  private val bboxMemo = GraphMemo[(Pt, Pt, Pt, Pt)]()
  /** Canonical-frame graph bbox (see class doc). */
  private[graphviz] def bbox(g: RGraph): (Pt, Pt, Pt, Pt) = bboxMemo(g)(bboxImpl(g))
  private def bboxImpl(g: RGraph): (Pt, Pt, Pt, Pt) =
    val (_, yOf) = Coord.rankY(g)
    val ranks    = Rank.assign(g)
    var minX = Double.MaxValue; var maxX = Double.MinValue
    var minY = Double.MaxValue; var maxY = Double.MinValue
    val xs = XCoord.xCoords(g)
    // selfRightSpace: self-edges reserve SELF_EDGE_SIZE (+ label width, port
    // sides permitting) on the right — dot_compute_bb reads the ND_rw that
    // make_LR_constraints inflated. One O(E) map, shared formula with XCoord.
    val selfLoops: Map[String, Double] =
      g.edges.filter(e => e.tail == e.head).groupBy(_.tail).view
        .mapValues(_.map(Coord.selfRightSpace(_, g)).sum).toMap
    g.nodes.foreach { n =>
      // layoutSize, NOT nodeSize: dot_compute_bb reads ND_lw/ND_rw/GD_ht1 —
      // the CANONICAL-frame (swapped for LR/RL) extents. Identical for
      // TB/BT; using the true size put a flipped ellipse ±(w−h)/2 off on
      // both axes (86-rankdir-rl was ±9 out until this).
      for xPt <- xs.get(n.id); sz <- NodeSize.layoutSize(n, g) do
        val x  = xPt.value
        val hw = sz.halfWidthPt.value; val hh = sz.halfHeightPt.value
        val selfW = selfLoops.getOrElse(n.id, 0.0)
        val y  = yOf(ranks(n.id)).value
        minX = math.min(minX, x - hw); maxX = math.max(maxX, x + hw + selfW)
        minY = math.min(minY, y - hh); maxY = math.max(maxY, y + hh)
    }
    // Edge-label vnodes are FAST nodes in dot_compute_bb (GD_nlist walk):
    // ND_lw = GD_nodesep, ND_rw = label width, ND_ht = label height
    // (class2.c:29) — a wide edge label can be the graph's x-extreme
    // (169's a→d label). lp = vnodeX + width/2 recovers the vnode coord.
    locally {
      val lps = Spline.labelPositions(g)
      if lps.nonEmpty then
        val ns = Coord.nodeSepPt(g)
        val realEdges = g.edges.filter(e => e.tail != e.head)
        lps.foreach { (idx, p) =>
          // only INTER-RANK labels ride a class2 label vnode; a flat-edge
          // label (92) is placed inside the rank band, no fast node.
          realEdges.lift(idx).filter(e => ranks.get(e.tail) != ranks.get(e.head)).foreach { e =>
            val (w0, h0) = Coord.edgeLabelDim(e, g)
            val (wl, hl) = if Rank.flip(g) then (h0, w0) else (w0, h0)
            val vx = p.x - wl / 2.0
            minX = math.min(minX, vx - ns); maxX = math.max(maxX, vx + wl)
            minY = math.min(minY, p.y - hl / 2.0); maxY = math.max(maxY, p.y + hl / 2.0)
          }
        }
    }
    // Clusters (dot_compute_bb root): the bb also spans every top-level
    // cluster box + CL_OFFSET margin in x; in y the root's cluster-inflated
    // GD_ht1/GD_ht2 set the bottom/top (label bands included).
    val cls = Cluster.clusters(g)
    if cls.nonEmpty then
      val yi   = Coord.yInfo(g)
      val cbbs = Cluster.bbs(g)
      Cluster.childrenOf(g, -1).foreach { i =>
        minX = math.min(minX, cbbs(i).llx - 8.0); maxX = math.max(maxX, cbbs(i).urx + 8.0)
      }
      if ranks.nonEmpty then
        val maxR = ranks.values.max; val minR = ranks.values.min
        minY = math.min(minY, yOf(maxR).value - yi.rootHt1)
        maxY = math.max(maxY, yOf(minR).value + yi.rootHt2)
    // Grow by each edge spline's tight curve extent (dot's per-spline
    // update_bb_bz). A no-op for node-contained regular edges; a non-adjacent
    // flat-edge arch rises above its rank and lifts the graph height here.
    val (gx0, gy0, gx1, gy1) = growBySplines(g, minX, minY, maxX, maxY)
    minX = gx0; minY = gy0; maxX = gx1; maxY = gy1
    // addXLabels (gv_postprocess): placed external labels grow the canonical
    // bb (updateBB/addLabelBB per label) BEFORE the root-label pad below.
    locally {
      val xl = XLabels.place(g)
      (xl.nodes.valuesIterator ++ xl.edges.valuesIterator).foreach { p =>
        val (w, h) = if Rank.flip(g) then (p.h, p.w) else (p.w, p.h)
        minX = math.min(minX, p.cx - w / 2.0); maxX = math.max(maxX, p.cx + w / 2.0)
        minY = math.min(minY, p.cy - h / 2.0); maxY = math.max(maxY, p.cy + h / 2.0)
      }
    }
    // Root graph label — gv_postprocess (postproc.c:617) grows the CANONICAL
    // bb: for a flipped graph (LR/RL) the label height lands on the canonical
    // X axis (the final y after rotation); TB/BT put it on Y with inverted
    // top/bottom for BT. If the label is WIDER than the drawing, the bb is
    // widened symmetrically on the label's text axis. (Coord already shifted
    // the nodes for a TB bottom label ⇒ this reclaims that space.)
    val padY = Coord.graphLabelPad(g) // dimen.y + YPAD (2*GAP)
    if padY > 0 then
      val lblW = Coord.graphLabelPaddedWidth(g) // dimen.x + XPAD (4*GAP)
      val top  = Coord.graphLabelTop(g)
      if Rank.flip(g) then
        if top then maxX += padY else minX -= padY
        if lblW > maxY - minY then
          val diff = (lblW - (maxY - minY)) / 2.0
          minY -= diff; maxY += diff
      else
        val tb = Rank.rankdir(g) == RankDir.TB
        if top then { if tb then maxY += padY else minY -= padY }
        else        { if tb then minY -= padY else maxY += padY }
        if lblW > maxX - minX then
          val diff = (lblW - (maxX - minX)) / 2.0
          minX -= diff; maxX += diff
    // Snap sub-epsilon FP noise to the nearest integer. gv's node coordinates
    // come out as clean values; a polygon size derived through sqrt/trig
    // (poly_init) carries ~1e-13 noise, which is harmless EXCEPT when it
    // straddles the integer boundary that dot_json floor/ceils or the svg
    // canvas ceils — there it becomes a full ±1pt error (e.g. house maxY
    // 36.0000001 → ceil 37). Genuine fractionals (triangle 49.6) stay put.
    def snap(v: Double): Double =
      val r = math.rint(v)
      if math.abs(v - r) < 1e-6 then r else v
    // Empty drawing (zero nodes/clusters/splines — e.g. `digraph {}` or the
    // viewer's defaults-only serialization): nothing touched the extreme
    // seeds, and MinValue−MaxValue overflows to −Infinity downstream (a
    // NumberFormatException in the BigDecimal formatters). gv emits
    // bb="0 0 0 0" here (translate_drawing of an empty drawing).
    if minX > maxX || minY > maxY then (Pt(0.0), Pt(0.0), Pt(0.0), Pt(0.0))
    else (Pt(snap(minX)), Pt(snap(minY)), Pt(snap(maxX)), Pt(snap(maxY)))

  /** `translate_bb` (postproc.c:124): the FINAL-frame bbox = the canonical
    * box's corners through `map_point` — for LR/BT the (LL.x, UR.y) /
    * (UR.x, LL.y) corner pair maps to the new LL/UR (the rotation turns
    * them into the extremes); for RL the LL/UR corners map directly. The
    * result's LL is (0,0) by construction (the Offset lands the drawing at
    * the origin). TB never calls this (identity path). */
  private[graphviz] def finalBBox(g: RGraph): (Double, Double, Double, Double) =
    val (llx, lly, urx, ury) = bbox(g)
    val tf = DrawTransform.of(g)
    val (ll, ur) = Rank.rankdir(g) match
      case RankDir.LR | RankDir.BT => (tf(llx.value, ury.value), tf(urx.value, lly.value))
      case _                       => (tf(llx.value, lly.value), tf(urx.value, ury.value))
    (ll._1, ll._2, ur._1, ur._2)

end GraphBB
