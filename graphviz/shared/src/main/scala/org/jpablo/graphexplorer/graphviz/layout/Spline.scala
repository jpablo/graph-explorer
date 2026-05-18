package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.RGraph
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

  private val NodeSep     = 18.0           // POINTS(DEFAULT_NODESEP 0.25in)
  private val Splinesep   = NodeSep / 4.0  // dot_splines_ sd.Splinesep
  private val MINW        = 16.0           // dotsplines.c min box width
  private val HALFMINW    = 8.0
  private val FUDGE        = 4.0           // maximal_bbox FUDGE
  private val ArrowLen    = 10.0           // arrows.c ARROW_LENGTH
  private val VirtualHalf = 1.0 + NodeSep / 2.0 // class2.c plain_vnode (== XCoord)

  final case class Pt(x: Double, y: Double)

  /** Installed edge spline: piecewise-cubic control points plus the arrow
    * attach points Graphviz records on the `bezier` struct — `ep` (head, set
    * iff a head arrow is drawn) / `sp` (tail). These drive json0's
    * `e,EX,EY`/`s,SX,SY` `pos` prefixes. */
  final case class ESpline(pts: Vector[Pt], ep: Option[Pt], sp: Option[Pt])
  private final case class Box(var llx: Double, var lly: Double, var urx: Double, var ury: Double)

  /** Declared-edge index (position in `g.edges`) → piecewise-cubic control
    * points. Keyed by edge **identity**, not `(tail,head)`, so parallel /
    * port-distinguished multi-edges (04's two `struct1→struct2`) do not
    * collapse — every consumer correlates by the `g.edges` index. */
  def splines(g: RGraph): Map[Int, Vector[Pt]] =
    splinesEx(g).view.mapValues(_.pts).toMap

  /** Declared-edge index (position in `g.edges`) → installed spline + arrow
    * attaches. See [[splines]] for why the key is the edge index. */
  def splinesEx(g: RGraph): Map[Int, ESpline] =
    val res          = Order.order(g)
    val (_, allX)    = XCoord.solveAll(g)
    val (_, yOf)     = Coord.rankY(g)
    val byId         = g.nodes.iterator.map(n => n.id -> n).toMap
    val orderByRank  = res.order // rank → left→right ids (real + virtual)
    // res.rank is real-only; invert res.order for a complete (incl. virtual)
    // id→rank consistent with Order's __v{idx}_{r} placement and XCoord.
    val rankOf       = orderByRank.iterator.flatMap { case (r, ids) => ids.map(_ -> r) }.toMap

    def isV(id: String): Boolean = res.isVirtual(id)
    def cx(id: String): Double   = allX(id)
    def cy(id: String): Double   = yOf(rankOf(id))
    def lw(id: String): Double =
      if isV(id) then VirtualHalf
      else byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map(_.widthIn * 36.0).getOrElse(1.0)
    def rw(id: String): Double = lw(id)

    // ht1/ht2 (GD_rank[r] half-heights) = tallest real node half-height in rank.
    val halfHt = mutable.HashMap.empty[Int, Double].withDefaultValue(0.0)
    g.nodes.foreach { n =>
      val r = rankOf(n.id)
      NodeSize.layoutSize(n, g).foreach { sz =>
        val h = sz.heightIn * 36.0
        if h > halfHt(r) then halfHt(r) = h
      }
    }
    def ht1(r: Int): Double = halfHt(r)
    def ht2(r: Int): Double = halfHt(r)

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
    // virtual chain (faithful enough for the no-pathscross corpus scope).
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

    // maximal_bbox(vn): widest box up to in-rank neighbours.
    def maximalBbox(vn: String, ownPath: Set[String]): Box =
      val r = rankOf(vn)
      var b = cx(vn) - lw(vn) - FUDGE
      val llx =
        neighbor(vn, -1, ownPath) match
          case Some(left) =>
            val base = cx(left) + rw(left)
            val nb   = base + (if isV(left) then Splinesep else NodeSep / 2.0)
            if nb < b then b = nb
            math.round(b).toDouble
          case None => math.min(math.round(b).toDouble, leftBound)
      var b2 = cx(vn) + rw(vn) + FUDGE
      val urx =
        neighbor(vn, 1, ownPath) match
          case Some(right) =>
            val base = cx(right) - lw(right)
            val nb   = base - (if isV(right) then Splinesep else NodeSep / 2.0)
            if nb > b2 then b2 = nb
            math.round(b2).toDouble
          case None => math.max(math.round(b2).toDouble, rightBound)
      Box(llx, cy(vn) - ht1(r), urx, cy(vn) + ht2(r))

    // rank_box(r): full-width inter-rank gap between rank r (upper) and r+1.
    def rankBox(rUpper: Int, rLower: Int): Box =
      Box(leftBound, yOf(rLower) + ht2(rLower), rightBound, yOf(rUpper) - ht1(rUpper))

    // installed spline keyed by the declared-edge index (position in
    // g.edges) — parallel/port-distinguished multi-edges must NOT collapse.
    val out = mutable.LinkedHashMap.empty[Int, ESpline]

    def halfH(id: String): Double =
      byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map(_.heightIn * 36.0).getOrElse(0.0)
    val centerOf: String => Pt = id => Pt(cx(id), cy(id))

    // ── beginpath/endpath port branch (record field ports, TB) ────────────
    // Returns the per-end channel box, aim point and constrained tangent for
    // a record-port endpoint, or None to fall back to the node-centred path.
    final case class End(box: Box, p: Pt, theta: Double, constrained: Boolean, clip: Boolean)

    def recRoot(id: String): Option[RecordLabel.Field] =
      byId.get(id).flatMap(n => NodeSize.recordLayout(n, g))

    // resolvePort/closestSide + compassPort over a node-local field box.
    def portEnd(id: String, other: String, port: org.jpablo.graphexplorer.graphviz.dotlang.Port, isTail: Boolean): Option[End] =
      val name = port.name.map(_.value).filter(_.nonEmpty)
      recRoot(id).flatMap { root => name.flatMap { nm => RecordLabel.field(root, nm).flatMap { fld =>
        val (llx, lly, urx, ury) = (fld.llx, fld.lly, fld.urx, fld.ury)
        val bcx = (llx + urx) / 2.0; val bcy = (lly + ury) / 2.0
        val nc  = centerOf(id)
        import org.jpablo.graphexplorer.graphviz.dotlang.Compass.*
        import RecordLabel.{Bottom, Right, Top, Left}
        // compassPort(box, compass, fld.sides) → (pLocal, side, theta, clip)
        def cp(c: org.jpablo.graphexplorer.graphviz.dotlang.Compass): (Pt, Int, Double, Boolean) =
          c match
            case N  => (Pt(bcx, ury), fld.sides & Top,            math.Pi / 2,      false)
            case S  => (Pt(bcx, lly), fld.sides & Bottom,        -math.Pi / 2,      false)
            case E  => (Pt(urx, bcy), fld.sides & Right,          0.0,              false)
            case W  => (Pt(llx, bcy), fld.sides & Left,           math.Pi,          false)
            case NE => (Pt(urx, ury), fld.sides & (Top | Right),  math.Pi / 4,      false)
            case NW => (Pt(llx, ury), fld.sides & (Top | Left),   3 * math.Pi / 4,  false)
            case SE => (Pt(urx, lly), fld.sides & (Bottom|Right), -math.Pi / 4,     false)
            case SW => (Pt(llx, lly), fld.sides & (Bottom|Left),  -3 * math.Pi / 4, false)
            case C | Underscore => (Pt(bcx, bcy), 0, 0.0, true)
        val (pl, side, theta, clip, constrained) =
          port.compass match
            case Some(c) if c != Underscore && c != C =>
              val (p, s, th, cl) = cp(c); (p, s, th, cl, true)
            case _ =>
              // `_` / no compass ⇒ dyna ⇒ resolvePort(closestSide)
              if fld.sides == 0 || fld.sides == (Bottom | Right | Top | Left) then
                (Pt(bcx, bcy), 0, 0.0, true, false)
              else
                // side midpoints (node-local), pick the one closest to `other`
                val oc = centerOf(other)
                val cand = Seq(
                  (Bottom, Pt(bcx, lly), S), (Right, Pt(urx, bcy), E),
                  (Top,    Pt(bcx, ury), N), (Left,  Pt(llx, bcy), W)
                ).filter((bit, _, _) => (fld.sides & bit) != 0)
                val (_, _, bestC) = cand.minBy { (_, mp, _) =>
                  val ax = nc.x + mp.x - oc.x; val ay = nc.y + mp.y - oc.y
                  ax * ax + ay * ay
                }
                val (p, s, th, cl) = cp(bestC); (p, s, th, cl, true)
        // beginpath/endpath: box + the ±1 router nudge (REGULAREDGE, NORMAL)
        val aim = Pt(nc.x + pl.x, nc.y + pl.y)
        val hh  = halfH(id)
        if side != 0 then
          if isTail && (side & Bottom) != 0 then
            Some(End(maximalBbox(id, Set(id)), Pt(aim.x, aim.y - 1.0), theta, constrained, clip))
          else if !isTail && (side & Top) != 0 then
            Some(End(maximalBbox(id, Set(id)), Pt(aim.x, aim.y + 1.0), theta, constrained, clip))
          else None // tail TOP/L/R or head BOTTOM/L/R: out of 04 scope
        else
          // pboxfn = record_path: the top-level field whose x-range holds p.x,
          // clipped to the node's full height. No router nudge.
          root.flds.find(f => pl.x >= f.llx && pl.x <= f.urx).orElse(Some(root)).map { f =>
            End(Box(nc.x + f.llx, nc.y - hh, nc.x + f.urx, nc.y + hh), aim, theta, constrained, clip)
          }
      }}}

    g.edges.zipWithIndex.filter { case (e, _) => e.tail != e.head }
      .zipWithIndex.foreach { case ((e, origIdx), i) =>
      val rt = rankOf(e.tail)
      val rh = rankOf(e.head)
      if rt != rh then
        val lo = math.min(rt, rh)
        val hi = math.max(rt, rh)
        // virtual chain ids for this declared edge (Order's __v{i}_{r} scheme)
        val midRanks = (lo + 1 until hi).toVector
        val mids     = midRanks.map(r => s"__v${i}_$r")
        if mids.forall(m => rankOf.contains(m) && allX.contains(m)) then
          // path nodes top→bottom (rank lo .. hi)
          val low  = if rt < rh then e.tail else e.head
          val high = if rt < rh then e.head else e.tail
          val pathTopDown: Vector[String] = (low +: mids) :+ high
          val ownPath = pathTopDown.toSet
          val tn0   = pathTopDown.head
          val hn0   = pathTopDown.last

          // Port routing: only the scoped case (adjacent ranks, NORMAL real
          // endpoints, ≥1 resolvable record port). Else node-centred path —
          // additive, so every portless edge stays byte-identical.
          val tEnd =
            if rt < rh then e.tailPort.flatMap(portEnd(tn0, hn0, _, isTail = true))
            else e.headPort.flatMap(portEnd(tn0, hn0, _, isTail = true))
          val hEnd =
            if rt < rh then e.headPort.flatMap(portEnd(hn0, tn0, _, isTail = false))
            else e.tailPort.flatMap(portEnd(hn0, tn0, _, isTail = false))
          val portRoute = mids.isEmpty && (tEnd.isDefined || hEnd.isDefined)

          if portRoute then
            // ── port box channel: [tail band] [rank gap] [head band] ──────
            val tBox  = tEnd.map(_.box).getOrElse(maximalBbox(tn0, ownPath))
            val hBox  = hEnd.map(_.box).getOrElse(maximalBbox(hn0, ownPath))
            val boxes = mutable.ArrayBuffer(tBox, rankBox(rankOf(tn0), rankOf(hn0)), hBox)
            val start = tEnd.map(_.p).getOrElse(Pt(cx(tn0), cy(tn0) - 1.0))
            val end   = hEnd.map(_.p).getOrElse(Pt(cx(hn0), cy(hn0) + 1.0))
            val ev0   =
              if tEnd.exists(_.constrained) then
                val th = tEnd.get.theta; Pt(math.cos(th), math.sin(th))
              else Pt(0, 0)
            val ev1 =
              if hEnd.exists(_.constrained) then
                val th = hEnd.get.theta; Pt(-math.cos(th), -math.sin(th))
              else Pt(0, 0)
            adjustRegularPath(boxes)
            val st = Array(start.x, start.y); val en = Array(end.x, end.y)
            val ctrl0 =
              if checkpath(boxes, st, en) then Vector(Pt(st(0), st(1)), Pt(en(0), en(1)))
              else
                val poly = buildPolygon(boxes)
                val sp   = funnel(boxes, Pt(st(0), st(1)), Pt(en(0), en(1)))
                proutespline(poly, sp, ev0, ev1)
            out(origIdx) = clipInstall(
              g, ctrl0, e, byId, centerOf,
              tailClip = tEnd.forall(_.clip), headClip = hEnd.forall(_.clip)
            )
          else
            // ── node-centred box channel (completeregularpath) ────────────
            val boxes = mutable.ArrayBuffer.empty[Box]
            val tBb   = maximalBbox(tn0, ownPath)
            boxes += Box(tBb.llx, cy(tn0) - ht1(lo), tBb.urx, cy(tn0)) // tail half
            var idxN = 0
            while idxN < pathTopDown.length - 2 do
              val upper = pathTopDown(idxN)
              val vn    = pathTopDown(idxN + 1)
              boxes += rankBox(rankOf(upper), rankOf(vn))
              val vBb = maximalBbox(vn, ownPath)
              boxes += vBb
              idxN += 1
            val lastUpper = pathTopDown(pathTopDown.length - 2)
            boxes += rankBox(rankOf(lastUpper), rankOf(hn0))
            val hBb = maximalBbox(hn0, ownPath)
            boxes += Box(hBb.llx, cy(hn0), hBb.urx, cy(hn0) + ht2(hi)) // head half

            val start = Pt(cx(tn0), cy(tn0) - 1.0)
            val end   = Pt(cx(hn0), cy(hn0) + 1.0)

            adjustRegularPath(boxes)
            val st  = Array(start.x, start.y)
            val en  = Array(end.x, end.y)
            if checkpath(boxes, st, en) then
              out(origIdx) = clipInstall(g, Vector(start, end), e, byId, centerOf)
            else
              val poly  = buildPolygon(boxes)
              val sp    = funnel(boxes, Pt(st(0), st(1)), Pt(en(0), en(1)))
              val raw   = proutespline(poly, sp)
              val ctrl  = if rt < rh then raw else raw.reverse
              out(origIdx) = clipInstall(g, ctrl, e, byId, centerOf)
    }
    out.toMap

  // ── adjustregularpath: widen path boxes to ≥ MINW ─────────────────────────
  private def adjustRegularPath(boxes: mutable.ArrayBuffer[Box]): Unit =
    var i = 0
    while i + 1 < boxes.length do
      val a = boxes(i); val b = boxes(i + 1)
      if a.urx - MINW < b.llx then b.llx = a.urx - MINW
      if a.llx + MINW > b.urx then b.urx = a.llx + MINW
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
  private def buildPolygon(boxes: mutable.ArrayBuffer[Box]): Array[Pt] =
    val n   = boxes.length
    val pts = mutable.ArrayBuffer.empty[Pt]
    def prevNext(bi: Int): (Int, Int) =
      val prev = if bi > 0 then (if boxes(bi).lly > boxes(bi - 1).lly then -1 else 1) else 0
      val next = if bi + 1 < n then (if boxes(bi + 1).lly > boxes(bi).lly then 1 else -1) else 0
      (prev, next)
    var bi = 0
    while bi < n do
      val (prev, next) = prevNext(bi)
      if prev != next then
        if next == -1 || prev == 1 then
          pts += Pt(boxes(bi).llx, boxes(bi).ury); pts += Pt(boxes(bi).llx, boxes(bi).lly)
        else
          pts += Pt(boxes(bi).urx, boxes(bi).lly); pts += Pt(boxes(bi).urx, boxes(bi).ury)
      else if prev == 0 then
        pts += Pt(boxes(bi).llx, boxes(bi).ury); pts += Pt(boxes(bi).llx, boxes(bi).lly)
      bi += 1
    bi = n - 1
    while bi >= 0 do
      val prev = if bi + 1 < n then (if boxes(bi).lly > boxes(bi + 1).lly then -1 else 1) else 0
      val next = if bi > 0 then (if boxes(bi - 1).lly > boxes(bi).lly then 1 else -1) else 0
      if prev != next then
        if next == -1 || prev == 1 then
          pts += Pt(boxes(bi).llx, boxes(bi).ury); pts += Pt(boxes(bi).llx, boxes(bi).lly)
        else
          pts += Pt(boxes(bi).urx, boxes(bi).lly); pts += Pt(boxes(bi).urx, boxes(bi).ury)
      else if prev == 0 then
        pts += Pt(boxes(bi).urx, boxes(bi).lly); pts += Pt(boxes(bi).urx, boxes(bi).ury)
      else
        pts += Pt(boxes(bi).urx, boxes(bi).lly); pts += Pt(boxes(bi).urx, boxes(bi).ury)
        pts += Pt(boxes(bi).llx, boxes(bi).ury); pts += Pt(boxes(bi).llx, boxes(bi).lly)
      bi -= 1
    pts.toArray

  /** Taut-string geodesic through the box portals (== `Pshortestpath` for a
    * y-monotone box channel). Canonical "simple stupid funnel" (Mononen).
    * Facing the descending path, east (+x) is the left chain, west the right.
    */
  private def funnel(boxes: mutable.ArrayBuffer[Box], s: Pt, e: Pt): Vector[Pt] =
    val gates = mutable.ArrayBuffer.empty[(Pt, Pt)]
    gates += ((s, s))
    var k = 0
    while k + 1 < boxes.length do
      val a = boxes(k); val b = boxes(k + 1)
      val y  = a.lly
      val xl = math.max(a.llx, b.llx)
      val xr = math.min(a.urx, b.urx)
      gates += ((Pt(xl, y), Pt(xr, y))) // left = smaller x, right = larger x
      k += 1
    gates += ((e, e))
    val portals = gates.toArray
    def triArea2(a: Pt, b: Pt, c: Pt): Double =
      (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)
    def eq(a: Pt, b: Pt): Boolean = math.abs(a.x - b.x) < 1e-9 && math.abs(a.y - b.y) < 1e-9
    val pts = mutable.ArrayBuffer[Pt](portals(0)._1)
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
  private def proutespline(poly: Array[Pt], pl: Vector[Pt]): Vector[Pt] =
    proutespline(poly, pl, Pt(0, 0), Pt(0, 0))

  /** `Proutespline` with constrained endpoint slopes (`evs`). routespl.c
    * passes `evs[0]=(cosθs,sinθs)`, `evs[1]=(-cosθe,-sinθe)` for constrained
    * ports; `Proutespline` then `normv`-normalises them. `(0,0)` ⇒ the
    * unconstrained least-squares fit (the portless path). */
  private def proutespline(poly: Array[Pt], pl: Vector[Pt], ev0: Pt, ev1: Pt): Vector[Pt] =
    val edges = Array.tabulate(poly.length)(i => (poly(i), poly((i + 1) % poly.length)))
    val inps  = pl.toArray
    val ops   = mutable.ArrayBuffer.empty[Pt]
    ops += inps(0)
    reallyRoute(edges, inps, 0, inps.length, normv(ev0), normv(ev1), ops)
    ops.toVector

  private def vsub(a: Pt, b: Pt)  = Pt(a.x - b.x, a.y - b.y)
  private def vadd(a: Pt, b: Pt)  = Pt(a.x + b.x, a.y + b.y)
  private def vscale(a: Pt, c: Double) = Pt(a.x * c, a.y * c)
  private def vdot(a: Pt, b: Pt)  = a.x * b.x + a.y * b.y
  private def vdist(a: Pt, b: Pt) = math.hypot(b.x - a.x, b.y - a.y)
  private def normv(v: Pt): Pt =
    val d = v.x * v.x + v.y * v.y
    if d > 1e-6 then { val s = math.sqrt(d); Pt(v.x / s, v.y / s) } else v
  private def b0(t: Double) = { val u = 1 - t; u * u * u }
  private def b1(t: Double) = { val u = 1 - t; 3 * t * u * u }
  private def b2(t: Double) = { val u = 1 - t; 3 * t * t * u }
  private def b3(t: Double) = t * t * t
  private def b01(t: Double) = { val u = 1 - t; u * u * (u + 3 * t) }
  private def b23(t: Double) = { val u = 1 - t; t * t * (3 * u + t) }

  private def reallyRoute(
      edges: Array[(Pt, Pt)], inps: Array[Pt], off: Int, inpn: Int,
      ev0: Pt, ev1: Pt, ops: mutable.ArrayBuffer[Pt]
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
        val d = vdist(Pt(px, py), inps(off + i))
        if d > maxd then { maxd = d; maxi = i }
        i += 1
      val sp = maxi
      val sv1 = normv(vsub(inps(off + sp), inps(off + sp - 1)))
      val sv2 = normv(vsub(inps(off + sp + 1), inps(off + sp)))
      val sv  = normv(vadd(sv1, sv2))
      reallyRoute(edges, inps, off, sp + 1, ev0, sv, ops)
      reallyRoute(edges, inps, off + sp, inpn - sp, sv, ev1, ops)

  private def mkspline(
      inps: Array[Pt], off: Int, inpn: Int, t: Array[Double],
      a0: Array[Pt], a1: Array[Pt], ev0: Pt, ev1: Pt
  ): (Pt, Pt, Pt, Pt) =
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

  private def distN(p: Array[Pt]): Double =
    var rv = 0.0; var i = 1
    while i < p.length do { rv += math.hypot(p(i).x - p(i - 1).x, p(i).y - p(i - 1).y); i += 1 }
    rv

  private def splinefits(
      edges: Array[(Pt, Pt)], pa: Pt, va: Pt, pb: Pt, vb: Pt,
      inps: Array[Pt], off: Int, inpn: Int, ops: mutable.ArrayBuffer[Pt]
  ): Int =
    val forceflag = inpn == 2
    var a = 4.0
    var first = true
    var result = -2
    val inpsSeg = inps.slice(off, off + inpn)
    while result == -2 do
      val sps = Array(
        pa,
        Pt(pa.x + a * va.x / 3.0, pa.y + a * va.y / 3.0),
        Pt(pb.x - a * vb.x / 3.0, pb.y - a * vb.y / 3.0),
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

  private def splineIsInside(edges: Array[(Pt, Pt)], sps: Array[Pt]): Boolean =
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

  private def splineIntersectsLine(sps: Array[Pt], l0: Pt, l1: Pt, roots: Array[Double]): Int =
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
  private def bezier(v: Array[Pt], s: Int, t: Double): (Pt, Array[Pt], Array[Pt]) =
    val tri = Array.ofDim[Pt](4, 4)
    var j = 0
    while j <= 3 do { tri(0)(j) = v(s + j); j += 1 }
    var i = 1
    while i <= 3 do
      j = 0
      while j <= 3 - i do
        tri(i)(j) = Pt(
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
  private def bezierClip(sp: Array[Pt], leftInside: Boolean, inside: Pt => Boolean): Unit =
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
      g: RGraph, ctrl0: Vector[Pt], e: org.jpablo.graphexplorer.graphviz.model.REdge,
      byId: Map[String, org.jpablo.graphexplorer.graphviz.model.RNode],
      centerOf: String => Pt,
      tailClip: Boolean = true, headClip: Boolean = true
  ): ESpline =
    val ps =
      if ctrl0.length >= 4 && (ctrl0.length - 1) % 3 == 0 then ctrl0.toArray
      else
        val s = ctrl0.head; val t = ctrl0.last
        Array(s, Pt(s.x + (t.x - s.x) / 3, s.y + (t.y - s.y) / 3),
              Pt(s.x + 2 * (t.x - s.x) / 3, s.y + 2 * (t.y - s.y) / 3), t)
    val pn = ps.length

    // ellipse insidefn: box semi-axes = (sizePt + penwidth)/2; symmetric poly
    // ⇒ scalex = scaley = 1. inside ⇔ hypot(P.x/URx, P.y/URy) < 1.
    def insideFn(id: String): Option[Pt => Boolean] =
      byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map { sz =>
        val cen = centerOf(id)
        val urx = (sz.widthIn * 72.0 + NodePenwidth) / 2.0
        val ury = (sz.heightIn * 72.0 + NodePenwidth) / 2.0
        (p: Pt) =>
          val px = p.x - cen.x; val py = p.y - cen.y
          if math.abs(px) > urx || math.abs(py) > ury then false
          else math.hypot(px / urx, py / ury) < 1.0
      }

    def shapeClip0(seg: Int, inside: Pt => Boolean, leftInside: Boolean): Unit =
      val w = Array(ps(seg), ps(seg + 1), ps(seg + 2), ps(seg + 3))
      bezierClip(w, leftInside, inside)
      var k = 0
      while k < 4 do { ps(seg + k) = w(k); k += 1 }

    def approxEq(a: Pt, b: Pt): Boolean =
      math.abs(a.x - b.x) < 0.001 && math.abs(a.y - b.y) < 0.001

    // tail node clip — skipped when the port set clip=false (the resolved
    // port point IS the endpoint; cf. beginpath `ED_*_port.clip = false`).
    var start = 0
    if tailClip then insideFn(e.tail).foreach { ins =>
      var s = 0
      var stop = false
      while !stop && s < pn - 4 do
        if !ins(ps(s + 3)) then stop = true else s += 3
      start = s
      shapeClip0(start, ins, true)
    }
    // head node clip — likewise skipped for a clip=false port.
    var end = pn - 4
    if headClip then insideFn(e.head).foreach { ins =>
      var en = pn - 4
      var stop = false
      while !stop && en > 0 do
        if !ins(ps(en)) then stop = true else en -= 3
      end = en
      shapeClip0(end, ins, false)
    }
    while start < pn - 4 && approxEq(ps(start), ps(start + 3)) do start += 3
    while end > 0 && approxEq(ps(end), ps(end + 3)) do end -= 3

    // arrow_clip: directed graphs draw a normal head arrow (ARROW_LENGTH=10).
    // `epAttach` = Graphviz's spl->ep: the node-boundary point the arrowhead
    // points at, captured before the arrow gap shortens the curve.
    var epAttach: Option[Pt] = None
    if g.directed then
      val elen2 = ArrowLen * ArrowLen
      val ep    = ps(end + 3)
      epAttach = Some(ep)
      if end > start && dist2(ps(end), ps(end + 3)) < elen2 then end -= 3
      val sp = Array(ep, ps(end + 2), ps(end + 1), ps(end))
      bezierClip(sp, true, (p: Pt) => dist2(p, ep) < elen2)
      ps(end) = sp(3); ps(end + 1) = sp(2); ps(end + 2) = sp(1); ps(end + 3) = sp(0)

    ESpline(ps.slice(start, end + 4).toVector, epAttach, None)

  private def dist2(a: Pt, b: Pt): Double =
    val dx = a.x - b.x; val dy = a.y - b.y
    dx * dx + dy * dy

end Spline
