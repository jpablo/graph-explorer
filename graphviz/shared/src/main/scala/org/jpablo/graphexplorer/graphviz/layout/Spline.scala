package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.units.Length.Pt
import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel, HtmlTableLayout}
import scala.collection.mutable

/** Phase 4 of the `dot` pipeline: edge spline routing — full box-fit port.
  *
  * Faithful reimplementation of Graphviz 13.0.1's regular-edge router
  * (`lib/dotgen/dotsplines.c` `make_regular_edge`/`completeregularpath` +
  * `lib/common/routespl.c` `routesplines` + `lib/pathplan` `Proutespline`),
  * scoped to label-free TB graphs with `NORMAL` real endpoints (no ports,
  * flat edges, clusters, or `pboxfn` shapes — those remain M6 deferrals).
  *
  * Pipeline, per declared edge:
  *  1. build the **box channel** (tail half-box, per-rank `rank_box`, per
  *     virtual-node `maximal_bbox`, head half-box) — `completeregularpath`;
  *  2. `adjustregularpath` + `checkpath` repair (degenerate/overlap/clamp);
  *  3. build the channel **polygon** (`routesplines_` outline walk);
  *  4. **shortest path** through the channel (taut-string funnel over the
  *     consecutive box portals — the geodesic `Pshortestpath` computes);
  *  5. **`Proutespline`**: recursive least-squares cubic-Bézier fit kept
  *     inside the polygon barriers (unconstrained endpoint slopes);
  *  6. clip the end segments to the node ellipse boundary + `ARROW_LENGTH`
  *     gap at a directed head (de Casteljau split — `clip_and_install`).
  *
  * Derived and verified against an instrumented gv-13.0.1 build (PORT.md
  * §2.5): the box channel, shortest path, and spline reproduce the dumps
  * for 01/06/07; gated on dense-sample Hausdorff vs the `plain` golden.
  */
