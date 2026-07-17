package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.html.{HtmlLayout, HtmlParser}
import org.jpablo.graphexplorer.graphviz.model.{REdge, RGraph, RNode}
import scala.collection.mutable

/** External-label (`xlabel`) placement — port of `addXLabels`
  * (lib/common/postproc.c:402) + `placeLabels`/`xladjust`
  * (lib/label/xlabels.c), gv 13.0.1.
  *
  * Runs in the CANONICAL frame after splines (gv_postprocess order: graph
  * label positions → addXLabels → root-label bb pad → translation). Obstacles
  * are every node, every SET label (dot edge labels), and a size-0 anchor
  * point per unset external label (its edge midpoint); each xlabel tries the
  * 9 corner/side positions around its anchor (left/mid/right × top/mid/bottom
  * in xladjust's exact order, first zero-overlap wins) then the two sliding
  * scans, else the least-overlap position (kept when `forcelabels`, default
  * true).
  *
  * gv accelerates the intersection query with a Hilbert-loaded R-tree; the
  * candidate set is `Overlap(labelRect, objplpmks(obj))` — reproduced here
  * with a linear scan over the same expanded rects. Leaf-visit ORDER only
  * matters through `intrsx` bucket replacement on the no-zero-overlap path
  * (all candidates land in bucket 5 while the label is unset); scan order =
  * object order. Head/tail port labels (`headlabel`/`taillabel`) also route
  * through gv's addXLabels — not modelled (no corpus exercise).
  */
