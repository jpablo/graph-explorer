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
    * box clipping), but Spline's external surface — the ESpline below —
    * always carries pt-scale coordinates by construction. The `xPt` /
    * `yPt` extensions document that contract at the type level for
    * downstream consumers (Output.json0, Svg.svg). */
  final case class XY(x: Double, y: Double):
    inline def xPt: Pt = Pt(x)
    inline def yPt: Pt = Pt(y)

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
    val Splinesep   = NodeSep / 4.0
    val VirtualHalf = 1.0 + NodeSep / 2.0
    // The Order/XCoord migration to LayoutNode preserves all type-safety at
    // construction. Spline's internals stay String-keyed for its 50+ lookup
    // sites (no unit-mixing risk inside a pure-numerical kernel — see the
    // "type the boundaries, leave the kernels" principle in the Phase 3
    // Pt-flow commit). Convert at the consumption boundary:
    val allX: Map[String, Pt] = allXNode.iterator.map((k, v) => k.name -> v).toMap
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
    def lw0(id: String): Double =
      if labelW.contains(id) then NodeSep
      else if isV(id) then VirtualHalf
      else byId.get(id).flatMap(n => NodeSize.layoutSize(n, g)).map(_.halfWidthPt.value).getOrElse(1.0)
    def lw(id: String): Double  = lwOv.getOrElse(id, lw0(id))
    def rw(id: String): Double  = rwOv.getOrElse(id, labelW.getOrElse(id, lw0(id)))

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

    // cl_bound (dotsplines.c:2198): the REAL cluster of `adj` that interferes
    // with vn's channel — distinct from vn's own (or its original edge's
    // endpoints') clusters; a virtual adj must actually lie inside the box
    // (cl_vninside). The channel then stops at that cluster's border
    // ± Splinesep instead of at the neighbour node itself.
    val cluBBs = Cluster.bbs(g)
    val cluOf  = if cluBBs.isEmpty then Map.empty[String, Int] else Cluster.clustOf(g)
    val dedges = g.edges.filter(e => e.tail != e.head)
    def origEnds(vname: String): Option[(String, String)] =
      LayoutNode.fromName(vname) match
        case LayoutNode.Virtual(d, _) => dedges.lift(d).map(e => (e.tail, e.head))
        case _                        => None
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

    // maximal_bbox(vn): widest box up to in-rank neighbours.
    def maximalBbox(vn: String, ownPath: Set[String]): Box =
      val r = rankOf(vn)
      val labelVn = isLabelV(vn)
      var b = cx(vn) - lw(vn) - FUDGE
      val llx =
        neighbor(vn, -1, ownPath) match
          case Some(left) =>
            val nb = clBound(vn, left) match
              case Some(cl) => cluBBs(cl).urx + Splinesep
              case None =>
                cx(left) + rw(left) + (if isV(left) then Splinesep else NodeSep / 2.0)
            if nb < b then b = nb
            math.round(b).toDouble
          case None => math.min(math.round(b).toDouble, leftBound)
      // "we have to leave room for our own label" (maximal_bbox, dotsplines.c:
      // 2276): a label vnode routes its edge to the LEFT of the label text, so
      // the right bound starts at x+10 (not x+rw) and — after the neighbour
      // clamp — the label width is subtracted, leaving the label its own strip.
      var b2 = if labelVn then cx(vn) + 10.0 else cx(vn) + rw(vn) + FUDGE
      var urx =
        neighbor(vn, 1, ownPath) match
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
    val TopRankSep = 36.0 // DEFAULT_RANKSEP (box top only; not path-critical)
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

    // resolvePort/closestSide + compassPort over a node-local field box.
    def portEnd(id: String, other: String, port: org.jpablo.graphexplorer.graphviz.dotlang.Port, isTail: Boolean): Option[End] =
      val name = port.name.map(_.value).filter(_.nonEmpty)
      if byId.get(id).exists(_.attrs.isHtml("label")) then return htmlPortEnd(id, other, port, isTail)
      recRoot(id).flatMap { root => name.flatMap { nm => RecordLabel.field(root, nm).flatMap { fld =>
        val (llx, lly, urx, ury) = (fld.llx, fld.lly, fld.urx, fld.ury)
        val bcx = (llx + urx) / 2.0; val bcy = (lly + ury) / 2.0
        val nc  = centerOf(id)
        import org.jpablo.graphexplorer.graphviz.dotlang.Compass.*
        import RecordLabel.{Bottom, Right, Top, Left}
        // compassPort(box, compass, fld.sides) → (pLocal, side, theta, clip)
        def cp(c: org.jpablo.graphexplorer.graphviz.dotlang.Compass): (XY, Int, Double, Boolean) =
          c match
            case N  => (XY(bcx, ury), fld.sides & Top,            math.Pi / 2,      false)
            case S  => (XY(bcx, lly), fld.sides & Bottom,        -math.Pi / 2,      false)
            case E  => (XY(urx, bcy), fld.sides & Right,          0.0,              false)
            case W  => (XY(llx, bcy), fld.sides & Left,           math.Pi,          false)
            case NE => (XY(urx, ury), fld.sides & (Top | Right),  math.Pi / 4,      false)
            case NW => (XY(llx, ury), fld.sides & (Top | Left),   3 * math.Pi / 4,  false)
            case SE => (XY(urx, lly), fld.sides & (Bottom|Right), -math.Pi / 4,     false)
            case SW => (XY(llx, lly), fld.sides & (Bottom|Left),  -3 * math.Pi / 4, false)
            case C | Underscore => (XY(bcx, bcy), 0, 0.0, true)
        val (pl, side, theta, clip, constrained) =
          port.compass match
            case Some(c) if c != Underscore && c != C =>
              val (p, s, th, cl) = cp(c); (p, s, th, cl, true)
            case _ =>
              // `_` / no compass ⇒ dyna ⇒ resolvePort(closestSide)
              if fld.sides == 0 || fld.sides == (Bottom | Right | Top | Left) then
                (XY(bcx, bcy), 0, 0.0, true, false)
              else
                // side midpoints (node-local), pick the one closest to `other`
                val oc = centerOf(other)
                val cand = Seq(
                  (Bottom, XY(bcx, lly), S), (Right, XY(urx, bcy), E),
                  (Top,    XY(bcx, ury), N), (Left,  XY(llx, bcy), W)
                ).filter((bit, _, _) => (fld.sides & bit) != 0)
                val (_, _, bestC) = cand.minBy { (_, mp, _) =>
                  val ax = nc.x + mp.x - oc.x; val ay = nc.y + mp.y - oc.y
                  ax * ax + ay * ay
                }
                val (p, s, th, cl) = cp(bestC); (p, s, th, cl, true)
        // beginpath/endpath: box + the ±1 router nudge (REGULAREDGE, NORMAL)
        val aim = XY(nc.x + pl.x, nc.y + pl.y)
        val hh  = halfH(id)
        if side != 0 then
          if isTail && (side & Bottom) != 0 then
            Some(End(maximalBbox(id, Set(id)), XY(aim.x, aim.y - 1.0), theta, constrained, clip))
          else if !isTail && (side & Top) != 0 then
            Some(End(maximalBbox(id, Set(id)), XY(aim.x, aim.y + 1.0), theta, constrained, clip))
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

            val start = XY(cx(tn0), cy(tn0) - 1.0)
            val end   = XY(cx(hn0), cy(hn0) + 1.0)

            adjustRegularPath(boxes)
            val st  = Array(start.x, start.y)
            val en  = Array(end.x, end.y)
            val routed =
              if checkpath(boxes, st, en) then
                out(origIdx) = clipInstall(g, Vector(start, end), e, byId, centerOf)
                Vector(start, end)
              else
                val poly  = buildPolygon(boxes)
                val sp    = funnel(boxes, XY(st(0), st(1)), XY(en(0), en(1)))
                val raw   = proutespline(poly, sp)
                val ctrl  = if rt < rh then raw else raw.reverse
                out(origIdx) = clipInstall(g, ctrl, e, byId, centerOf)
                ctrl
            // narrow the channel boxes to the routed spline corridor, then snap
            // this edge's virtual nodes to them (mids are top→bottom); feeds lp
            // + later edges' neighbour geometry.
            limitBoxes(boxes, routed)
            recoverSlack(mids, boxes, origIdx)
      else
        // ── flat edge (rt == rh): same-rank edge (rank=same / minlen=0) ─────
        // `makeSimpleFlat` (dotsplines.c): a 4-point Bezier at y=tp.y from tail
        // to head, then clip_and_install (clip to node boundaries + arrow).
        // Scoped to the simple case gv routes this way — adjacent endpoints,
        // no ports, no label. Non-adjacent (routed around intervening nodes)
        // and labeled/ported flat edges are tracked deferrals.
        val row      = orderByRank.getOrElse(rt, Vector.empty)
        val adjacent = math.abs(row.indexOf(e.tail) - row.indexOf(e.head)) == 1
        val plain    = e.tailPort.isEmpty && e.headPort.isEmpty &&
                       !e.attrs.get("label").exists(_.nonEmpty)
        if adjacent && plain then
          val tp   = XY(cx(e.tail), cy(e.tail))
          val hp   = XY(cx(e.head), cy(e.head))
          val ctrl = Vector(tp,
            XY((2 * tp.x + hp.x) / 3.0, tp.y),
            XY((2 * hp.x + tp.x) / 3.0, tp.y),
            hp)
          out(origIdx) = clipInstall(g, ctrl, e, byId, centerOf)
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
        group.zipWithIndex.foreach { case ((e, origIdx), i) =>
          val k  = i + 1
          val dx = rw + k * stepx
          val tx = rw + k * stepx
          val hx = rw + k * stepx
          val dy = k * stepy // sgn = +1 (tp.y == hp.y, no-port ⇒ no flip)
          val pts = Vector(
            np,
            XY(np.x + tx / 3.0, np.y + dy),
            XY(np.x + dx, np.y + dy),
            XY(np.x + dx, np.y),
            XY(np.x + dx, np.y - dy),
            XY(np.x + hx / 3.0, np.y - dy),
            np
          )
          out(origIdx) = clipInstall(g, pts, e, byId, centerOf)
        }
      }
    (out.toMap, labelPos.toMap)

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
    val portals = gates.toArray
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
  private def bezierClip(sp: Array[XY], leftInside: Boolean, inside: XY => Boolean): Unit =
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
      tailClip: Boolean = true, headClip: Boolean = true
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
              val ol  = poly.outline
              val m   = ol.length
              (p: XY) =>
                val px = p.x - cen.x; val py = p.y - cen.y
                var i = 0; var inside = true
                while i < m && inside do
                  val (qx, qy) = ol(i); val (rx, ry) = ol((i + 1) % m)
                  if !sameSide(px, py, 0.0, 0.0, qx, qy, rx, ry) then inside = false
                  i += 1
                inside
            }
          case None =>
            NodeSize.layoutSize(n, g).map { sz =>
              val cen = centerOf(id)
              // plaintext/none/plain are box shapes (sides=4) with NO border
              // ⇒ box clip using the node size directly (peripheries=0, no
              // penwidth inflation). Other box-family shapes add penwidth.
              val plain = Set("plaintext", "none", "plain").contains(shapeName)
              val pw  = if plain then 0.0 else NodePenwidth
              val urx = (sz.widthPt.value + pw) / 2.0
              val ury = (sz.heightPt.value + pw) / 2.0
              val boxLike = plain || Set("box", "rect", "rectangle", "square").contains(shapeName)
              (p: XY) =>
                val px = p.x - cen.x; val py = p.y - cen.y
                if boxLike then math.abs(px) < urx && math.abs(py) < ury
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

    // arrow_clip (arrows.c arrowEndClip): trim the curve by the *true*
    // normal-arrowhead length = `arrow_length_normal` (≈11.53 at the
    // defaults, NOT the nominal ARROW_LENGTH 10 — the miter makes a
    // stroked triangle longer than its geometric vertex). Closes the
    // long-deferred M5/M7 sub-2px residual. `epAttach` = Graphviz's
    // spl->ep, captured before the gap shortens the curve (unchanged).
    var epAttach: Option[XY] = None
    if g.directed then
      val pw    = e.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
      val asz   = e.attrs.get("arrowsize").flatMap(_.toDoubleOption).getOrElse(1.0)
      // arrowhead type sets the trim length: `vee` (crow, ≈11.22) ≠ `normal`
      // (≈11.53) — the miter differs, so 02's head-side control points shift.
      val elen  = Arrow.length(e.attrs.getOrElse("arrowhead", "normal"), pw, asz).value
      val elen2 = elen * elen
      val ep    = ps(end + 3)
      epAttach = Some(ep)
      if end > start && dist2(ps(end), ps(end + 3)) < elen2 then end -= 3
      val sp = Array(ep, ps(end + 2), ps(end + 1), ps(end))
      bezierClip(sp, true, (p: XY) => dist2(p, ep) < elen2)
      ps(end) = sp(3); ps(end + 1) = sp(2); ps(end + 2) = sp(1); ps(end + 3) = sp(0)

    ESpline(ps.slice(start, end + 4).toVector, epAttach, None)

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