object Spline:

  private val MINW        = 16.0           // dotsplines.c min box width
  private val HALFMINW    = 8.0
  private val FUDGE        = 4.0           // maximal_bbox FUDGE

  /** 2D point in the layout coordinate system. The `x`/`y` fields are
    * raw Doubles for the in-kernel arithmetic (math.hypot, tuple math,
    * box clipping); Spline's external surface — the ESpline below —
    * always carries pt-scale coordinates by construction. */
  final case class XY(x: Double, y: Double)

  /** Installed edge spline: piecewise-cubic control points plus the arrow
    * attach points Graphviz records on the `bezier` struct — `ep` (head, set
    * iff a head arrow is drawn) / `sp` (tail). These drive json0's
    * `e,EX,EY`/`s,SX,SY` `pos` prefixes. All coordinates are in points. */
  final case class ESpline(pts: Vector[XY], ep: Option[XY], sp: Option[XY])
  private final case class Box(var llx: Double, var lly: Double, var urx: Double, var ury: Double)

  /** Declared-edge index (position in `g.edges`) → piecewise-cubic control
    * points. Keyed by edge **identity**, not `(tail,head)`, so parallel /
    * port-distinguished multi-edges (04's two `struct1→struct2`) do not
    * collapse — every consumer correlates by the `g.edges` index. */
  def splines(g: RGraph): Map[Int, Vector[XY]] =
    splinesEx(g).view.mapValues(_.pts).toMap

  /** Declared-edge index (position in `g.edges`) → installed spline + arrow
    * attaches. See [[splines]] for why the key is the edge index. The second
    * map is the edge-label positions (`lp`), produced by the same pass because
    * gv's `place_vnlabel` reads the label vnode's *post-routing* x — after
    * `recover_slack` snaps it to its channel box (dotsplines.c:498/2126). */
  private val splinesMemo = GraphMemo[(Map[Int, ESpline], Map[Int, XY])]()
  def splinesEx(g: RGraph): Map[Int, ESpline] = splinesMemo(g)(splinesExImpl(g))._1

  /** Edge-label position `lp` (json0 `lp` / svg text), keyed by g.edges index —
    * `lp = routedLabelVnode.x + labelWidth/2` (place_vnlabel), where the vnode x
    * is the RIGHT edge of the box the labelled edge threads (recover_slack). */
  def labelPositions(g: RGraph): Map[Int, XY] = splinesMemo(g)(splinesExImpl(g))._2

  private def splinesExImpl(g: RGraph): (Map[Int, ESpline], Map[Int, XY]) =
    val res          = Order.order(g)
    val (_, allXNode) = XCoord.solveAll(g)
    val (_, yOf)     = Coord.rankY(g)
    val byId         = g.nodes.iterator.map(n => n.id -> n).toMap
    // GD_nodesep (attr-driven), the spline channel separation (nodesep/4),
    // and a plain virtual node's half width (1 + nodesep/2). Default 18/4.5/10.
    val NodeSep     = Coord.nodeSepPt(g)
    // dot_splines_ (dotsplines.c:275): `.Splinesep = GD_nodesep(g) / 4` — with
    // GD_nodesep an *int* (types.h), this is C INTEGER division: 18/4 = 4, not
    // 4.5. (Contrast maximal_bbox's `GD_nodesep(g) / 2.` — the trailing dot
    // makes that one floating division.) The 0.5pt matters: a cluster-wall
    // clamp `round(bb.urx + Splinesep)` lands at 82 vs 83 (162-cluster-style).
    val Splinesep   = (NodeSep.toInt / 4).toDouble
    val VirtualHalf = Coord.virtualHalfPt(g)
    // The Order/XCoord migration to LayoutNode preserves all type-safety at
    // construction. Spline's internals stay String-keyed for its 50+ lookup
    // sites (no unit-mixing risk inside a pure-numerical kernel — see the
    // "type the boundaries, leave the kernels" principle in the Phase 3
    // Pt-flow commit). Convert at the consumption boundary:
    val allX: Map[String, Pt] = allXNode.iterator.map((k, v) => k.name -> v).toMap
    // Reverse index of the same conversion: String key → the ORIGINAL typed
    // node, so recovering a Virtual's owning edge is a lookup of the upstream
    // value, not a re-parse of the name it was erased to.
    val nodeOf: Map[String, LayoutNode] = allXNode.keysIterator.map(k => k.name -> k).toMap
    val orderByRank: Map[Int, Vector[String]] =
      res.order.view.mapValues(_.map(_.name)).toMap
    val rankOf       = orderByRank.iterator.flatMap { case (r, ids) => ids.map(_ -> r) }.toMap

    def isV(id: String): Boolean = LayoutNode.isVirtualName(id)
    // recover_slack (dotsplines.c:2126) mutates virtual-node x/lw/rw after each
    // edge routes, so later edges see the shifted neighbours. These overrides
    // hold those in-flight resizes; absent an override the static solve wins.
    val cxOv = mutable.HashMap.empty[String, Double]
    val lwOv = mutable.HashMap.empty[String, Double]
    val rwOv = mutable.HashMap.empty[String, Double]
    val labelPos = mutable.HashMap.empty[Int, XY] // origIdx → lp (place_vnlabel)
    def cx(id: String): Double   = cxOv.getOrElse(id, allX(id).value)
    def cy(id: String): Double   = yOf(rankOf(id)).value
    val labelW = Coord.labelVnodeWidths(g) // class2 label_vnode: lw=nodesep, rw=labelWidth
    def isLabelV(id: String): Boolean = labelW.contains(id)
    // gv quirk (as in XCoord): intra-cluster chain vnodes are created by
    // class2(subg), and incr_width reads GD_nodesep(subg) — unset (0) on
    // cluster subgraphs — so they keep lw=rw=1, not 1 + nodesep/2.
    val intraVEdge: Set[Int] =
      val clustOfName = Cluster.clustOf(g)
      val ends = g.edges.filter(e => e.tail != e.head).map(e => (e.tail, e.head))
      ends.indices.filter { i =>
        (clustOfName.get(ends(i)._1), clustOfName.get(ends(i)._2)) match
          case (Some(a), Some(b)) => a == b
          case _                  => false
      }.toSet
    def vHalf(id: String): Double = nodeOf.get(id) match
      case Some(LayoutNode.Virtual(d, _)) if intraVEdge(d) => 1.0
      case _                                               => VirtualHalf
    def lw0(id: String): Double =
      if labelW.contains(id) then NodeSep
      else if isV(id) then vHalf(id)
      else byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map(_.halfWidthPt.value).getOrElse(1.0)
    def lw(id: String): Double  = lwOv.getOrElse(id, lw0(id))
    def rw(id: String): Double  = rwOv.getOrElse(id, labelW.getOrElse(id, lw0(id)))
    // gv `ND_mval`: the PRE-spline rw (set at set_xcoords, before recover_slack
    // narrows a virtual). `rw` without the `rwOv` snap override. For a
    // SELF-LOOP node the dot_splines swap leaves mval holding the
    // selfRightSpace-INFLATED rw (position parked the original in mval and
    // inflated rw; dot_splines swaps them back for routing) — so maximal_bbox
    // sees the loop+label space when the node is a neighbour.
    val selfRwSpl: Map[String, Double] =
      g.edges.filter(e => e.tail == e.head).groupBy(_.tail).view
        .mapValues(_.map(Coord.selfRightSpace(_, g)).sum).toMap
    def mvalRw(id: String): Double =
      labelW.getOrElse(id, lw0(id) + selfRwSpl.getOrElse(id, 0.0))

    // ht1/ht2 (GD_rank[r] half-heights): rank_box/maximal_bbox use the
    // CLUSTER-INFLATED per-rank values (clust_ht adds label bands + margins
    // at a cluster's extreme ranks) — spline channels stop at cluster
    // borders. Coord.yInfo owns the set_ycoords node scan + inflation;
    // without clusters these equal the plain node-scan half-heights.
    val yiHt = Coord.yInfo(g)
    def ht1(r: Int): Double = yiHt.ht1.getOrElse(r, 0.0)
    def ht2(r: Int): Double = yiHt.ht2.getOrElse(r, 0.0)

    // ── LeftBound / RightBound (dot_splines_) ───────────────────────────────
    val ranksSorted = orderByRank.keys.toVector.sorted
    var leftBound   = 0.0
    var rightBound  = 0.0
    ranksSorted.foreach { r =>
      val row = orderByRank(r)
      if row.nonEmpty then
        leftBound  = math.min(leftBound, cx(row.head) - lw(row.head))
        rightBound = math.max(rightBound, cx(row.last) + rw(row.last))
      leftBound  -= MINW
      rightBound += MINW
    }

    // neighbor(vn, dir): nearest rank-order node in dir not on `path`'s own
    // virtual chain (the legacy approximation, kept for the HTML port branch
    // and flat ends — equivalent to gv's when no chains cross).
    def neighbor(vn: String, dir: Int, ownPath: Set[String]): Option[String] =
      val row = orderByRank(rankOf(vn))
      val idx = row.indexOf(vn)
      var i   = idx + dir
      var ans: Option[String] = None
      while ans.isEmpty && i >= 0 && i < row.length do
        val n = row(i)
        if !ownPath.contains(n) then ans = Some(n)
        i += dir
      ans

    // cl_bound (dotsplines.c:2198): the REAL cluster of `adj` that interferes
    // with vn's channel — distinct from vn's own (or its original edge's
    // endpoints') clusters; a virtual adj must actually lie inside the box
    // (cl_vninside). The channel then stops at that cluster's border
    // ± Splinesep instead of at the neighbour node itself.
    val cluBBs = Cluster.bbs(g)
    val cluOf  = if cluBBs.isEmpty then Map.empty[String, Int] else Cluster.clustOf(g)
    val dedges = g.edges.filter(e => e.tail != e.head)
    def origEnds(vname: String): Option[(String, String)] =
      nodeOf.get(vname) match
        case Some(LayoutNode.Virtual(d, _)) => dedges.lift(d).map(e => (e.tail, e.head))
        case _                              => None
    def clBound(n: String, adj: String): Option[Int] =
      if cluBBs.isEmpty then None
      else
        val (tcl, hcl) =
          if !isV(n) then { val c = cluOf.get(n); (c, c) }
          else
            origEnds(n) match
              case Some((t, h)) => (cluOf.get(t), cluOf.get(h))
              case None         => (None, None)
        def foreign(c: Int): Boolean = !tcl.contains(c) && !hcl.contains(c)
        def vninside(ci: Int, m: String): Boolean =
          val bb = cluBBs(ci)
          bb.llx <= cx(m) && cx(m) <= bb.urx && bb.lly <= cy(m) && cy(m) <= bb.ury
        if !isV(adj) then cluOf.get(adj).filter(foreign)
        else
          origEnds(adj).flatMap { (t2, h2) =>
            cluOf.get(t2).filter(c => foreign(c) && vninside(c, adj))
              .orElse(cluOf.get(h2).filter(c => foreign(c) && vninside(c, adj)))
          }

    // ── gv's real neighbor rule (dotsplines.c:2310) ─────────────────────────
    // A plain virtual in-rank neighbour is SKIPPED when its chain CROSSES
    // vn's (pathscross) — the channel then extends past it to the next
    // normal/labelled/non-crossing node (ds: node7's box reaches past the
    // crossing V(4,*) chains). Needs the fast-graph chain topology.
    val orderIdx: Map[String, Int] =
      orderByRank.iterator.flatMap((_, ids) => ids.zipWithIndex).toMap
    // working ends + rank span per dedge (segments run working tail → head)
    val workEnds: Vector[(String, String, Int, Int)] = dedges.map { e =>
      val rt = rankOf.getOrElse(e.tail, 0); val rh = rankOf.getOrElse(e.head, 0)
      if rt <= rh then (e.tail, e.head, rt, rh) else (e.head, e.tail, rh, rt)
    }
    // fast in/out degrees (chain segments only; flat edges live elsewhere)
    val outDeg = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    val inDeg  = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    workEnds.foreach { case (wt, wh, lo, hi) =>
      if lo != hi then { outDeg(wt) += 1; inDeg(wh) += 1 }
    }
    def outSize(n: String): Int = if isV(n) then 1 else outDeg(n)
    def inSize(n: String): Int  = if isV(n) then 1 else inDeg(n)
    // a real node's UNIQUE out/in segment (only queried when the degree is 1)
    val realOutSeg: Map[String, (String, String)] =
      workEnds.zipWithIndex.collect { case ((wt, wh, lo, hi), d) if lo != hi =>
        wt -> (wt, if lo + 1 <= hi - 1 then LayoutNode.Virtual(d, lo + 1).name else wh)
      }.toMap
    val realInSeg: Map[String, (String, String)] =
      workEnds.zipWithIndex.collect { case ((wt, wh, lo, hi), d) if lo != hi =>
        wh -> (if hi - 1 >= lo + 1 then LayoutNode.Virtual(d, hi - 1).name else wt, wh)
      }.toMap
    def chainNextOf(vn: String): String = nodeOf(vn) match
      case LayoutNode.Virtual(d, r) =>
        val (_, wh, _, hi) = workEnds(d)
        if r + 1 <= hi - 1 then LayoutNode.Virtual(d, r + 1).name else wh
      case _ => vn
    def chainPrevOf(vn: String): String = nodeOf(vn) match
      case LayoutNode.Virtual(d, r) =>
        val (wt, _, lo, _) = workEnds(d)
        if r - 1 >= lo + 1 then LayoutNode.Virtual(d, r - 1).name else wt
      case _ => vn
    def outSegOf(n: String): Option[(String, String)] =
      if isV(n) then Some((n, chainNextOf(n))) else realOutSeg.get(n)
    def inSegOf(n: String): Option[(String, String)] =
      if isV(n) then Some((chainPrevOf(n), n)) else realInSeg.get(n)

    /** pathscross (dotsplines.c:2334): do n0's and n1's chains cross within
      * two steps up/down? `ie1`/`oe1` are n1's own in/out segments. */
    def pathscross(n0: String, n1: String,
                   ie1: Option[(String, String)], oe1: Option[(String, String)]): Boolean =
      val order = orderIdx(n0) > orderIdx(n1)
      if outSize(n0) != 1 && outSize(n1) != 1 then return false
      var e1 = oe1
      if outSize(n0) == 1 && e1.isDefined then
        var e0 = outSegOf(n0)
        var cnt = 0
        var stop = false
        while !stop && cnt < 2 do
          val na = e0.get._2; val nb = e1.get._2
          if na == nb then stop = true
          else if order != (orderIdx(na) > orderIdx(nb)) then return true
          else if outSize(na) != 1 || !isV(na) then stop = true
          else
            e0 = Some((na, chainNextOf(na)))
            if outSize(nb) != 1 || !isV(nb) then stop = true
            else e1 = Some((nb, chainNextOf(nb)))
          cnt += 1
      e1 = ie1
      if inSize(n0) == 1 && e1.isDefined then
        var e0 = inSegOf(n0)
        var cnt = 0
        var stop = false
        while !stop && cnt < 2 do
          val na = e0.get._1; val nb = e1.get._1
          if na == nb then stop = true
          else if order != (orderIdx(na) > orderIdx(nb)) then return true
          else if inSize(na) != 1 || !isV(na) then stop = true
          else
            e0 = Some((chainPrevOf(na), na))
            if inSize(nb) != 1 || !isV(nb) then stop = true
            else e1 = Some((chainPrevOf(nb), nb))
          cnt += 1
      false

    /** neighbor (dotsplines.c:2310): the first normal or labelled-virtual
      * node in `dir`, or the first plain virtual whose chain does NOT cross. */
    def neighborGv(vn: String, dir: Int,
                   ie: Option[(String, String)], oe: Option[(String, String)]): Option[String] =
      val row = orderByRank(rankOf(vn))
      var i   = orderIdx(vn) + dir
      var ans: Option[String] = None
      while ans.isEmpty && i >= 0 && i < row.length do
        val n = row(i)
        if isLabelV(n) then ans = Some(n)
        else if !isV(n) then ans = Some(n)
        else if !pathscross(n, vn, ie, oe) then ans = Some(n)
        i += dir
      ans

    // maximal_bbox(vn): widest box up to in-rank neighbours (legacy
    // ownPath-neighbour form — HTML branch, topBoxes, flat ends).
    def maximalBbox(vn: String, ownPath: Set[String]): Box =
      maximalBboxCore(vn, neighbor(vn, -1, ownPath), neighbor(vn, 1, ownPath))

    /** maximal_bbox with gv's pathscross-aware neighbours (dotsplines.c:2251)
      * — `ie`/`oe` are vn's own in/out segments as gv passes them. */
    def maximalBboxGv(vn: String, ie: Option[(String, String)], oe: Option[(String, String)]): Box =
      maximalBboxCore(vn, neighborGv(vn, -1, ie, oe), neighborGv(vn, 1, ie, oe))

    def maximalBboxCore(vn: String, leftNb: Option[String], rightNb: Option[String]): Box =
      val r = rankOf(vn)
      val labelVn = isLabelV(vn)
      var b = cx(vn) - lw(vn) - FUDGE
      val llx =
        leftNb match
          case Some(left) =>
            val nb = clBound(vn, left) match
              case Some(cl) => cluBBs(cl).urx + Splinesep
              case None =>
                // gv `maximal_bbox` clamps by `ND_coord(left).x + ND_mval(left)`
                // — `ND_mval` is the PRE-spline rw (a safe copy from set_xcoords),
                // NOT the recover_slack-snapped `ND_rw`. So a snapped left-vnode
                // still reserves its ORIGINAL half-width here (else a narrowed
                // neighbour would let this box creep in, shortening the spline).
                cx(left) + mvalRw(left) + (if isV(left) then Splinesep else NodeSep / 2.0)
            if nb < b then b = nb
            math.round(b).toDouble
          case None => math.min(math.round(b).toDouble, leftBound)
      // "we have to leave room for our own label" (maximal_bbox, dotsplines.c:
      // 2276): a label vnode routes its edge to the LEFT of the label text, so
      // the right bound starts at x+10 (not x+rw) and — after the neighbour
      // clamp — the label width is subtracted, leaving the label its own strip.
      var b2 = if labelVn then cx(vn) + 10.0 else cx(vn) + rw(vn) + FUDGE
      var urx =
        rightNb match
          case Some(right) =>
            val nb = clBound(vn, right) match
              case Some(cl) => cluBBs(cl).llx - Splinesep
              case None =>
                cx(right) - lw(right) - (if isV(right) then Splinesep else NodeSep / 2.0)
            if nb > b2 then b2 = nb
            math.round(b2).toDouble
          case None => math.max(math.round(b2).toDouble, rightBound)
      if labelVn then
        urx -= rw(vn)
        if urx < llx then urx = cx(vn)
      Box(llx, cy(vn) - ht1(r), urx, cy(vn) + ht2(r))

    // rank_box(r): full-width inter-rank gap between rank r (upper) and r+1.
    def rankBox(rUpper: Int, rLower: Int): Box =
      Box(leftBound, yOf(rLower).value + ht2(rLower), rightBound, yOf(rUpper).value - ht1(rUpper))

    // limitBoxes (routespl.c:242): after routesplines fits the spline, gv resets
    // every channel box's x-range and re-fills it by finely sampling the spline
    // (INIT_DELTA·boxn points/segment) — each box's [LL.x, UR.x] becomes the
    // min/max spline x among samples whose y lands in the box. This is the
    // corridor-collapse that recover_slack then reads. `ctrl` is the *unclipped*
    // spline; boxes keep their y-ranges (only x is re-derived).
    def limitBoxes(boxes: mutable.ArrayBuffer[Box], ctrl: Vector[XY]): Unit =
      val pts =
        if ctrl.length >= 4 && (ctrl.length - 1) % 3 == 0 then ctrl
        else
          val s = ctrl.head; val t = ctrl.last
          Vector(s, XY(s.x + (t.x - s.x) / 3, s.y + (t.y - s.y) / 3),
                 XY(s.x + 2 * (t.x - s.x) / 3, s.y + 2 * (t.y - s.y) / 3), t)
      var delta = 10.0 // INIT_DELTA
      var tries = 0
      var done  = false
      while !done && tries < 15 do // LOOP_TRIES
        boxes.foreach { b => b.llx = Double.MaxValue; b.urx = -Double.MaxValue }
        val numDiv = delta * boxes.length
        var pi = 0
        while pi + 3 < pts.length do
          var si = 0.0
          while si <= numDiv do
            val t = si / numDiv
            var ax = pts(pi).x;     var ay = pts(pi).y
            var bx = pts(pi + 1).x; var by = pts(pi + 1).y
            var cx2 = pts(pi + 2).x; var cy2 = pts(pi + 2).y
            val dx = pts(pi + 3).x; val dy = pts(pi + 3).y
            ax += t * (bx - ax); ay += t * (by - ay)
            bx += t * (cx2 - bx); by += t * (cy2 - by)
            cx2 += t * (dx - cx2); cy2 += t * (dy - cy2)
            ax += t * (bx - ax); ay += t * (by - ay)
            bx += t * (cx2 - bx); by += t * (cy2 - by)
            ax += t * (bx - ax); ay += t * (by - ay)
            boxes.foreach { b =>
              if ay <= b.ury + 0.0001 && ay >= b.lly - 0.0001 then // FUDGE
                b.llx = math.min(b.llx, ax); b.urx = math.max(b.urx, ax)
            }
            si += 1
          pi += 3
        if boxes.forall(b => b.llx != Double.MaxValue && b.urx != -Double.MaxValue) then done = true
        else { delta *= 2; tries += 1 }

    // recover_slack (dotsplines.c:2126): after an edge routes, snap each of its
    // virtual nodes to the (limitBoxes-narrowed) corridor box it threads — a
    // LABEL vnode to the box's RIGHT edge (the label then extends rw further
    // right; place_vnlabel puts lp at x+labelWidth/2; lw = full box width),
    // plain vnodes to the box centre. Mutates the shared x/lw/rw so later edges
    // see the shift (15: a→b's snap makes a→c start past the label; 02: the
    // label vnode's narrowed lw is what clamps the long start→end edge's box).
    def recoverSlack(vns: Vector[String], boxes: mutable.ArrayBuffer[Box], origIdx: Int): Unit =
      var b = 0
      vns.foreach { vn =>
        val vy = cy(vn)
        while b < boxes.length && boxes(b).lly > vy do b += 1
        if b < boxes.length && boxes(b).ury >= vy then
          val bx = boxes(b)
          if isLabelV(vn) then
            val wl = rw(vn) // labelWidth (unchanged by resize)
            cxOv(vn) = bx.urx; lwOv(vn) = bx.urx - bx.llx; rwOv(vn) = wl
            labelPos(origIdx) = XY(bx.urx + wl / 2.0, vy)
          else
            val c = (bx.llx + bx.urx) / 2.0
            cxOv(vn) = c; lwOv(vn) = c - bx.llx; rwOv(vn) = bx.urx - c
      }

    // beginpath TOP (splines.c:419) — three boxes that route the spline up out
    // of the node then down its go-left/right side: b0 (above the node from the
    // port height up), b (down the chosen side), bc (maximal_bbox clamped to the
    // side box's y). `portY` = the un-nudged cell-top world y.
    // GD_ranksep as dotsplines sees it (box top only; not path-critical) —
    // derived, not the hardcoded 36.0 default, so non-default `ranksep` graphs
    // stay faithful here too.
    val TopRankSep = Coord.flatVspaceTopRank(g)
    def topBoxes(id: String, portY: Double, goLeft: Boolean, ownPath: Set[String]): Vector[Box] =
      val nb   = maximalBbox(id, ownPath)
      val ncx  = cx(id); val ncy = cy(id); val r = rankOf(id)
      val bTop = ncy + ht2(r) + TopRankSep / 2.0
      val yLo  = ncy - ht2(r)
      if goLeft then
        Vector(
          Box(nb.llx - 1, portY, nb.urx, bTop),          // b0: above the node
          Box(nb.llx - 1, yLo, ncx - lw(id), portY),     // b : down the left side
          Box(nb.llx, yLo, nb.urx, portY)                // bc: maximal_bbox clamped
        )
      else
        Vector(
          Box(nb.llx, portY, nb.urx + 1, bTop),
          Box(ncx + rw(id), yLo, nb.urx + 1, portY),
          Box(nb.llx, yLo, nb.urx, portY)
        )

    // installed spline keyed by the declared-edge index (position in
    // g.edges) — parallel/port-distinguished multi-edges must NOT collapse.
    val out = mutable.LinkedHashMap.empty[Int, ESpline]

    def halfH(id: String): Double =
      byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map(_.halfHeightPt.value).getOrElse(0.0)
    val centerOf: String => XY = id => XY(cx(id), cy(id))

    // ── beginpath/endpath port branch (record field ports, TB) ────────────
    // Returns the per-end channel box, aim point and constrained tangent for
    // a record-port endpoint, or None to fall back to the node-centred path.
    // `top`/`portY`/`goLeft`: an against-grain TOP-side port (tail exits the
    // cell top toward a lower head) → the port route builds the go-left/right
    // box channel that loops around the node (beginpath TOP, splines.c:419).
    final case class End(box: Box, p: XY, theta: Double, constrained: Boolean, clip: Boolean,
                         top: Boolean = false, portY: Double = 0.0, goLeft: Boolean = true)

    def recRoot(id: String): Option[RecordLabel.Field] =
      byId.get(id).flatMap(n => NodeSize.recordLayout(n, g))

    // ── canonical-frame port machinery (splines.c beginpath/endpath) ────────
    // gv resolves record `:fN` ports AT ROUTE TIME: the stored port is `dyna`
    // (compass `_`), and beginpath/endpath call resolvePort/closestSide with
    // the segment's OTHER endpoint — so the same declared port lands on a
    // different field side per edge. The resolved port then shapes the end's
    // channel boxes (side branches / record_path corridor) and start point.
    val rd        = Rank.rankdir(g)
    val flip      = Rank.flip(g)
    val RanksepPt = Coord.rankSepBasePt(g)
    // splines=polyline (utils.c edgeType → routespl.c:471): the installed
    // route is the Pshortestpath funnel POLYLINE run through make_polyline
    // (pathplan/util.c:59 — endpoints doubled, interior corners tripled into
    // degenerate cubics); Proutespline never runs, endpoint slopes ignored.
    val polylineMode = g.rootAttrs.get("splines").map(_.trim).contains("polyline")
    def makePolyline(pl: Vector[XY]): Vector[XY] =
      if pl.length < 2 then pl
      else
        val out = Vector.newBuilder[XY]
        out += pl.head; out += pl.head
        var i = 1
        while i < pl.length - 1 do { out += pl(i); out += pl(i); out += pl(i); i += 1 }
        out += pl.last; out += pl.last
        out.result()
    val BpFudge   = 2.0 // splines.c FUDGE (≠ dotsplines.c FUDGE=4)

    /** Route-time port resolution (beginpath:395 / endpath:592): initial
      * `record_port` struct, dyna re-resolved against `otherCanon`. Portless
      * or unresolvable ⇒ gv's `Center` (clip to the node, p = centre).
      * Returns (resolved port, ORIG clip flag): clip_and_install reads the
      * clip off the ORIGINAL edge — the INITIAL resolution's flag (false for
      * explicit compass ports, true for dyna/centre) — because a dyna's
      * route-time side resolution lives only on the working copy. */
    def endPort(id: String, portOpt: Option[org.jpablo.graphexplorer.graphviz.dotlang.Port],
                otherCanon: XY): (PortAnchor.GvPort, Boolean) =
      portOpt.flatMap(pp => byId.get(id).flatMap(n =>
        PortAnchor.gvRecordPort(n, g, pp)
          .orElse(PortAnchor.gvHtmlPort(n, g, pp))
          .orElse(PortAnchor.gvPolyPort(n, g, pp)))) match
        case None => (PortAnchor.GvPort.center, true)
        case Some(gp0) =>
          val resolved =
            if gp0.dyna then PortAnchor.resolveDyna(gp0, rd, (cx(id), cy(id)), (otherCanon.x, otherCanon.y))
            else gp0
          (resolved, gp0.clip)

    /** record_path (shapes.c:3817), canonical: the top-level field whose
      * canonical-x slice holds the port x, as a full-node-height corridor
      * (`flip_rec_boxf` for LR/RL is the transpose of the true-frame box). */
    def recordPathBox(id: String, px: Double): Option[Box] =
      recRoot(id).flatMap { root =>
        root.flds.find { f =>
          val (ls, rs) = if flip then (f.lly, f.ury) else (f.llx, f.urx)
          ls <= px && px <= rs
        }.map { f =>
          val hh = halfH(id)
          if flip then Box(cx(id) + f.lly, cy(id) + f.llx, cx(id) + f.ury, cy(id) + hh)
          else Box(cx(id) + f.llx, cy(id) - hh, cx(id) + f.urx, cy(id) + hh)
        }
      }

    /** beginpath (splines.c:387), REGULAREDGE: P.start (post-nudge), the
      * constrained tangent, the tail-end channel boxes, and whether the side
      * branch cleared the orig port's clip flag. `nb` = maximal_bbox. */
    def beginPathR(id: String, gp: PortAnchor.GvPort, nb: Box): (XY, Option[Double], Vector[Box], Boolean) =
      var sx = cx(id) + gp.px
      var sy = cy(id) + gp.py
      val hh = halfH(id)
      val th = if gp.constrained then Some(gp.theta) else None
      if gp.side != 0 then
        val ncx = cx(id); val ncy = cy(id)
        val boxes =
          if (gp.side & RecordLabel.Top) != 0 then
            val bs =
              if sx < ncx then // go left
                Vector(
                  Box(nb.llx - 1, sy, nb.urx, ncy + hh + RanksepPt / 2.0),
                  Box(nb.llx - 1, ncy - hh, ncx - lw(id) - (BpFudge - 2), sy))
              else
                Vector(
                  Box(nb.llx, sy, nb.urx + 1, ncy + hh + RanksepPt / 2.0),
                  Box(ncx + rw(id) + (BpFudge - 2), ncy - hh, nb.urx + 1, sy))
            sy += 1
            bs
          else if (gp.side & RecordLabel.Bottom) != 0 then
            val b = Box(nb.llx, nb.lly, nb.urx, math.max(nb.ury, sy))
            sy -= 1
            Vector(b)
          else if (gp.side & RecordLabel.Left) != 0 then
            val b = Box(nb.llx, ncy - hh, sx, sy)
            sx -= 1
            Vector(b)
          else
            val b = Box(sx, ncy - hh, nb.urx, sy)
            sx += 1
            Vector(b)
        (XY(sx, sy), th, boxes, true)
      else
        val corridor = if gp.defined then recordPathBox(id, gp.px) else None
        corridor match
          case Some(cb) => (XY(sx, sy), th, Vector(cb), false)
          case None =>
            val b = Box(nb.llx, nb.lly, nb.urx, sy) // UR.y = start.y
            sy -= 1
            (XY(sx, sy), th, Vector(b), false)

    /** endpath (splines.c:584), REGULAREDGE — the head-side mirror. */
    def endPathR(id: String, gp: PortAnchor.GvPort, nb: Box): (XY, Option[Double], Vector[Box], Boolean) =
      var ex = cx(id) + gp.px
      var ey = cy(id) + gp.py
      val hh = halfH(id)
      val th = if gp.constrained then Some(gp.theta) else None
      if gp.side != 0 then
        val ncx = cx(id); val ncy = cy(id)
        val boxes =
          if (gp.side & RecordLabel.Top) != 0 then
            val b = Box(nb.llx, math.min(nb.lly, ey), nb.urx, nb.ury)
            ey += 1
            Vector(b)
          else if (gp.side & RecordLabel.Bottom) != 0 then
            val bs =
              if ex < ncx then // go left
                Vector(
                  Box(nb.llx - 1, ncy - hh - RanksepPt / 2.0, nb.urx, ey),
                  Box(nb.llx - 1, ey, ncx - lw(id) - (BpFudge - 2), ncy + hh))
              else
                Vector(
                  Box(nb.llx, ncy - hh - RanksepPt / 2.0, nb.urx + 1, ey),
                  Box(ncx + rw(id) + (BpFudge - 2), ey, nb.urx + 1, ncy + hh))
            ey -= 1
            bs
          else if (gp.side & RecordLabel.Left) != 0 then
            val b = Box(nb.llx, ey, ex, ncy + hh)
            ex -= 1
            Vector(b)
          else
            val b = Box(ex, ey, nb.urx, ncy + hh)
            ex += 1
            Vector(b)
        (XY(ex, ey), th, boxes, true)
      else
        val corridor = if gp.defined then recordPathBox(id, gp.px) else None
        corridor match
          case Some(cb) => (XY(ex, ey), th, Vector(cb), false)
          case None =>
            val b = Box(nb.llx, ey, nb.urx, nb.ury) // LL.y = end.y
            ey += 1
            (XY(ex, ey), th, Vector(b), false)

    /** HTML `<td port[:compass]>` endpoint. A compass forces the exact cell
      * point + outward tangent (compassPort); no compass ⇒ the record *dyna*
      * port (aim at the cell side closest to the other endpoint). Either way the
      * endpoint carries the ±1 begin/endpath nudge and a constrained tangent
      * (clip=false — the point IS the endpoint). TB scope. */
    def htmlPortEnd(id: String, other: String, port: org.jpablo.graphexplorer.graphviz.dotlang.Port, isTail: Boolean): Option[End] =
      import org.jpablo.graphexplorer.graphviz.dotlang.Compass.*
      val name = port.name.map(_.value).filter(_.nonEmpty)
      val cell =
        for
          node <- byId.get(id) if node.attrs.isHtml("label")
          nm   <- name
          tbl  <- HtmlParser.parse(node.attrs.getOrElse("label", "")).collect { case HtmlLabel.Table(t) => t }
          box  <- HtmlTableLayout.cellPortBox(tbl, nm, 14.0, "Times")
        yield box
      cell.flatMap { box =>
        val nc  = centerOf(id)
        val oc  = centerOf(other)
        val bcx = (box.llx + box.urx) / 2.0; val bcy = (box.lly + box.ury) / 2.0
        val hp  = math.Pi / 2.0
        // compass → (cell point (node-local), outward tangent)
        val compassPt = port.compass.flatMap {
          case N  => Some((XY(bcx, box.ury), hp))
          case S  => Some((XY(bcx, box.lly), -hp))
          case E  => Some((XY(box.urx, bcy), 0.0))
          case W  => Some((XY(box.llx, bcy), math.Pi))
          case NE => Some((XY(box.urx, box.ury), hp / 2))
          case NW => Some((XY(box.llx, box.ury), 3 * hp / 2))
          case SE => Some((XY(box.urx, box.lly), -hp / 2))
          case SW => Some((XY(box.llx, box.lly), -3 * hp / 2))
          case C | Underscore => None // centre / dyna
        }
        compassPt match
          case Some((pl, theta)) =>
            // beginpath/endpath nudge: 1 unit outward along the tangent.
            val aim = XY(nc.x + pl.x + math.cos(theta), nc.y + pl.y + math.sin(theta))
            // A TOP-side TAIL port exits away from the (lower) head ⇒ loop.
            val topLoop = isTail && port.compass.contains(N)
            val portY   = nc.y + box.ury
            val goLeft  = (nc.x + bcx) < nc.x
            Some(End(maximalBbox(id, Set(id)), aim, theta, constrained = true, clip = false,
                     top = topLoop, portY = portY, goLeft = goLeft))
          case None =>
            // dyna: closest side midpoint to `other`; ±1 nudge (TB scope only).
            val sides = Seq(
              (0, XY(bcx, box.lly), -hp),      // bottom
              (1, XY(box.urx, bcy), 0.0),      // right
              (2, XY(bcx, box.ury), hp),       // top
              (3, XY(box.llx, bcy), math.Pi)   // left
            )
            val (side, pl, theta) = sides.minBy { case (_, mp, _) =>
              val ax = nc.x + mp.x - oc.x; val ay = nc.y + mp.y - oc.y; ax * ax + ay * ay
            }
            val aim = XY(nc.x + pl.x, nc.y + pl.y)
            if isTail && side == 0 then
              Some(End(maximalBbox(id, Set(id)), XY(aim.x, aim.y - 1.0), theta, constrained = true, clip = false))
            else if !isTail && side == 2 then
              Some(End(maximalBbox(id, Set(id)), XY(aim.x, aim.y + 1.0), theta, constrained = true, clip = false))
            else None
      }

    // dot_splines_ routes edges in `edgecmp` order (dotsplines.c:551), NOT
    // declaration order: edge type DESCENDING (self 8 > flat 2 > regular 1),
    // then |Δrank| of the ORIGINAL endpoints (shortest spans first), then
    // |Δx| of the solved coords (straightest first), then AGSEQ. The order is
    // load-bearing: recover_slack moves vnodes as each edge routes, so later
    // routes see different neighbour geometry (fsm's 0.03pt drifts).
    // class2-merged multi-edges: only the REP routes (its chain is the only
    // one that exists); members install Multisep-offset copies at the rep's
    // position (dotsplines.c:1948).
    val dedgeOrigIdx: Vector[Int] =
      g.edges.zipWithIndex.collect { case (e, oi) if e.tail != e.head => oi }
    g.edges.zipWithIndex.filter { case (e, _) => e.tail != e.head }
      .zipWithIndex
      .filter { case (_, i) => res.mergedInto.lift(i).forall(_ == i) }
      .map { case ((e, origIdx), i) => (e, origIdx, i) }
      .sortBy { (e, _, i) =>
        val rt = rankOf.getOrElse(e.tail, 0); val rh = rankOf.getOrElse(e.head, 0)
        val typeKey = if rt == rh then -2 else -1 // descending type via negation
        val dx = math.abs(allX.get(e.tail).fold(0.0)(_.value) - allX.get(e.head).fold(0.0)(_.value))
        (typeKey, math.abs(rt - rh), dx, i)
      }
      .foreach { case (e, origIdx, i) =>
      val rt = rankOf(e.tail)
      val rh = rankOf(e.head)
      if rt != rh then
        val lo = math.min(rt, rh)
        val hi = math.max(rt, rh)
        // virtual chain ids for this declared edge (Order's Virtual scheme)
        val midRanks = (lo + 1 until hi).toVector
        val mids     = midRanks.map(r => LayoutNode.Virtual(i, r).name)
        if mids.forall(m => rankOf.contains(m) && allX.contains(m)) then
          // path nodes top→bottom (rank lo .. hi)
          val low  = if rt < rh then e.tail else e.head
          val high = if rt < rh then e.head else e.tail
          val pathTopDown: Vector[String] = (low +: mids) :+ high
          val ownPath = pathTopDown.toSet
          val tn0   = pathTopDown.head
          val hn0   = pathTopDown.last

          // Working-orientation ports (make_regular_edge MAKEFWDEDGE /
          // fwdedgea, dotsplines.c:1789: a BWDEDGE's working tail takes the
          // original HEAD's port).
          val (wtPort, whPort) =
            if rt < rh then (e.tailPort, e.headPort) else (e.headPort, e.tailPort)
          // HTML cell ports route through the SAME unified gv channel as
          // records (poly_port html branch → compassPort → endPort); the
          // legacy adjacent-rank port channel is retired.
          val tEnd: Option[End] = None
          val hEnd: Option[End] = None
          val portRoute = mids.isEmpty && (tEnd.isDefined || hEnd.isDefined)

          if portRoute then
            // ── port box channel: [tail band(s)] [rank gap] [head band] ──────
            // An against-grain TOP-side tail port expands into 3 boxes that loop
            // up out of the node and down its side.
            val tBoxes =
              if tEnd.exists(_.top) then topBoxes(tn0, tEnd.get.portY, tEnd.get.goLeft, ownPath)
              else Vector(tEnd.map(_.box).getOrElse(maximalBbox(tn0, ownPath)))
            val hBox  = hEnd.map(_.box).getOrElse(maximalBbox(hn0, ownPath))
            val boxes = mutable.ArrayBuffer.from(tBoxes)
            boxes += rankBox(rankOf(tn0), rankOf(hn0))
            boxes += hBox
            val start = tEnd.map(_.p).getOrElse(XY(cx(tn0), cy(tn0) - 1.0))
            val end   = hEnd.map(_.p).getOrElse(XY(cx(hn0), cy(hn0) + 1.0))
            val ev0   =
              if tEnd.exists(_.constrained) then
                val th = tEnd.get.theta; XY(math.cos(th), math.sin(th))
              else XY(0, 0)
            val ev1 =
              if hEnd.exists(_.constrained) then
                val th = hEnd.get.theta; XY(-math.cos(th), -math.sin(th))
              else XY(0, 0)
            adjustRegularPath(boxes)
            val st = Array(start.x, start.y); val en = Array(end.x, end.y)
            val ctrl0 =
              if checkpath(boxes, st, en) then Vector(XY(st(0), st(1)), XY(en(0), en(1)))
              else
                // checkpath's overlap repair can collapse a box to zero area
                // (e.g. the maximal_bbox added for a TOP-side port fully overlaps
                // the side box) — drop those so buildPolygon doesn't pinch the
                // channel and wrongly reject the spline.
                boxes.filterInPlace(b => math.abs(b.ury - b.lly) > 0.01 && math.abs(b.urx - b.llx) > 0.01)
                val poly = buildPolygon(boxes)
                val sp   = funnel(boxes, XY(st(0), st(1)), XY(en(0), en(1)))
                proutespline(poly, sp, ev0, ev1)
            out(origIdx) = clipInstall(
              g, ctrl0, e, byId, centerOf,
              tailClip = tEnd.forall(_.clip), headClip = hEnd.forall(_.clip),
              reversedWork = rt > rh
            )
          else
            // ── unified make_regular_edge channel (dotsplines.c:1820) ─────
            // Ported or portless, adjacent or chained. beginpath resolves
            // the working tail's dyna port vs the segment's OTHER end — the
            // FIRST VNODE for chains (fwdedgea's head, dotsplines.c:1803);
            // endpath resolves the head's vs the working TAIL real node
            // (fwdedgeb keeps the real endpoints, dotsplines.c:1900). HTML
            // cell ports resolve exactly like record ports (poly_port html
            // branch → compassPort inside endPort).
            val tOther = mids.headOption.map(m => XY(cx(m), cy(m))).getOrElse(XY(cx(hn0), cy(hn0)))
            val (tGp, tOrigClip) = endPort(tn0, wtPort, tOther)
            val (hGp, hOrigClip) = endPort(hn0, whPort, XY(cx(tn0), cy(tn0)))

            // gv's maximal_bbox (ie, oe) threading: tail (NULL, first seg),
            // chain vnode (in seg, out seg), head (last seg, NULL).
            def segAt(i: Int): (String, String) = (pathTopDown(i), pathTopDown(i + 1))
            val nbT = maximalBboxGv(tn0, None, Some(segAt(0)))
            val (start, thT, tB0, tCleared) = beginPathR(tn0, tGp, nbT)
            // makeregularend BOTTOM: nb's x-range from the rank bottom up to
            // the last tail box (degenerate — dropped — in the portless case
            // where maximal_bbox already reaches the rank bottom).
            val fillT  = Box(nbT.llx, cy(tn0) - ht1(lo), nbT.urx, tB0.last.lly)
            val tBoxes = if fillT.llx < fillT.urx && fillT.lly < fillT.ury then tB0 :+ fillT else tB0

            // ── chain driver with smode straight-splits (dotsplines.c:1836) ─
            // straight_len ≥ (EDGE_LABEL ? 4+1 : 2+1) x-aligned vnodes splits
            // the route: route to a vnode two past detection (endpath default
            // + θ=π/2 constrained), emit the straight stretch as DUPLICATED
            // last points (straight_path), resume from the stretch end
            // (beginpath default + θ=−π/2 constrained). Each segment routes,
            // limit-boxes and recover_slacks separately; the concatenated
            // point list installs once.
            val threshold = if Rank.hasEdgeLabel(g) then 4 + 1 else 2 + 1
            def straightLen(v0: String): Int =
              var cnt = 0; var v = v0; var go = true
              while go do
                val w = chainNextOf(v)
                if !isV(w) || outSize(w) != 1 || inSize(w) != 1 || cx(w) != cx(v0) then go = false
                else { cnt += 1; v = w }
              cnt

            val pointfs  = mutable.ArrayBuffer.empty[XY]
            var curTend  = tBoxes
            var curStart = start
            var curEv0   = thT.map(t => XY(math.cos(t), math.sin(t))).getOrElse(XY(0, 0))
            var segStart = 0 // pathTopDown idx of the current segment-run tail
            val boxesBuf = mutable.ArrayBuffer.empty[Box]
            var tnI = 0; var hnI = 1
            var smode = false; var si = -1; var sl = 0

            def routeSeg(hend: Vector[Box], endPt: XY, ev1: XY): Unit =
              val boxes = mutable.ArrayBuffer.empty[Box]
              def addBox(b: Box): Unit = if b.llx < b.urx && b.lly < b.ury then boxes += b
              curTend.foreach(addBox)
              val fb = boxes.length + 1
              val lb = fb + boxesBuf.length - 3
              boxesBuf.foreach(addBox)
              hend.reverse.foreach(addBox)
              adjustRegularPath(boxes, fb, lb)
              val st = Array(curStart.x, curStart.y)
              val en = Array(endPt.x, endPt.y)
              val seg =
                if checkpath(boxes, st, en) then Vector(XY(st(0), st(1)), XY(en(0), en(1)))
                else
                  val sp = funnel(boxes, XY(st(0), st(1)), XY(en(0), en(1)))
                  if polylineMode then makePolyline(sp)
                  else proutespline(buildPolygon(boxes), sp, curEv0, ev1)
              pointfs ++= seg
              limitBoxes(boxes, seg)
              recoverSlack(pathTopDown.slice(segStart + 1, pathTopDown.length - 1).toVector, boxes, origIdx)

            while hnI < pathTopDown.length - 1 do // hn is a chain vnode
              val tn = pathTopDown(tnI); val hn = pathTopDown(hnI)
              boxesBuf += rankBox(rankOf(tn), rankOf(hn))
              if !smode && { sl = straightLen(hn); sl >= threshold } then
                smode = true; si = 1; sl -= 2
              if !smode || si > 0 then
                si -= 1
                boxesBuf += maximalBboxGv(hn, Some(segAt(hnI - 1)), Some(segAt(hnI)))
                tnI = hnI; hnI += 1
              else
                // split at hn: endpath default (portless vnode) + TOP fill
                val nbS   = maximalBboxGv(hn, Some(segAt(hnI - 1)), Some(segAt(hnI)))
                val hb0   = Box(nbS.llx, cy(hn), nbS.urx, nbS.ury) // LL.y = end.y
                val fillS = Box(nbS.llx, hb0.ury, nbS.urx, cy(hn) + ht2(rankOf(hn)))
                val hend  = if fillS.llx < fillS.urx && fillS.lly < fillS.ury then Vector(hb0, fillS) else Vector(hb0)
                routeSeg(hend, XY(cx(hn), cy(hn) + 1.0), XY(0, -1)) // P.end θ=π/2 constrained
                // straight_path: skip sl segments; duplicate the last point ×2
                val lastPt = pointfs.last
                pointfs += lastPt; pointfs += lastPt
                tnI = hnI + sl; hnI = tnI + 1; segStart = tnI
                boxesBuf.clear()
                val tnN  = pathTopDown(tnI)
                val nbT2 = maximalBboxGv(tnN, Some(segAt(tnI - 1)), Some(segAt(tnI)))
                curTend  = Vector(Box(nbT2.llx, nbT2.lly, nbT2.urx, cy(tnN))) // UR.y = start.y
                curStart = XY(cx(tnN), cy(tnN) - 1.0)
                curEv0   = XY(0, -1) // P.start θ=−π/2 constrained
                smode = false

            // final segment into the real head
            boxesBuf += rankBox(rankOf(pathTopDown(tnI)), rankOf(hn0))
            val nbH = maximalBboxGv(hn0, Some(segAt(pathTopDown.length - 2)), None)
            val (end, thH, hB0, hCleared) = endPathR(hn0, hGp, nbH)
            val fillH  = Box(nbH.llx, hB0.last.ury, nbH.urx, cy(hn0) + ht2(hi)) // makeregularend TOP
            val hBoxes = if fillH.llx < fillH.urx && fillH.lly < fillH.ury then hB0 :+ fillH else hB0
            routeSeg(hBoxes, end, thH.map(t => XY(-math.cos(t), -math.sin(t))).getOrElse(XY(0, 0)))
            val routed = pointfs.toVector
            // Clip flags come from the ORIG port at install (clip_and_install
            // walks ED_to_orig): initial-resolution clip, cleared only by a
            // begin/endpath side branch — a route-time dyna side port's
            // clip=false lives ONLY on the working copy, so a centre-resolved
            // record port still clips, against its FIELD box (port.bp).
            // gv clips in the WORKING (top-down) parameterization and only
            // swap_spline's at install — clipInstall(reversedWork) does both.
            def install(oIx: Int, me: org.jpablo.graphexplorer.graphviz.model.REdge,
                        pts: Vector[XY]): Unit =
              out(oIx) = clipInstall(g, pts, me, byId, centerOf,
                tailClip = tOrigClip && !tCleared, headClip = hOrigClip && !hCleared,
                reversedWork = rt > rh,
                tailBp = if tGp.defined then tGp.bp else None,
                headBp = if hGp.defined then hGp.bp else None)
            val members = res.mergedInto.indices.filter(res.mergedInto(_) == i).toVector
            if members.length <= 1 then install(origIdx, e, routed)
            else
              // multi-edge install (dotsplines.c:1948): interior points of
              // the shared route shift by −Multisep·(cnt−1)/2, then
              // +Multisep per member copy — members in declaration order
              // (edgecmp's AGSEQ tie), each clipped with its own attrs.
              val base =
                (if routed.length >= 4 then routed
                 else
                   val s = routed.head; val t = routed.last
                   Vector(s, XY(s.x + (t.x - s.x) / 3, s.y + (t.y - s.y) / 3),
                          XY(s.x + 2 * (t.x - s.x) / 3, s.y + 2 * (t.y - s.y) / 3), t)
                ).toArray
              val dxm = NodeSep * (members.length - 1) / 2.0
              var k = 1
              while k < base.length - 1 do { base(k) = XY(base(k).x - dxm, base(k).y); k += 1 }
              members.zipWithIndex.foreach { (dj, mi) =>
                if mi > 0 then
                  var q = 1
                  while q < base.length - 1 do { base(q) = XY(base(q).x + NodeSep, base(q).y); q += 1 }
                install(dedgeOrigIdx(dj), g.edges(dedgeOrigIdx(dj)), base.toVector)
              }
      else
        // ── flat edge (rt == rh): same-rank edge (rank=same / minlen=0) ─────
        // dotsplines.c: `makeSimpleFlat` (unlabeled) routes a 4-point Bezier
        // [tp, (2tp+hp)/3, (2hp+tp)/3, hp] at y=tp.y; `makeSimpleFlatLabels`
        // (labeled) routes the degenerate line [tp,tp,hp,hp] and places the
        // label above the edge at (ctrx, tp.y+(dimen.y+LBL_SPACE)/2). Both
        // then clip_and_install (clip to node boundaries + arrow). Scoped to
        // the adjacent, portless case; non-adjacent/ported flat edges deferred.
        val row      = orderByRank.getOrElse(rt, Vector.empty)
        val adjacent = math.abs(row.indexOf(e.tail) - row.indexOf(e.head)) == 1
        val ports    = e.tailPort.isDefined || e.headPort.isDefined
        val labelled = e.attrs.get("label").exists(_.nonEmpty)
        if adjacent && !ports then
          // make_flat_edge forward-normalizes by within-rank ORDER (the
          // swap_ends_p tie-break for equal ranks): the WORKING direction is
          // left→right; the clip runs in it and swap_spline restores the
          // declared direction at install. bezier_clip is not direction-
          // symmetric, so clipping tail→head put the two cuts on the wrong
          // ends (logo's b->h was off ±0.14pt, mirrored).
          val flatRev = row.indexOf(e.head) < row.indexOf(e.tail)
          val (wt, wh) = if flatRev then (e.head, e.tail) else (e.tail, e.head)
          val tp   = XY(cx(wt), cy(wt))
          val hp   = XY(cx(wh), cy(wh))
          val ctrl =
            if labelled then Vector(tp, tp, hp, hp)
            else Vector(tp, XY((2 * tp.x + hp.x) / 3.0, tp.y),
                            XY((2 * hp.x + tp.x) / 3.0, tp.y), hp)
          out(origIdx) = clipInstall(g, ctrl, e, byId, centerOf, reversedWork = flatRev)
          if labelled then
            // place_flat_label: centred between the facing node edges
            // (leftend = left.x+rw, rightend = right.x−lw), one
            // (dimen.y + LBL_SPACE)/2 above the rank line. LBL_SPACE = 6.
            val (_, dh)  = Coord.edgeLabelDim(e, g)
            val (ln, rn) = if cx(e.tail) <= cx(e.head) then (e.tail, e.head) else (e.head, e.tail)
            val ctrx     = (cx(ln) + rw(ln) + cx(rn) - lw(rn)) / 2.0
            labelPos(origIdx) = XY(ctrx, tp.y + (dh + 6.0) / 2.0)
        else if !ports && !labelled then
          // ── non-adjacent flat edge (make_flat_edge): an up-and-over arch ──
          // Skips ≥1 node in the rank, so it can't run straight — gv arches it
          // over the intervening nodes. Normalise the edge left→right, build
          // the tail/head flat-end boxes + a 3-box channel, then route the
          // geodesic through it. Single-edge scope (cnt = 1); parallel
          // non-adjacent flats would share one widened channel (no corpus).
          val (tn, hn) = if cx(e.tail) <= cx(e.head) then (e.tail, e.head) else (e.head, e.tail)
          val tmb = maximalBbox(tn, Set(tn))
          val hmb = maximalBbox(hn, Set(hn))
          // makeFlatEnd: the flat-end box is `maximal_bbox` with LL.y pulled up
          // to node.y (its `makeregularend` extension to node.y+ht2 comes out
          // degenerate and is dropped), so a single box per end. UR.y already
          // equals node.y+ht2 from maximal_bbox.
          val tend = Box(tmb.llx, cy(tn), tmb.urx, tmb.ury)
          val hend = Box(hmb.llx, cy(hn), hmb.urx, hmb.ury)
          val stepx   = NodeSep / 2.0 // sp->Multisep / (cnt + 1), cnt = 1
          val topRank = ranksSorted.head
          val vspace  =
            if rt == topRank then Coord.flatVspaceTopRank(g)
            else (yOf(rt - 1).value - ht1(rt - 1)) - (yOf(rt).value + ht2(rt))
          val stepy = vspace / 2.0
          // channel (make_flat_edge): step up by stepy, widen by stepx.
          val cb0   = Box(tend.llx, tend.ury, tend.urx + stepx, tend.ury + stepy)
          val cb1   = Box(tend.llx, cb0.ury, hend.urx, cb0.ury + stepy)
          val cb2   = Box(hend.llx - stepx, hend.ury, hend.urx, cb1.lly)
          val boxes = mutable.ArrayBuffer(tend, cb0, cb1, cb2, hend)
          val st    = Array(cx(tn), cy(tn))
          val en    = Array(cx(hn), cy(hn))
          val ctrl  =
            if checkpath(boxes, st, en) then Vector(XY(st(0), st(1)), XY(en(0), en(1)))
            else
              val poly = buildPolygon(boxes)
              val sp   = funnelGeneral(boxes, XY(st(0), st(1)), XY(en(0), en(1)))
              proutespline(poly, sp)
          // gv routes AND clips tn→hn (left→right, the working direction) and
          // swap_spline's at install — clipInstall(reversedWork) does both.
          out(origIdx) = clipInstall(g, ctrl, e, byId, centerOf, reversedWork = e.tail != tn)
        // else: ported / labeled non-adjacent flat edges stay deferred (no corpus).
    }

    // ── self-edges (makeSelfEdge → selfRight, no-port case) ───────────────
    // Self-loops don't rank (excluded above & in acyclic); route them here
    // keyed by their g.edges index. Ports-on-self-edges (selfTop/Left/
    // Bottom) are a documented deferral (no corpus). `sizey` mirrors the
    // rank-position rule at the dotsplines.c call site; selfRight bows the
    // loop right of the node by `rw + (i+1)·nodesep`.
    val minRank = if ranksSorted.isEmpty then 0 else ranksSorted.head
    val maxRank = if ranksSorted.isEmpty then 0 else ranksSorted.last
    g.edges.zipWithIndex
      .filter { case (e, _) => e.tail == e.head && rankOf.contains(e.tail) }
      .groupBy(_._1.tail)
      .foreach { case (nid, group) =>
        val r    = rankOf(nid)
        val ndHt = 2.0 * halfH(nid)
        val sizeyCs =
          if r == maxRank then
            if r > minRank then yOf(r - 1).value - yOf(r).value else ndHt
          else if r == minRank then yOf(r).value - yOf(r + 1).value
          else math.min(yOf(r - 1).value - yOf(r).value, yOf(r).value - yOf(r + 1).value)
        val cnt   = group.length
        val stepx = NodeSep                                  // sd.Multisep
        val stepy = math.max(sizeyCs / 2.0 / 2.0 / cnt, 2.0) // selfRight
        val np    = XY(cx(nid), cy(nid))
        val rw    = lw(nid)
        // selfRight accumulates dx/tx/hx/dy ACROSS the node's loops, and a
        // LABELLED loop bumps dx by (labelWidth − stepx) so the next loop
        // clears the label. Label pos = (n.x + dx + width/2, n.y) with the
        // flip-aware width (dimen.y under LR/RL) — fsm's S(a)/S(b) lps.
        var dx = rw; var tx = rw; var hx = rw; var dy = 0.0
        group.foreach { case (e, origIdx) =>
          dx += stepx; tx += stepx; hx += stepx
          dy += stepy // sgn = +1 (tp.y == hp.y, no-port ⇒ no flip)
          val pts = Vector(
            np,
            XY(np.x + tx / 3.0, np.y + dy),
            XY(np.x + dx, np.y + dy),
            XY(np.x + dx, np.y),
            XY(np.x + dx, np.y - dy),
            XY(np.x + hx / 3.0, np.y - dy),
            np
          )
          e.attrs.get("label").filter(_.nonEmpty).foreach { _ =>
            val (wl, hl) = Coord.edgeLabelDim(e, g)
            val width    = if Rank.flip(g) then hl else wl
            labelPos(origIdx) = XY(np.x + dx + width / 2.0, np.y)
            if width > stepx then dx += width - stepx
          }
          out(origIdx) = clipInstall(g, pts, e, byId, centerOf)
        }
      }
    // any merged-away member not installed by the regular Multisep loop
    // (flat/HTML classes — no corpus) inherits its rep's spline verbatim.
    res.mergedInto.indices.foreach { d =>
      val r = res.mergedInto(d)
      if r != d then
        val mo = dedgeOrigIdx(d)
        if !out.contains(mo) then out.get(dedgeOrigIdx(r)).foreach(sp => out(mo) = sp)
    }
    // place_vnlabel post-pass (dotsplines.c:437): AFTER all edges route,
    // every regular-edge label vnode gets lp = (vn.x + width/2, vn.y) from
    // its FINAL (possibly recover_slack-snapped) coordinate — including
    // label vnodes inside straight (smode) sections that no per-segment
    // snap visited (psg's 8-rank state0→state2). Snapped entries already
    // hold exactly this value, so only missing ones are filled.
    // gv iterates GD_nlist, so only vnodes that actually EXIST in the
    // placed graph are visited — labelW is a per-edge table and can name
    // vnodes that were never created (merged/degenerate chains); an
    // unguarded cx() lookup throws (viewer: "key not found: __v5_5").
    labelW.foreach { (vn, w) =>
      LayoutNode.fromName(vn) match
        case LayoutNode.Virtual(d, _) if allX.contains(vn) =>
          val orig = dedgeOrigIdx(d)
          if !labelPos.contains(orig) then labelPos(orig) = XY(cx(vn) + w / 2.0, cy(vn))
        case _ => ()
    }
    (out.toMap, labelPos.toMap)

  // ── adjustregularpath: widen path boxes to ≥ MINW ─────────────────────────
  // Legacy simplified overlap-guarantee (the HTML port-route branch's
  // corpus-verified form — equivalent to the exact one on its box lists).
  private def adjustRegularPath(boxes: mutable.ArrayBuffer[Box]): Unit =
    var i = 0
    while i + 1 < boxes.length do
      val a = boxes(i); val b = boxes(i + 1)
      if a.urx - MINW < b.llx then b.llx = a.urx - MINW
      if a.llx + MINW > b.urx then b.urx = a.llx + MINW
      i += 1

  /** adjustregularpath (dotsplines.c:2040), exact: `fb`/`lb` bound the
    * interrank slice — boxes at EVEN offsets from `fb` (the vnode boxes) are
    * grown only when degenerate, odd offsets (rank boxes, including the
    * size_t-underflowing `fb-1` first one) are stretched to MINW; then the
    * pairwise ≥MINW-overlap pass adjusts the box gv's parity rules pick. */
  private def adjustRegularPath(boxes: mutable.ArrayBuffer[Box], fb: Int, lb: Int): Unit =
    var i = fb - 1
    while i < lb + 1 do
      if i >= 0 && i < boxes.length then
        val bp1 = boxes(i)
        // C `(i - fb) % 2` on size_t: i = fb-1 underflows to an ODD value.
        if i >= fb && (i - fb) % 2 == 0 then
          if bp1.llx >= bp1.urx then
            val x = (bp1.llx + bp1.urx) / 2
            bp1.llx = x - HALFMINW; bp1.urx = x + HALFMINW
        else
          if bp1.llx + MINW > bp1.urx then
            val x = (bp1.llx + bp1.urx) / 2
            bp1.llx = x - HALFMINW; bp1.urx = x + HALFMINW
      i += 1
    i = 0
    while i + 1 < boxes.length do
      val bp1 = boxes(i); val bp2 = boxes(i + 1)
      if i >= fb && i <= lb && (i - fb) % 2 == 0 then
        if bp1.llx + MINW > bp2.urx then bp2.urx = bp1.llx + MINW
        if bp1.urx - MINW < bp2.llx then bp2.llx = bp1.urx - MINW
      else if i + 1 >= fb && i < lb && (i + 1 - fb) % 2 == 0 then
        if bp1.llx + MINW > bp2.urx then bp1.llx = bp2.urx - MINW
        if bp1.urx - MINW < bp2.llx then bp1.urx = bp2.llx + MINW
      i += 1

  // ── checkpath: drop degenerate boxes, repair joins/overlaps, clamp ends ───
  private def checkpath(boxes0: mutable.ArrayBuffer[Box], st: Array[Double], en: Array[Double]): Boolean =
    val boxes = boxes0.filterNot(bx => math.abs(bx.lly - bx.ury) < 0.01 || math.abs(bx.llx - bx.urx) < 0.01)
    boxes0.clear(); boxes0 ++= boxes
    val n = boxes0.length
    if n == 0 then return true
    if boxes0(0).llx > boxes0(0).urx || boxes0(0).lly > boxes0(0).ury then return true
    var bi = 0
    while bi + 1 < n do
      val ba = boxes0(bi); val bb = boxes0(bi + 1)
      if bb.llx > bb.urx || bb.lly > bb.ury then return true
      var l = if ba.urx < bb.llx then 1 else 0
      var r = if ba.llx > bb.urx then 1 else 0
      var d = if ba.ury < bb.lly then 1 else 0
      var u = if ba.lly > bb.ury then 1 else 0
      val errs = l + r + d + u
      if errs > 0 then
        if l == 1 then { val xy = ba.urx; ba.urx = bb.llx; bb.llx = xy; l = 0 }
        else if r == 1 then { val xy = ba.llx; ba.llx = bb.urx; bb.urx = xy; r = 0 }
        else if d == 1 then { val xy = ba.ury; ba.ury = bb.lly; bb.lly = xy; d = 0 }
        else if u == 1 then { val xy = ba.lly; ba.lly = bb.ury; bb.ury = xy; u = 0 }
        var j = 0
        while j < errs - 1 do
          if l == 1 then { val xy = (ba.urx + bb.llx) / 2.0 + 0.5; ba.urx = xy; bb.llx = xy; l = 0 }
          else if r == 1 then { val xy = (ba.llx + bb.urx) / 2.0 + 0.5; ba.llx = xy; bb.urx = xy; r = 0 }
          else if d == 1 then { val xy = (ba.ury + bb.lly) / 2.0 + 0.5; ba.ury = xy; bb.lly = xy; d = 0 }
          else if u == 1 then { val xy = (ba.lly + bb.ury) / 2.0 + 0.5; ba.lly = xy; bb.ury = xy; u = 0 }
          j += 1
      val xo = overlap(ba.llx, ba.urx, bb.llx, bb.urx)
      val yo = overlap(ba.lly, ba.ury, bb.lly, bb.ury)
      if xo > 0 && yo > 0 then
        if xo < yo then
          if ba.urx - ba.llx > bb.urx - bb.llx then
            if ba.urx < bb.urx then ba.urx = bb.llx else ba.llx = bb.urx
          else if ba.urx < bb.urx then bb.llx = ba.urx
          else bb.urx = ba.llx
        else if ba.ury - ba.lly > bb.ury - bb.lly then
          if ba.ury < bb.ury then ba.ury = bb.lly else ba.lly = bb.ury
        else if ba.ury < bb.ury then bb.lly = ba.ury
        else bb.ury = ba.lly
      bi += 1
    val b0 = boxes0(0)
    if st(0) < b0.llx || st(0) > b0.urx || st(1) < b0.lly || st(1) > b0.ury then
      st(0) = math.min(math.max(st(0), b0.llx), b0.urx)
      st(1) = math.min(math.max(st(1), b0.lly), b0.ury)
    val bn = boxes0(n - 1)
    if en(0) < bn.llx || en(0) > bn.urx || en(1) < bn.lly || en(1) > bn.ury then
      en(0) = math.min(math.max(en(0), bn.llx), bn.urx)
      en(1) = math.min(math.max(en(1), bn.lly), bn.ury)
    false

  private def overlap(i0: Double, i1: Double, j0: Double, j1: Double): Double =
    if i1 <= j0 || i0 >= j1 then 0.0
    else if i0 <= j0 && i1 >= j1 then i1 - i0
    else if j0 <= i0 && j1 >= i1 then j1 - j0
    else if j0 <= i0 && i0 <= j1 then j1 - i0
    else i1 - j0

  /** Channel outline polygon — `routesplines_` box→polygon walk (down-only). */
  private def buildPolygon(boxes: mutable.ArrayBuffer[Box]): Array[XY] =
    val n   = boxes.length
    val pts = mutable.ArrayBuffer.empty[XY]
    def prevNext(bi: Int): (Int, Int) =
      val prev = if bi > 0 then (if boxes(bi).lly > boxes(bi - 1).lly then -1 else 1) else 0
      val next = if bi + 1 < n then (if boxes(bi + 1).lly > boxes(bi).lly then 1 else -1) else 0
      (prev, next)
    var bi = 0
    while bi < n do
      val (prev, next) = prevNext(bi)
      if prev != next then
        if next == -1 || prev == 1 then
          pts += XY(boxes(bi).llx, boxes(bi).ury); pts += XY(boxes(bi).llx, boxes(bi).lly)
        else
          pts += XY(boxes(bi).urx, boxes(bi).lly); pts += XY(boxes(bi).urx, boxes(bi).ury)
      else if prev == 0 then
        pts += XY(boxes(bi).llx, boxes(bi).ury); pts += XY(boxes(bi).llx, boxes(bi).lly)
      bi += 1
    bi = n - 1
    while bi >= 0 do
      val prev = if bi + 1 < n then (if boxes(bi).lly > boxes(bi + 1).lly then -1 else 1) else 0
      val next = if bi > 0 then (if boxes(bi - 1).lly > boxes(bi).lly then 1 else -1) else 0
      if prev != next then
        if next == -1 || prev == 1 then
          pts += XY(boxes(bi).llx, boxes(bi).ury); pts += XY(boxes(bi).llx, boxes(bi).lly)
        else
          pts += XY(boxes(bi).urx, boxes(bi).lly); pts += XY(boxes(bi).urx, boxes(bi).ury)
      else if prev == 0 then
        pts += XY(boxes(bi).urx, boxes(bi).lly); pts += XY(boxes(bi).urx, boxes(bi).ury)
      else
        pts += XY(boxes(bi).urx, boxes(bi).lly); pts += XY(boxes(bi).urx, boxes(bi).ury)
        pts += XY(boxes(bi).llx, boxes(bi).ury); pts += XY(boxes(bi).llx, boxes(bi).lly)
      bi -= 1
    pts.toArray

  /** Taut-string geodesic through the box portals (== `Pshortestpath` for a
    * y-monotone box channel). Canonical "simple stupid funnel" (Mononen).
    * Facing the descending path, east (+x) is the left chain, west the right.
    */
  private def funnel(boxes: mutable.ArrayBuffer[Box], s: XY, e: XY): Vector[XY] =
    val gates = mutable.ArrayBuffer.empty[(XY, XY)]
    gates += ((s, s))
    var k = 0
    while k + 1 < boxes.length do
      val a = boxes(k); val b = boxes(k + 1)
      val y  = a.lly
      val xl = math.max(a.llx, b.llx)
      val xr = math.min(a.urx, b.urx)
      gates += ((XY(xl, y), XY(xr, y))) // left = smaller x, right = larger x
      k += 1
    gates += ((e, e))
    funnelCore(gates.toArray, e)

  /** Same taut-string geodesic, but for a corridor that is NOT y-monotone
    * (`make_flat_edge`'s up-and-over arch). gv routes both shapes through the
    * one general `Pshortestpath`; the monotone [[funnel]] can't, because it
    * pins each gate to `y = a.lly` and to a fixed "west = left" winding, which
    * both break once the path turns back down. Here each gate is the actual
    * shared edge between consecutive boxes, and its two ends are wound
    * consistently by the local travel direction (`rot90cw`), so the two
    * boundary chains stay coherent through the U-turn. For a strictly
    * descending channel this reduces to [[funnel]]'s gates bit-for-bit (a
    * horizontal edge at `y = a.lly`, `_1 = smaller x`), so regular edges are
    * untouched — but the flat branch below is the only caller. */
  private def funnelGeneral(boxes: mutable.ArrayBuffer[Box], s: XY, e: XY): Vector[XY] =
    val gates = mutable.ArrayBuffer.empty[(XY, XY)]
    gates += ((s, s))
    var k = 0
    while k + 1 < boxes.length do
      val a = boxes(k); val b = boxes(k + 1)
      // shared edge = the touching side (its span in the thin dimension is 0).
      val xlo = math.max(a.llx, b.llx); val xhi = math.min(a.urx, b.urx)
      val ylo = math.max(a.lly, b.lly); val yhi = math.min(a.ury, b.ury)
      val (p, q) =
        if yhi - ylo <= xhi - xlo then
          val y = (ylo + yhi) / 2.0; (XY(xlo, y), XY(xhi, y))
        else
          val x = (xlo + xhi) / 2.0; (XY(x, ylo), XY(x, yhi))
      // wind by travel direction d = centroid(b) − centroid(a): the endpoint
      // maximising dot(rot90cw(d), ·) is `_1` on every gate, so `_1` follows
      // one continuous wall and `_2` the other even as the corridor bends.
      val dx = (b.llx + b.urx - a.llx - a.urx) / 2.0
      val dy = (b.lly + b.ury - a.lly - a.ury) / 2.0
      val sp = dy * p.x - dx * p.y
      val sq = dy * q.x - dx * q.y
      gates += (if sp >= sq then (p, q) else (q, p))
      k += 1
    gates += ((e, e))
    funnelCore(gates.toArray, e)

  /** The Mononen funnel proper: walk the ordered `(left, right)` portals and
    * emit the taut path. Shared by [[funnel]] and [[funnelGeneral]]. */
  private def funnelCore(portals: Array[(XY, XY)], e: XY): Vector[XY] =
    def triArea2(a: XY, b: XY, c: XY): Double =
      (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)
    def eq(a: XY, b: XY): Boolean = math.abs(a.x - b.x) < 1e-9 && math.abs(a.y - b.y) < 1e-9
    val pts = mutable.ArrayBuffer[XY](portals(0)._1)
    var apex = portals(0)._1
    var pL = portals(0)._1
    var pR = portals(0)._2
    var apexI = 0; var lI = 0; var rI = 0
    var i = 1
    while i < portals.length do
      val L = portals(i)._1
      val R = portals(i)._2
      var restart = false
      // update right vertex
      if triArea2(apex, pR, R) <= 0.0 then
        if eq(apex, pR) || triArea2(apex, pL, R) > 0.0 then
          pR = R; rI = i
        else
          pts += pL
          apex = pL; apexI = lI
          pL = apex; pR = apex; lI = apexI; rI = apexI
          i = apexI + 1; restart = true
      if !restart then
        // update left vertex
        if triArea2(apex, pL, L) >= 0.0 then
          if eq(apex, pL) || triArea2(apex, pR, L) < 0.0 then
            pL = L; lI = i
          else
            pts += pR
            apex = pR; apexI = rI
            pL = apex; pR = apex; lI = apexI; rI = apexI
            i = apexI + 1; restart = true
        if !restart then i += 1
    if pts.isEmpty || !eq(pts.last, e) then pts += e
    pts.toVector

  // ───────────────────────── Proutespline (route.c) ────────────────────────
  private def proutespline(poly: Array[XY], pl: Vector[XY]): Vector[XY] =
    proutespline(poly, pl, XY(0, 0), XY(0, 0))

  /** `Proutespline` with constrained endpoint slopes (`evs`). routespl.c
    * passes `evs[0]=(cosθs,sinθs)`, `evs[1]=(-cosθe,-sinθe)` for constrained
    * ports; `Proutespline` then `normv`-normalises them. `(0,0)` ⇒ the
    * unconstrained least-squares fit (the portless path). */
  private def proutespline(poly: Array[XY], pl: Vector[XY], ev0: XY, ev1: XY): Vector[XY] =
    val edges = Array.tabulate(poly.length)(i => (poly(i), poly((i + 1) % poly.length)))
    val inps  = pl.toArray
    val ops   = mutable.ArrayBuffer.empty[XY]
    ops += inps(0)
    reallyRoute(edges, inps, 0, inps.length, normv(ev0), normv(ev1), ops)
    ops.toVector

  private def vsub(a: XY, b: XY)  = XY(a.x - b.x, a.y - b.y)
  private def vadd(a: XY, b: XY)  = XY(a.x + b.x, a.y + b.y)
  private def vscale(a: XY, c: Double) = XY(a.x * c, a.y * c)
  private def vdot(a: XY, b: XY)  = a.x * b.x + a.y * b.y
  private def vdist(a: XY, b: XY) = math.hypot(b.x - a.x, b.y - a.y)
  private def normv(v: XY): XY =
    val d = v.x * v.x + v.y * v.y
    if d > 1e-6 then { val s = math.sqrt(d); XY(v.x / s, v.y / s) } else v
  private def b0(t: Double) = { val u = 1 - t; u * u * u }
  private def b1(t: Double) = { val u = 1 - t; 3 * t * u * u }
  private def b2(t: Double) = { val u = 1 - t; 3 * t * t * u }
  private def b3(t: Double) = t * t * t
  private def b01(t: Double) = { val u = 1 - t; u * u * (u + 3 * t) }
  private def b23(t: Double) = { val u = 1 - t; t * t * (3 * u + t) }

  private def reallyRoute(
      edges: Array[(XY, XY)], inps: Array[XY], off: Int, inpn: Int,
      ev0: XY, ev1: XY, ops: mutable.ArrayBuffer[XY]
  ): Unit =
    val t = new Array[Double](inpn)
    t(0) = 0.0
    var i = 1
    while i < inpn do { t(i) = t(i - 1) + vdist(inps(off + i), inps(off + i - 1)); i += 1 }
    i = 1
    val tot = t(inpn - 1)
    while i < inpn do { if tot != 0.0 then t(i) = t(i) / tot; i += 1 }
    val a0 = Array.tabulate(inpn)(k => vscale(ev0, b1(t(k))))
    val a1 = Array.tabulate(inpn)(k => vscale(ev1, b2(t(k))))
    val (p1, v1, p2, v2) = mkspline(inps, off, inpn, t, a0, a1, ev0, ev1)
    val fit = splinefits(edges, p1, v1, p2, v2, inps, off, inpn, ops)
    if fit > 0 then ()
    else
      val cp1 = vadd(p1, vscale(v1, 1.0 / 3.0))
      val cp2 = vsub(p2, vscale(v2, 1.0 / 3.0))
      var maxd = -1.0; var maxi = -1
      i = 1
      while i < inpn - 1 do
        val tt = t(i)
        val px = b0(tt) * p1.x + b1(tt) * cp1.x + b2(tt) * cp2.x + b3(tt) * p2.x
        val py = b0(tt) * p1.y + b1(tt) * cp1.y + b2(tt) * cp2.y + b3(tt) * p2.y
        val d = vdist(XY(px, py), inps(off + i))
        if d > maxd then { maxd = d; maxi = i }
        i += 1
      val sp = maxi
      val sv1 = normv(vsub(inps(off + sp), inps(off + sp - 1)))
      val sv2 = normv(vsub(inps(off + sp + 1), inps(off + sp)))
      val sv  = normv(vadd(sv1, sv2))
      reallyRoute(edges, inps, off, sp + 1, ev0, sv, ops)
      reallyRoute(edges, inps, off + sp, inpn - sp, sv, ev1, ops)

  private def mkspline(
      inps: Array[XY], off: Int, inpn: Int, t: Array[Double],
      a0: Array[XY], a1: Array[XY], ev0: XY, ev1: XY
  ): (XY, XY, XY, XY) =
    var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
    var i = 0
    while i < inpn do
      c00 += vdot(a0(i), a0(i))
      c01 += vdot(a0(i), a1(i))
      c11 += vdot(a1(i), a1(i))
      val tmp = vsub(inps(off + i), vadd(vscale(inps(off), b01(t(i))), vscale(inps(off + inpn - 1), b23(t(i)))))
      x0 += vdot(a0(i), tmp)
      x1 += vdot(a1(i), tmp)
      i += 1
    val det01 = c00 * c11 - c01 * c01
    val det0X = c00 * x1 - c01 * x0
    val detX1 = x0 * c11 - x1 * c01
    var s0 = 0.0; var s3 = 0.0
    if math.abs(det01) >= 1e-6 then { s0 = detX1 / det01; s3 = det0X / det01 }
    if math.abs(det01) < 1e-6 || s0 <= 0.0 || s3 <= 0.0 then
      val d01 = vdist(inps(off), inps(off + inpn - 1)) / 3.0
      s0 = d01; s3 = d01
    (inps(off), vscale(ev0, s0), inps(off + inpn - 1), vscale(ev1, s3))

  private def distN(p: Array[XY]): Double =
    var rv = 0.0; var i = 1
    while i < p.length do { rv += math.hypot(p(i).x - p(i - 1).x, p(i).y - p(i - 1).y); i += 1 }
    rv

  private def splinefits(
      edges: Array[(XY, XY)], pa: XY, va: XY, pb: XY, vb: XY,
      inps: Array[XY], off: Int, inpn: Int, ops: mutable.ArrayBuffer[XY]
  ): Int =
    val forceflag = inpn == 2
    var a = 4.0
    var first = true
    var result = -2
    val inpsSeg = inps.slice(off, off + inpn)
    while result == -2 do
      val sps = Array(
        pa,
        XY(pa.x + a * va.x / 3.0, pa.y + a * va.y / 3.0),
        XY(pb.x - a * vb.x / 3.0, pb.y - a * vb.y / 3.0),
        pb
      )
      if first && distN(sps) < distN(inpsSeg) - 1e-3 then result = 0
      else
        first = false
        if splineIsInside(edges, sps) then
          ops += sps(1); ops += sps(2); ops += sps(3)
          result = 1
        else if a < 0.005 then
          if forceflag then { ops += sps(1); ops += sps(2); ops += sps(3); result = 1 }
          else result = 0
        else
          if a > 0.01 then a = a / 2.0 else a = 0.0
    result

  private def splineIsInside(edges: Array[(XY, XY)], sps: Array[XY]): Boolean =
    var ei = 0
    while ei < edges.length do
      val (la, lb) = edges(ei)
      val roots = new Array[Double](4)
      val rootn = splineIntersectsLine(sps, la, lb, roots)
      if rootn != 4 then
        var ri = 0
        while ri < rootn do
          val tt = roots(ri)
          if tt >= 1e-6 && tt <= 1 - 1e-6 then
            val td = tt * tt * tt
            val tc = 3 * tt * tt * (1 - tt)
            val tb = 3 * tt * (1 - tt) * (1 - tt)
            val ta = (1 - tt) * (1 - tt) * (1 - tt)
            val ipx = ta * sps(0).x + tb * sps(1).x + tc * sps(2).x + td * sps(3).x
            val ipy = ta * sps(0).y + tb * sps(1).y + tc * sps(2).y + td * sps(3).y
            val d0 = (ipx - la.x) * (ipx - la.x) + (ipy - la.y) * (ipy - la.y)
            val d1 = (ipx - lb.x) * (ipx - lb.x) + (ipy - lb.y) * (ipy - lb.y)
            if d0 >= 1e-3 && d1 >= 1e-3 then return false
          ri += 1
      ei += 1
    true

  private def points2coeff(v0: Double, v1: Double, v2: Double, v3: Double): Array[Double] =
    Array(v0, 3 * (v1 - v0), 3 * v0 + 3 * v2 - 6 * v1, v3 + 3 * v1 - (v0 + 3 * v2))

  private def addRoot(root: Double, roots: Array[Double], rootn: Int): Int =
    if root >= 0 && root <= 1 then { roots(rootn) = root; rootn + 1 } else rootn

  private def splineIntersectsLine(sps: Array[XY], l0: XY, l1: XY, roots: Array[Double]): Int =
    val xc0 = l0.x; val xc1 = l1.x - l0.x
    val yc0 = l0.y; val yc1 = l1.y - l0.y
    var rootn = 0
    if xc1 == 0.0 then
      if yc1 == 0.0 then
        val s1 = points2coeff(sps(0).x, sps(1).x, sps(2).x, sps(3).x); s1(0) -= xc0
        val xr = new Array[Double](3); val xn = solve3(s1, xr)
        val s2 = points2coeff(sps(0).y, sps(1).y, sps(2).y, sps(3).y); s2(0) -= yc0
        val yr = new Array[Double](3); val yn = solve3(s2, yr)
        if xn == 4 then { if yn == 4 then return 4 else { var j = 0; while j < yn do { rootn = addRoot(yr(j), roots, rootn); j += 1 } } }
        else if yn == 4 then { var k = 0; while k < xn do { rootn = addRoot(xr(k), roots, rootn); k += 1 } }
        else { var k = 0; while k < xn do { var j = 0; while j < yn do { if xr(k) == yr(j) then rootn = addRoot(xr(k), roots, rootn); j += 1 }; k += 1 } }
        rootn
      else
        val s1 = points2coeff(sps(0).x, sps(1).x, sps(2).x, sps(3).x); s1(0) -= xc0
        val xr = new Array[Double](3); val xn = solve3(s1, xr)
        if xn == 4 then return 4
        var k = 0
        while k < xn do
          val tv = xr(k)
          if tv >= 0 && tv <= 1 then
            val s2 = points2coeff(sps(0).y, sps(1).y, sps(2).y, sps(3).y)
            val sv0 = s2(0) + tv * (s2(1) + tv * (s2(2) + tv * s2(3)))
            val sv = (sv0 - yc0) / yc1
            if sv >= 0 && sv <= 1 then rootn = addRoot(tv, roots, rootn)
          k += 1
        rootn
    else
      val rat = yc1 / xc1
      val s1 = points2coeff(sps(0).y - rat * sps(0).x, sps(1).y - rat * sps(1).x, sps(2).y - rat * sps(2).x, sps(3).y - rat * sps(3).x)
      s1(0) += rat * xc0 - yc0
      val xr = new Array[Double](3); val xn = solve3(s1, xr)
      if xn == 4 then return 4
      var k = 0
      while k < xn do
        val tv = xr(k)
        if tv >= 0 && tv <= 1 then
          val s2 = points2coeff(sps(0).x, sps(1).x, sps(2).x, sps(3).x)
          val sv0 = s2(0) + tv * (s2(1) + tv * (s2(2) + tv * s2(3)))
          val sv = (sv0 - xc0) / xc1
          if sv >= 0 && sv <= 1 then rootn = addRoot(tv, roots, rootn)
        k += 1
      rootn

  // solve3/solve2/solve1 (pathplan/solvers.c)
  private val EPS = 1e-7
  private def aeq0(x: Double) = x < EPS && x > -EPS
  private def solve3(coeff: Array[Double], roots: Array[Double]): Int =
    val a = coeff(3); val b = coeff(2); val c = coeff(1); val d = coeff(0)
    if aeq0(a) then return solve2(coeff, roots)
    val b3a = b / (3 * a); val ca = c / a; val da = d / a
    var p = b3a * b3a
    val q = 2 * b3a * p - b3a * ca + da
    p = ca / 3 - p
    val disc = q * q + 4 * p * p * p
    var rootn = 0
    if disc < 0 then
      val r = 0.5 * math.sqrt(-disc + q * q)
      val theta = math.atan2(math.sqrt(-disc), -q)
      val temp = 2 * math.cbrt(r)
      roots(0) = temp * math.cos(theta / 3)
      roots(1) = temp * math.cos((theta + 2 * math.Pi) / 3)
      roots(2) = temp * math.cos((theta - 2 * math.Pi) / 3)
      rootn = 3
    else
      val alpha = 0.5 * (math.sqrt(disc) - q)
      val beta = -q - alpha
      roots(0) = math.cbrt(alpha) + math.cbrt(beta)
      if disc > 0 then rootn = 1
      else { roots(1) = -0.5 * roots(0); roots(2) = roots(1); rootn = 3 }
    var i = 0
    while i < rootn do { roots(i) -= b3a; i += 1 }
    rootn

  private def solve2(coeff: Array[Double], roots: Array[Double]): Int =
    val a = coeff(2); val b = coeff(1); val c = coeff(0)
    if aeq0(a) then return solve1(coeff, roots)
    val b2a = b / (2 * a); val ca = c / a
    val disc = b2a * b2a - ca
    if disc < 0 then 0
    else if disc > 0 then { roots(0) = -b2a + math.sqrt(disc); roots(1) = -2 * b2a - roots(0); 2 }
    else { roots(0) = -b2a; 1 }

  private def solve1(coeff: Array[Double], roots: Array[Double]): Int =
    val a = coeff(1); val b = coeff(0)
    if aeq0(a) then { if aeq0(b) then 4 else 0 }
    else { roots(0) = -b / a; 1 }

  // ── clip_and_install (splines.c) + bezier_clip + ellipse insidefn ────────
  private val NodePenwidth = 1.0 // DEFAULT_NODEPENWIDTH

  /** de Casteljau (utils.c `Bezier`): point at t plus [0,t] and [t,1] subs. */
  private def bezier(v: Array[XY], s: Int, t: Double): (XY, Array[XY], Array[XY]) =
    val tri = Array.ofDim[XY](4, 4)
    var j = 0
    while j <= 3 do { tri(0)(j) = v(s + j); j += 1 }
    var i = 1
    while i <= 3 do
      j = 0
      while j <= 3 - i do
        tri(i)(j) = XY(
          (1.0 - t) * tri(i - 1)(j).x + t * tri(i - 1)(j + 1).x,
          (1.0 - t) * tri(i - 1)(j).y + t * tri(i - 1)(j + 1).y
        )
        j += 1
      i += 1
    val left  = Array(tri(0)(0), tri(1)(0), tri(2)(0), tri(3)(0))
    val right = Array(tri(3)(0), tri(2)(1), tri(1)(2), tri(0)(3))
    (tri(3)(0), left, right)

  /** bezier_clip (splines.c): binary search; keep the sub-curve outside the
    * shape. `sp` is a 4-point segment, mutated in place. */
  private[layout] def bezierClip(sp: Array[XY], leftInside: Boolean, inside: XY => Boolean): Unit =
    val seg  = sp.clone()
    val best = sp.clone()
    var found = false
    var low = 0.0
    var high = 1.0
    var pt = if leftInside then sp(0) else sp(3)
    var opt = pt
    var first = true
    while first || math.abs(opt.x - pt.x) > 0.5 || math.abs(opt.y - pt.y) > 0.5 do
      first = false
      opt = pt
      val t = (high + low) / 2.0
      val (p, l, r) = bezier(sp, 0, t)
      pt = p
      val s = if leftInside then r else l
      System.arraycopy(s, 0, seg, 0, 4)
      if inside(pt) then
        if leftInside then low = t else high = t
        System.arraycopy(seg, 0, best, 0, 4)
        found = true
      else if leftInside then high = t
      else low = t
    val srcseg = if found then best else seg
    System.arraycopy(srcseg, 0, sp, 0, 4)

  private def clipInstall(
      g: RGraph, ctrl0: Vector[XY], e: org.jpablo.graphexplorer.graphviz.model.REdge,
      byId: Map[String, org.jpablo.graphexplorer.graphviz.model.RNode],
      centerOf: String => XY,
      tailClip: Boolean = true, headClip: Boolean = true,
      /** gv routes/clips a rank-REVERSED edge (rank(head) < rank(tail)) in the
        * WORKING direction — `ctrl0` runs orig-head → orig-tail — and only
        * swaps the spline at the very end (dotsplines.c swap_spline via
        * swap_ends_p). The bezier_clip bisection is not direction-symmetric,
        * so clipping in the original direction lands the cut a hair off
        * (81-rankmin's 0.06–0.17pt). Here: clip index-0 against the ORIG HEAD,
        * the far end against the ORIG TAIL, run arrowStartClip (arrows.c:315,
        * the head arrow sits at the working START), then reverse the points. */
      reversedWork: Boolean = false,
      /** Defined record/cell port boxes (TRUE-frame, node-local): a clipping
        * end with a port `bp` clips against the FIELD box, not the node shape
        * (record_inside with inside_context.bp, splines.c:277/288). Keyed to
        * the WORKING ends like tailClip/headClip. */
      tailBp: Option[(Double, Double, Double, Double)] = None,
      headBp: Option[(Double, Double, Double, Double)] = None
  ): ESpline =
    val ps =
      if ctrl0.length >= 4 && (ctrl0.length - 1) % 3 == 0 then ctrl0.toArray
      else
        val s = ctrl0.head; val t = ctrl0.last
        Array(s, XY(s.x + (t.x - s.x) / 3, s.y + (t.y - s.y) / 3),
              XY(s.x + 2 * (t.x - s.x) / 3, s.y + 2 * (t.y - s.y) / 3), t)
    val pn = ps.length

    // insidefn: box semi-axes = (sizePt + penwidth)/2. For an ellipse the
    // symmetric poly ⇒ scalex=scaley=1 ⇒ inside ⇔ hypot(P.x/URx,P.y/URy)<1;
    // for a box-family shape (poly_inside on the rectangle) it's the plain
    // axis-aligned bound |px|<URx ∧ |py|<URy.
    def insideFn(id: String): Option[XY => Boolean] =
      byId.get(id).flatMap { n =>
        val shapeName = n.attrs.get("shape").getOrElse("")
        // Convex builtin polygon: poly_inside tests the point against the
        // OUTLINE (penwidth/2-inflated) polygon — inside ⇔ on the centre's
        // side of every outline edge (same_side, shapes.c:371). Order-
        // independent so this full loop equals gv's optimised segment walk.
        Polygon.descOf(shapeName) match
          case Some(_) =>
            NodeSize.polygon(n, g).map { poly =>
              val cen = centerOf(id)
              val rd  = Rank.rankdir(g)
              val ol  = poly.outline
              val m   = ol.length
              // outline bb for the quick reject (n_outline_width/height / 2).
              val boxURx = ol.iterator.map(v => math.abs(v._1)).max
              val boxURy = ol.iterator.map(v => math.abs(v._2)).max
              // poly_inside's cached segment (`s.last`) — fresh per clip
              // context (one closure per edge-end), persists ACROSS the
              // bezier-clip bisection queries. With the early wedge-success
              // this makes the walk gv-exact on CONCAVE rings (cylinder's
              // bezier control points), where a plain all-faces AND differs.
              var last = 0
              (p: XY) =>
                // poly_inside rotates the CANONICAL query into the node's
                // TRUE frame first (ccwrotatepf, shapes.c:2415) — the vertex
                // ring is stored in the final orientation. Identity for TB;
                // load-bearing for LR/RL/BT with a non-symmetric polygon
                // (rotated invtriangle, 168).
                val (px, py) = PortAnchor.ccwrot(p.x - cen.x, p.y - cen.y, rd)
                if math.abs(px) > boxURx || math.abs(py) > boxURy then false
                else
                  var i  = last % m
                  var i1 = (i + 1) % m
                  val (qx, qy) = ol(i); val (rx, ry) = ol(i1)
                  if !sameSide(px, py, 0.0, 0.0, qx, qy, rx, ry) then false
                  else
                    // between this segment's side rays ⇒ inside immediately
                    val s = sameSide(px, py, qx, qy, rx, ry, 0.0, 0.0)
                    if s && sameSide(px, py, rx, ry, 0.0, 0.0, qx, qy) then true
                    else
                      var j = 1; var res = true; var done = false
                      while j < m && !done do
                        if s then { i = i1; i1 = (i + 1) % m }
                        else { i1 = i; i = (i + m - 1) % m }
                        val (ax, ay) = ol(i); val (bx, by) = ol(i1)
                        if !sameSide(px, py, 0.0, 0.0, ax, ay, bx, by) then
                          last = i; res = false; done = true
                        j += 1
                      if res then last = i
                      res
            }
          case None if Set("record", "mrecord").contains(shapeName.toLowerCase) =>
            // record_inside with bp==NULL (shapes.c:3786): the clip box is the
            // FIELD-TREE ROOT box (ND_shape_info fld0->b) ± penwidth/2 in the
            // TRUE frame — NOT the node box, which is 1pt taller (the
            // record_init "+1" height kluge). bpInsideFn IS that test.
            NodeSize.recordLayout(n, g).map(root =>
              bpInsideFn(id, (root.llx, root.lly, root.urx, root.ury)))
          case None =>
            NodeSize.layoutSize(n, g).map { sz =>
              val cen = centerOf(id)
              // plaintext/none/plain are box shapes (sides=4) with NO border
              // ⇒ box clip using the node size directly (peripheries=0, no
              // penwidth inflation). Other box-family shapes add penwidth.
              val plain = Set("plaintext", "none", "plain").contains(shapeName)
              // late_double(n, N_penwidth, 1, 0): the OUTLINE (clip boundary)
              // sits penwidth/2 outside the outermost periphery (shapes.c
              // poly_init) — a penwidth=7 circle clips 3.5pt out (logo).
              val pw =
                if plain then 0.0
                else n.attrs.get("penwidth").flatMap(_.toDoubleOption)
                  .map(math.max(0.0, _)).getOrElse(NodePenwidth)
              val urx = (sz.widthPt.value + pw) / 2.0
              val ury = (sz.heightPt.value + pw) / 2.0
              // Every sides=4 axis-aligned shape clips as a BOX (poly_inside on
              // the rectangle vertices): the box family, Msquare (regular box +
              // decorative diagonals), underline/image, and all the special-
              // corner shapes (note/tab/…/bio — their poly_inside uses the box
              // periphery, the corner geometry is render-only). Records clip
              // against their FIELD-TREE root box (the case above).
              val boxLike = plain ||
                Set("box", "rect", "rectangle", "square", "Msquare", "underline", "image")
                  .contains(shapeName) ||
                RoundCorners.codeOf.contains(shapeName)
              (p: XY) =>
                var px = p.x - cen.x; var py = p.y - cen.y
                // poly_inside's normalizing scale (shapes.c:2472): scalex =
                // n_width / xsize, applied to the query — with a ZERO-size
                // axis the division is SKIPPED and scalex stays 0, collapsing
                // the query to the centre: a 0×0 node (empty plain label)
                // contains EVERY point, so its spline gets swallowed (168 f).
                if sz.widthPt.value == 0.0 then px = 0.0
                if sz.heightPt.value == 0.0 then py = 0.0
                // poly_inside is boundary-INCLUSIVE for polygons: the bbox
                // check rejects only STRICTLY-outside points and same_side's
                // `>= 0` counts an exactly-on-the-outline point as inside.
                // (Matters: a polyline's degenerate-cubic midpoint can land
                // EXACTLY on the outline — sbt's geny→3.3.1 clip at t=0.5.)
                // Ellipses stay strict (`hypot(...) < 1`, shapes.c:2501).
                if boxLike then math.abs(px) <= urx && math.abs(py) <= ury
                else if math.abs(px) > urx || math.abs(py) > ury then false
                else math.hypot(px / urx, py / ury) < 1.0
            }
      }

    def shapeClip0(seg: Int, inside: XY => Boolean, leftInside: Boolean): Unit =
      val w = Array(ps(seg), ps(seg + 1), ps(seg + 2), ps(seg + 3))
      bezierClip(w, leftInside, inside)
      var k = 0
      while k < 4 do { ps(seg + k) = w(k); k += 1 }

    def approxEq(a: XY, b: XY): Boolean =
      math.abs(a.x - b.x) < 0.001 && math.abs(a.y - b.y) < 0.001

    // record_inside with a port bp (clip_and_install → insidefn with
    // inside_context.bp): the query point is ccw-rotated into the node's
    // TRUE frame and INSIDE-tested against the FIELD box ± penwidth/2.
    def bpInsideFn(id: String, bp: (Double, Double, Double, Double)): XY => Boolean =
      val cen = centerOf(id)
      val rd  = Rank.rankdir(g)
      val pw  = byId.get(id).flatMap(_.attrs.get("penwidth")).flatMap(_.toDoubleOption)
        .map(math.max(0.0, _)).getOrElse(NodePenwidth) / 2.0
      val (bllx, blly, burx, bury) = bp
      (p: XY) =>
        val (tx, ty) = PortAnchor.ccwrot(p.x - cen.x, p.y - cen.y, rd)
        bllx - pw <= tx && tx <= burx + pw && blly - pw <= ty && ty <= bury + pw

    // index-0 end of the working list: the orig tail normally, the orig HEAD
    // for a reversed edge (gv clip_and_install clips fe's tn/hn, splines.c:269).
    val startNodeId = if reversedWork then e.head else e.tail
    val endNodeId   = if reversedWork then e.tail else e.head
    // tail node clip — skipped when the port set clip=false (the resolved
    // port point IS the endpoint; cf. beginpath `ED_*_port.clip = false`).
    var start = 0
    if tailClip then tailBp.map(bp => Some(bpInsideFn(startNodeId, bp))).getOrElse(insideFn(startNodeId)).foreach { ins =>
      var s = 0
      var stop = false
      while !stop && s < pn - 4 do
        if !ins(ps(s + 3)) then stop = true else s += 3
      start = s
      shapeClip0(start, ins, true)
    }
    // head node clip — likewise skipped for a clip=false port.
    var end = pn - 4
    if headClip then headBp.map(bp => Some(bpInsideFn(endNodeId, bp))).getOrElse(insideFn(endNodeId)).foreach { ins =>
      var en = pn - 4
      var stop = false
      while !stop && en > 0 do
        if !ins(ps(en)) then stop = true else en -= 3
      end = en
      shapeClip0(end, ins, false)
    }
    while start < pn - 4 && approxEq(ps(start), ps(start + 3)) do start += 3
    while end > 0 && approxEq(ps(end), ps(end + 3)) do end -= 3

    // arrow_clip (arrows.c arrowEndClip): trim the curve by the *true*
    // normal-arrowhead length = `arrow_length_normal` (≈11.53 at the
    // defaults, NOT the nominal ARROW_LENGTH 10 — the miter makes a
    // stroked triangle longer than its geometric vertex). Closes the
    // long-deferred M5/M7 sub-2px residual. `epAttach` = Graphviz's
    // spl->ep, captured before the gap shortens the curve (unchanged).
    // arrow_clip (arrows.c:344): arrow_flags gives the ORIG-orientation
    // (tail, head) arrow names; a reversed working edge swaps them ("swap the
    // two ends"), then arrowStartClip runs before arrowEndClip — both in the
    // WORKING orientation. `none`/gap keeps the existing no-trim convention.
    val (snameO, enameO) = Arrow.flags(g.directed, e.attrs.get("dir"),
      e.attrs.get("arrowhead"), e.attrs.get("arrowtail"))
    val (sName, eName) = if reversedWork then (enameO, snameO) else (snameO, enameO)
    val pw  = e.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
    val asz = e.attrs.get("arrowsize").flatMap(_.toDoubleOption).getOrElse(1.0)
    var spAttachW: Option[XY] = None // working-start arrow attach (spl->sp)
    var epAttachW: Option[XY] = None // working-end arrow attach (spl->ep)
    // arrowStartClip (arrows.c:315): `sp` saved before the segment advance;
    // the working segment enters the clip reversed, written back reversed.
    sName.filter(_ != "none").foreach { name =>
      // arrow type sets the trim length: `vee` (crow, ≈11.22) ≠ `normal`
      // (≈11.53) — the miter differs, so the end-side control points shift.
      val elen  = Arrow.length(name, pw, asz).value
      val elen2 = elen * elen
      val sp = ps(start)
      spAttachW = Some(sp)
      if end > start && dist2(ps(start), ps(start + 3)) < elen2 then start += 3
      val w = Array(ps(start + 3), ps(start + 2), ps(start + 1), sp)
      bezierClip(w, false, (p: XY) => dist2(p, sp) < elen2)
      ps(start) = w(3); ps(start + 1) = w(2); ps(start + 2) = w(1); ps(start + 3) = w(0)
    }
    // arrowEndClip
    eName.filter(_ != "none").foreach { name =>
      val elen  = Arrow.length(name, pw, asz).value
      val elen2 = elen * elen
      val ep = ps(end + 3)
      epAttachW = Some(ep)
      if end > start && dist2(ps(end), ps(end + 3)) < elen2 then end -= 3
      val sp = Array(ep, ps(end + 2), ps(end + 1), ps(end))
      bezierClip(sp, true, (p: XY) => dist2(p, ep) < elen2)
      ps(end) = sp(3); ps(end + 1) = sp(2); ps(end + 2) = sp(1); ps(end + 3) = sp(0)
    }

    // swap_spline (dotsplines.c:158): a reversed edge's spline is installed
    // flipped back into the ORIGINAL direction (points reversed, sp/ep
    // swapped — each attach already sits at its orig end).
    val pts = ps.slice(start, end + 4).toVector
    if reversedWork then ESpline(pts.reverse, spAttachW, epAttachW)
    else ESpline(pts, epAttachW, spAttachW)

  private def dist2(a: XY, b: XY): Double =
    val dx = a.x - b.x; val dy = a.y - b.y
    dx * dx + dy * dy

  /** shapes.c `same_side`: are p0 and p1 on the same side of line L0→L1?
    * Uses the `≥ 0` half-plane test (inclusive) exactly as gv, so the
    * bezier-clip boundary matches bit-for-bit. */
  private def sameSide(
      p0x: Double, p0y: Double, p1x: Double, p1y: Double,
      l0x: Double, l0y: Double, l1x: Double, l1y: Double
  ): Boolean =
    val a = -(l1y - l0y); val b = l1x - l0x; val c = a * l0x + b * l0y
    ((a * p0x + b * p0y - c) >= 0) == ((a * p1x + b * p1y - c) >= 0)

end Spline