object XLabels:

  /** A placed external label: canonical-frame CENTER (`lp->pos` after
    * `centerPt`) + FINAL-frame dimen (unswapped `lp->dimen`). */
  final case class Placed(cx: Double, cy: Double, w: Double, h: Double)
  final case class Result(nodes: Map[String, Placed], edges: Map[Int, Placed]):
    def isEmpty: Boolean = nodes.isEmpty && edges.isEmpty

  private val placeMemo = GraphMemo[Result]()
  def place(g: RGraph): Result = placeMemo(g)(placeImpl(g))

  // xlabels.h: candidate/neighbour grid indices — 6 7 8 / 3 4 5 / 0 1 2,
  // the object of interest at 4; getintrsxi's -1 falls back to bucket 5.
  private val XLPXPY = 0; private val XLCXPY = 1; private val XLNXPY = 2
  private val XLPXCY = 3; private val XLNXCY = 5
  private val XLPXNY = 6; private val XLCXNY = 7; private val XLNXNY = 8
  private val XLXDENOM = 8.0
  private val XLYDENOM = 2.0

  /** xlabel_t: sz is canonical-swapped; pos = box LL corner (mutated during
    * placement). */
  private final class XL(val szx: Double, val szy: Double, val owner: Either[String, Int]):
    var px, py = 0.0
    var set    = false
  /** object_t: pos = box LL corner; size-0 = an edge-label anchor point. */
  private final class Obj(val px: Double, val py: Double, val sx: Double, val sy: Double, val lbl: XL)

  private type Rect = (Double, Double, Double, Double) // llx, lly, urx, ury

  /** C round(): half away from zero (Scala's math.round is half-up). */
  private def cRound(v: Double): Double =
    if v < 0 then -math.round(-v).toDouble else math.round(v).toDouble

  private def objp2rect(o: Obj): Rect =
    (cRound(o.px), cRound(o.py), cRound(o.px + o.sx), cRound(o.py + o.sy))
  private def objplp2rect(o: Obj): Rect =
    val lp = o.lbl
    (cRound(lp.px), cRound(lp.py), cRound(lp.px + lp.szx), cRound(lp.py + lp.szy))
  /** Boundary enclosing all possible label placements (the rect gv loads in
    * the R-tree). */
  private def objplpmks(o: Obj): Rect =
    val (px, py) = if o.lbl != null then (o.lbl.szx, o.lbl.szy) else (0.0, 0.0)
    (math.floor(o.px - px), math.floor(o.py - py),
     math.ceil(o.px + o.sx + px), math.ceil(o.py + o.sy + py))

  private def overlap(r: Rect, s: Rect): Boolean =
    !(r._1 > s._3 || s._1 > r._3 || r._2 > s._4 || s._2 > r._4)

  /** Intersection area of two overlapping rects (aabbaabb). */
  private def aabbaabb(r: Rect, s: Rect): Double =
    if !overlap(r, s) then 0.0
    else
      val iminx = math.max(r._1, s._1); val iminy = math.max(r._2, s._2)
      val imaxx = math.min(r._3, s._3); val imaxy = math.min(r._4, s._4)
      (imaxx - iminx) * (imaxy - iminy)

  /** Direction bucket of cp relative to op (both labels must be set — while
    * placing, `op`'s label is unset, so this returns -1 and everything lands
    * in bucket 5). */
  private def getintrsxi(op: Obj, cp: Obj): Int =
    val lp = op.lbl; val clp = cp.lbl
    if lp == null || clp == null || !lp.set || !clp.set then return -1
    if (op.px == 0.0 && op.py == 0.0) || (cp.px == 0.0 && cp.py == 0.0) then return -1
    if cp.py < op.py then
      if cp.px < op.px then XLPXPY else if cp.px > op.px then XLNXPY else XLCXPY
    else if cp.py > op.py then
      if cp.px < op.px then XLPXNY else if cp.px > op.px then XLNXNY else XLCXNY
    else if cp.px < op.px then XLPXCY
    else if cp.px > op.px then XLNXCY
    else -1

  /** recordointrsx/recordlintrsx (identical bodies in C). */
  private def recordIntrsx(op: Obj, cp: Obj, rp: Rect, a: Double, intrsx: Array[Obj]): Double =
    var i = getintrsxi(op, cp)
    if i < 0 then i = 5
    if intrsx(i) != null then
      var maxa = 0.0
      val sa1 = aabbaabb(rp, objp2rect(intrsx(i)))
      if sa1 > a then maxa = sa1
      if intrsx(i).lbl != null then
        val sa2 = aabbaabb(rp, objplp2rect(intrsx(i)))
        if sa2 > a then maxa = math.max(sa2, maxa)
      if maxa > 0.0 then return maxa
      intrsx(i) = cp
      a
    else
      intrsx(i) = cp
      a

  /** (n, area, posx, posy) — BestPos_t; pos = the candidate under test. */
  private def xlintersections(objs: Array[Obj], op: Obj, intrsx: Array[Obj]): (Int, Double, Double, Double) =
    val lp = op.lbl
    var n = 0; var area = 0.0
    // size-0 objects (label anchor points) strictly enclosed by the candidate
    var i = 0
    while i < objs.length do
      val o = objs(i)
      if (o ne op) && !(o.sx > 0 && o.sy > 0)
        && o.px > lp.px && o.px < lp.px + lp.szx
        && o.py > lp.py && o.py < lp.py + lp.szy
      then n += 1
      i += 1
    val rect = objplp2rect(op)
    i = 0
    while i < objs.length do
      val cp = objs(i)
      if (cp ne op) && overlap(rect, objplpmks(cp)) then // the R-tree candidate query
        val a = aabbaabb(rect, objp2rect(cp)) // label-object intersect
        if a > 0.0 then
          area += recordIntrsx(op, cp, rect, a, intrsx); n += 1
        if cp.lbl != null && cp.lbl.set then  // label-label intersect
          val a2 = aabbaabb(rect, objplp2rect(cp))
          if a2 > 0.0 then
            area += recordIntrsx(op, cp, rect, a2, intrsx); n += 1
      i += 1
    (n, area, lp.px, lp.py)

  /** xladjust: try the 9 fixed positions (exact C order, zero-overlap returns
    * immediately), then the two sliding scans; else the least-area best. */
  private def xladjust(objs: Array[Obj], op: Obj): (Int, Double, Double, Double) =
    val lp     = op.lbl
    val xincr  = (2 * lp.szx + op.sx) / XLXDENOM
    val yincr  = (2 * lp.szy + op.sy) / XLYDENOM
    val intrsx = new Array[Obj](9)
    inline def probe() = xlintersections(objs, op, intrsx)
    // x left: top / mid / bottom
    lp.px = op.px - lp.szx
    lp.py = op.py + op.sy
    var bp = probe()
    if bp._1 == 0 then return bp
    lp.py = op.py
    var nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // x mid: top / bottom
    lp.px = op.px
    lp.py = op.py + op.sy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // x right: top / mid / bottom
    lp.px = op.px + op.sx
    lp.py = op.py + op.sy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    lp.py = op.py - lp.szy
    nbp = probe()
    if nbp._1 == 0 then return nbp
    if nbp._2 < bp._2 then bp = nbp
    // sliding from top left
    if intrsx(XLPXNY) != null || intrsx(XLCXNY) != null || intrsx(XLNXNY) != null
      || intrsx(XLPXCY) != null || intrsx(XLPXPY) != null
    then
      if intrsx(XLCXNY) == null && intrsx(XLNXNY) == null then // some room right?
        lp.px = op.px - lp.szx; lp.py = op.py + op.sy
        while lp.px <= op.px + op.sx do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.px += xincr
      if intrsx(XLPXCY) == null && intrsx(XLPXPY) == null then // some room down?
        lp.px = op.px - lp.szx; lp.py = op.py + op.sy
        while lp.py >= op.py - lp.szy do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.py -= yincr
    // sliding from bottom right
    lp.px = op.px + op.sx
    lp.py = op.py - lp.szy
    if intrsx(XLNXPY) != null || intrsx(XLCXPY) != null || intrsx(XLPXPY) != null
      || intrsx(XLNXCY) != null || intrsx(XLNXNY) != null
    then
      if intrsx(XLCXPY) == null && intrsx(XLPXPY) == null then // some room left?
        lp.px = op.px + op.sx; lp.py = op.py - lp.szy
        while lp.px >= op.px - lp.szx do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.px -= xincr
      if intrsx(XLNXCY) == null && intrsx(XLNXNY) == null then // some room up?
        lp.px = op.px + op.sx; lp.py = op.py - lp.szy
        while lp.py <= op.py + op.sy do
          nbp = probe()
          if nbp._1 == 0 then return nbp
          if nbp._2 < bp._2 then bp = nbp
          lp.py += yincr
    bp

  // ── edgeMidpoint (splines.c:1297, EDGETYPE_SPLINE branch) ─────────────────
  private def dist2(a: Spline.XY, b: Spline.XY): Double =
    val dx = a.x - b.x; val dy = a.y - b.y
    dx * dx + dy * dy

  /** de Casteljau at t — gv's `Bezier(V, t, NULL, NULL)` (utils.c). */
  private def bezierAt(c: Array[Spline.XY], t: Double): Spline.XY =
    val vx = Array(c(0).x, c(1).x, c(2).x, c(3).x)
    val vy = Array(c(0).y, c(1).y, c(2).y, c(3).y)
    var i = 1
    while i <= 3 do
      var j = 0
      while j <= 3 - i do
        vx(j) = (1.0 - t) * vx(j) + t * vx(j + 1)
        vy(j) = (1.0 - t) * vy(j) + t * vy(j + 1)
        j += 1
      i += 1
    Spline.XY(vx(0), vy(0))

  /** dotneato_closest (utils.c:342): closest spline point to `pt` — nearest
    * control point picks the cubic, then a bisection refines t. */
  private def dotneatoClosest(pts: Vector[Spline.XY], pt: Spline.XY): Spline.XY =
    var besti = -1; var bestdist2 = 1e38
    var j = 0
    while j < pts.length do
      val d2 = dist2(pts(j), pt)
      if besti < 0 || d2 < bestdist2 then { besti = j; bestdist2 = d2 }
      j += 1
    var bestj = besti
    if bestj == pts.length - 1 then bestj -= 1
    val j0 = 3 * (bestj / 3)
    val c  = Array(pts(j0), pts(j0 + 1), pts(j0 + 2), pts(j0 + 3))
    var low = 0.0; var high = 1.0
    var dlow2  = dist2(c(0), pt)
    var dhigh2 = dist2(c(3), pt)
    var pt2 = c(0)
    var go  = true
    while go do
      val t = (low + high) / 2.0
      pt2 = bezierAt(c, t)
      if math.abs(dlow2 - dhigh2) < 1.0 then go = false
      else if math.abs(high - low) < 0.00001 then go = false
      else if dlow2 < dhigh2 then { high = t; dhigh2 = dist2(pt2, pt) }
      else { low = t; dlow2 = dist2(pt2, pt) }
    pt2

  private def edgeMidpoint(spl: Spline.ESpline): Spline.XY =
    val p = spl.sp.getOrElse(spl.pts.head)
    val q = spl.ep.getOrElse(spl.pts.last)
    if dist2(p, q) < 0.001 * 0.001 then p // APPROXEQPT MILLIPOINT: degenerate
    else dotneatoClosest(spl.pts, Spline.XY((q.x + p.x) / 2.0, (p.y + q.y) / 2.0))

  // ── addXLabels driver ──────────────────────────────────────────────────────
  /** Label (w, h) in pt, final frame — HTML-aware like `Coord.edgeLabelDim`. */
  private def labelDim(raw: String, isHtml: Boolean, fs: Double, fn: String, g: RGraph): (Double, Double) =
    def plain = (NodeSize.labelWidthPt(raw, fs, fn, g.name.getOrElse("")),
                 NodeSize.labelHeightPt(raw, fs, g.name.getOrElse("")))
    if isHtml then
      HtmlParser.parse(raw).map(h => HtmlLayout.size(h, fs, fn, g.images)).getOrElse(plain)
    else plain

  private def xlabelOf(attrs: org.jpablo.graphexplorer.graphviz.model.Attrs): Option[String] =
    attrs.get("xlabel").filter(_.nonEmpty)

  private def placeImpl(g: RGraph): Result =
    val empty = Result(Map.empty, Map.empty)
    val anyNode = g.nodes.exists(n => xlabelOf(n.attrs).isDefined)
    val anyEdge = g.edges.exists(e => xlabelOf(e.attrs).isDefined)
    if !anyNode && !anyEdge then return empty

    val flip    = Rank.flip(g)
    val xs      = XCoord.xCoords(g)
    val (ranks, yOf) = Coord.rankY(g)
    val spls    = Spline.splinesEx(g)
    val lps     = Spline.labelPositions(g)
    val nodeSeq = g.nodes.iterator.map(_.id).zipWithIndex.toMap
    val byTail  = g.edges.zipWithIndex.groupBy(_._1.tail)
    // agfstout order: a node's out-edges iterate by (head-node seq, decl idx)
    def outEdges(id: String): Seq[(REdge, Int)] =
      byTail.getOrElse(id, Vector.empty).sortBy((e, i) => (nodeSeq.getOrElse(e.head, Int.MaxValue), i))

    def swapped(w: Double, h: Double): (Double, Double) = if flip then (h, w) else (w, h)

    val objs = mutable.ArrayBuffer.empty[Obj]
    val lbls = mutable.ArrayBuffer.empty[XL]

    g.nodes.foreach { n =>
      val objBefore = objs.length
      for xPt <- xs.get(n.id); sz <- NodeSize.layoutSize(n, g) do
        val x  = xPt.value
        val y  = yOf(ranks(n.id)).value
        val sx = 2.0 * sz.halfWidthPt.value
        val sy = 2.0 * sz.halfHeightPt.value
        val xl = xlabelOf(n.attrs).map { raw =>
          val fs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
          val fn = n.attrs.getOrElse("fontname", "Times")
          val (w, h)   = labelDim(raw, n.attrs.isHtml("xlabel"), fs, fn, g)
          val (cw, ch) = swapped(w, h)
          new XL(cw, ch, Left(n.id))
        }.orNull
        if xl != null then lbls += xl
        objs += new Obj(x - sx / 2.0, y - sy / 2.0, sx, sy, xl)
      if objs.length == objBefore then () // unplaced node: no obstacle
      outEdges(n.id).foreach { (e, idx) =>
        // SET dot edge label ⇒ obstacle at lp (addLabelObj)
        lps.get(idx).foreach { p =>
          val (w, h)   = Coord.edgeLabelDim(e, g)
          val (cw, ch) = swapped(w, h)
          objs += new Obj(p.x - cw / 2.0, p.y - ch / 2.0, cw, ch, null)
        }
        // unset edge xlabel ⇒ size-0 anchor at the edge midpoint
        xlabelOf(e.attrs).foreach { raw =>
          spls.get(idx).foreach { spl =>
            val fs = e.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
            val fn = e.attrs.getOrElse("fontname", "Times")
            val (w, h)   = labelDim(raw, e.attrs.isHtml("xlabel"), fs, fn, g)
            val (cw, ch) = swapped(w, h)
            val xl  = new XL(cw, ch, Right(idx))
            val mid = edgeMidpoint(spl)
            lbls += xl
            objs += new Obj(mid.x, mid.y, 0.0, 0.0, xl)
          }
        }
      }
    }
    if lbls.isEmpty then return empty

    // placeLabels: greedy in object order; forcelabels (default true) keeps
    // the least-overlap position when no clean one exists.
    val force = g.rootAttrs.get("forcelabels").map(_.toLowerCase) match
      case Some("false") | Some("no") => false
      case Some(v)                    => v.toIntOption.forall(_ > 0)
      case None                       => true
    val arr = objs.toArray
    arr.foreach { op =>
      if op.lbl != null then
        val bp = xladjust(arr, op)
        if bp._1 == 0 then op.lbl.set = true
        else if bp._2 == 0.0 || force then
          op.lbl.px = bp._3; op.lbl.py = bp._4
          op.lbl.set = true
    }

    val nodesOut = Map.newBuilder[String, Placed]
    val edgesOut = Map.newBuilder[Int, Placed]
    lbls.foreach { xl =>
      if xl.set then
        val cx = xl.px + xl.szx / 2.0 // centerPt
        val cy = xl.py + xl.szy / 2.0
        val (w, h) = if flip then (xl.szy, xl.szx) else (xl.szx, xl.szy)
        xl.owner match
          case Left(id)   => nodesOut += id -> Placed(cx, cy, w, h)
          case Right(idx) => edgesOut += idx -> Placed(cx, cy, w, h)
    }
    Result(nodesOut.result(), edgesOut.result())

end XLabels
