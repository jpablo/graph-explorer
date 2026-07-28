package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.model.{RGraph, REdge}
import org.jpablo.graphexplorer.graphviz.units.Length.Pt
import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLayout}
import scala.collection.mutable

/** Phase 3a of the `dot` pipeline: rank-axis (Y) coordinate assignment.
  *
  * Ports `set_ycoords` from `lib/dotgen/position.c` (gv 13.0.1): the bottom
  * rank's centre sits at its own half-height; each rank above is offset by
  * `halfHt(below) + halfHt(this) + ranksep`. Deterministic — gated tight
  * against the `plain` golden.
  *
  * The cross-axis (X) coordinate is intentionally NOT here: `set_xcoords`
  * just reads `ND_rank` from a network-simplex solve on an auxiliary graph,
  * so X needs the NS kernel (its own milestone — see PORT.md §4/§5.2).
  */
object Coord:

  /** Effective pen width (gvrender_set_style + emit_begin_*): style items
    * apply IN ORDER — `bold` ⇒ 2, `setlinewidth(N)` ⇒ N (last wins) — and a
    * `penwidth` ATTR overrides them (it is set AFTER gvrender_set_style).
    * None ⇒ default 1. */
  def penwidthOpt(attrs: org.jpablo.graphexplorer.graphviz.model.Attrs): Option[Double] =
    penwidthOptM(k => attrs.get(k))
  def penwidthOptM(attrs: String => Option[String]): Option[Double] =
    attrs("penwidth").flatMap(_.toDoubleOption).orElse {
      attrs("style").flatMap { st =>
        var pw: Option[Double] = None
        st.split(",").iterator.map(_.trim).foreach { item =>
          if item == "bold" then pw = Some(2.0)
          else if item.startsWith("setlinewidth(") && item.endsWith(")") then
            pw = item.stripPrefix("setlinewidth(").stripSuffix(")").trim.toDoubleOption.orElse(Some(0.0))
        }
        pw
      }
    }

  private val PointsPerInch = 72.0
  private val Gap           = 4.0  // const.h GAP (YPAD = 2*GAP)
  private val DefFontSize   = 14.0 // DEFAULT_FONTSIZE

  /** `POINTS(a) = ROUND(a·72)` — round half away from zero (geom.h). */
  private def points(inches: Double): Double =
    val x = inches * PointsPerInch
    if x >= 0 then math.floor(x + 0.5) else math.ceil(x - 0.5)

  /** strtod-style leading-numeric parse: the longest numeric prefix (after
    * optional whitespace), or None. Mirrors gv's `strtod`/`sscanf("%lf")` on
    * `nodesep`/`ranksep` (so `"0.5 equally"` yields `0.5`). */
  private def leadingDouble(s: String): Option[Double] =
    "^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)".r
      .findPrefixMatchOf(s).flatMap(_.group(1).toDoubleOption)

  /** `GD_nodesep` (input.c): `POINTS(late_double("nodesep", 0.25, min 0.02))`.
    * Default 18pt. Shared by [[XCoord]]/[[Spline]] separation + Splinesep. */
  def nodeSepPt(g: RGraph): Double =
    val v = g.rootAttrs.get("nodesep").flatMap(leadingDouble) match
      case Some(x) => math.max(0.02, x)
      case None    => 0.25
    points(v)

  /** Plain virtual node half-width — `incr_width` = 1 + nodesep/2
    * (class2.c plain_vnode). Shared by [[XCoord]] and [[Spline]].
    *
    * `int width = GD_nodesep(g) / 2` is C INTEGER division, and GD_nodesep is
    * itself an int (`POINTS` rounds), so an ODD nodesep truncates: 191 sets
    * `nodesep=0.35` ⇒ 25 ⇒ 12, not 12.5. Invisible at the 18pt default (9
    * either way), which is why no other corpus file caught it. */
  def virtualHalfPt(g: RGraph): Double = 1.0 + (nodeSepPt(g).toInt / 2).toDouble

  /** `selfRightSpace(e)` (splines.c:1146): the extra RIGHT-side space a
    * self-edge needs — `SELF_EDGE_SIZE` (18) plus its label width (`dimen.y`
    * under flip), unless a port routes it away from the right (any LEFT-side
    * port, or both ports on the same TOP/BOTTOM side). `make_LR_constraints`
    * (position.c:264) inflates `ND_rw` by the SUM over a node's self-edges
    * before the x solve (the original rw is parked in `ND_mval`), and
    * `dot_compute_bb` sees the inflated value too. */
  /** The resolved gv `port` struct for one end of an edge — the portless
    * `Center` when there is no port. A NAMED record/HTML port carries a
    * `side` bitmap of the node edges its field box lies along (`html_port`
    * fills `sides`, and compass `_` takes it wholesale), which is what
    * `selfRightSpace` tests; deriving sides from the COMPASS alone leaves a
    * named port at 0 and silently flips the test's outcome. */
  private def gvPortOf(g: RGraph, nodeId: String,
                       p: Option[org.jpablo.graphexplorer.graphviz.dotlang.Port]): PortAnchor.GvPort =
    val gp =
      for
        port <- p
        n    <- g.nodes.find(_.id == nodeId)
        res  <- PortAnchor.gvRecordPort(n, g, port).orElse(PortAnchor.gvHtmlPort(n, g, port))
      yield res
    gp.getOrElse(PortAnchor.GvPort.center)

  def selfRightSpace(e: REdge, g: RGraph): Double =
    // geom.h side bits — BOTTOM/RIGHT/TOP/LEFT, not the compass order.
    val BOTTOM = 1; val RIGHT = 2; val TOP = 4; val LEFT = 8
    val tp = gvPortOf(g, e.tail, e.tailPort)
    val hp = gvPortOf(g, e.head, e.headPort)
    // splines.c:1146 verbatim: an undefined pair, or neither side on the LEFT
    // and not the same TOP/BOTTOM side on both ends.
    val onRight =
      (!tp.defined && !hp.defined) ||
        ((tp.side & LEFT) == 0 && (hp.side & LEFT) == 0 &&
          (tp.side != hp.side || (tp.side & (TOP | BOTTOM)) == 0))
    if !onRight then 0.0
    else
      val lbl = e.attrs.get("label").filter(_.nonEmpty).map { _ =>
        val (w, h) = edgeLabelDim(e, g)
        if Rank.flip(g) then h else w
      }.getOrElse(0.0)
      18.0 + lbl // SELF_EDGE_SIZE

  /** Σ selfRightSpace over a node's self-edges — the `ND_rw` inflation the
    * whole position phase (aux x solve + graph bbox) sees. */
  def selfRightSpaceOf(g: RGraph, nodeId: String): Double =
    g.edges.iterator
      .filter(e => e.tail == nodeId && e.head == nodeId)
      .map(selfRightSpace(_, g)).sum

  /** `GD_ranksep` base (input.c): `POINTS(max(0.02, %lf) | 0.5)`. Default 36pt. */
  def rankSepBasePt(g: RGraph): Double =
    val v = g.rootAttrs.get("ranksep").flatMap(leadingDouble) match
      case Some(x) => math.max(0.02, x)
      case None    => 0.5
    points(v)

  /** `ranksep="… equally"` ⇒ `GD_exact_ranksep`: ranks are re-spaced to a
    * uniform `maxht` after the min-gap assignment (set_ycoords). */
  private def exactRanksep(g: RGraph): Boolean =
    g.rootAttrs.get("ranksep").exists(_.contains("equally"))

  /** `edgelabel_ranks` compensates the doubled ranks by halving ranksep:
    * `GD_ranksep = (GD_ranksep + 1) / 2` with int `GD_ranksep` (rank.c:100). */
  private def rankSep(g: RGraph): Double =
    val base = rankSepBasePt(g).toInt
    if Rank.hasEdgeLabel(g) then ((base + 1) / 2).toDouble else base.toDouble

  /** `GD_ranksep(g)` as dotsplines sees it (post edge-label halving) — the
    * `vspace` used by `make_flat_edge` for a non-adjacent flat edge at the top
    * rank (`r == 0`); interior ranks derive `vspace` from the actual gap. */
  def flatVspaceTopRank(g: RGraph): Double = rankSep(g)

  /** `GD_ranksep(g)` itself — an INT in gv, so callers that halve it again
    * (`beginpath`/`endpath`'s FLATEDGE BOTTOM branch: `GD_ranksep/2`) must use
    * C integer division. 191: ranksep 0.85 ⇒ 61 ⇒ (61+1)/2 = 31 ⇒ 31/2 = 15. */
  def gdRanksep(g: RGraph): Int = rankSep(g).toInt


  /** Edge-label (width, height) in pt. **HTML-aware**: an HTML label (`<...>`)
    * is parsed and measured by the table/text layout — measuring the raw markup
    * as plain text would count the `<b>`/`</b>` tags and grossly inflate the
    * label-vnode width (05: `<b>html</b> label` was 105.8 pt instead of ~52). */
  private[graphviz] def edgeLabelDim(e: REdge, g: RGraph): (Double, Double) =
    val lbl = e.attrs.getOrElse("label", "")
    val fs  = e.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
    val fn  = e.attrs.getOrElse("fontname", "Times")
    def plain = (NodeSize.labelWidthPt(lbl, fs, fn, g.name.getOrElse("")),
                 NodeSize.labelHeightPt(lbl, fs, g.name.getOrElse("")))
    if e.attrs.isHtml("label") then
      HtmlParser.parse(lbl).map(h => HtmlLayout.size(h, fs, fn, g.images)).getOrElse(plain)
    else plain

  /** Rank-axis coordinate in points for every real node. For TB this is the
    * final `y` (rank 0 = largest = top); for LR it is pre-rotation and not
    * yet comparable to `plain` (LR rotation handled with rankdir later).
    */
  /** (real-node ranks, y-per-rank in points). Virtual nodes share their
    * rank's y, so callers position them via `yOf(rank)`.
    */
  def rankY(g: RGraph): (Map[String, Int], Map[Int, Pt]) =
    val yi = yInfo(g)
    (yi.ranks, yi.yOf)

  /** Full `set_ycoords` result (position.c). `ht1`/`ht2` are the per-rank
    * half-heights **after** cluster inflation (`clust_ht` — label band +
    * CL_OFFSET margins); `pht` is the primitive node-scan value. `rootHt1`/
    * `rootHt2` are the root graph's `GD_ht1`/`GD_ht2` (they set the bbox
    * bottom/top); `clHt1`/`clHt2` index [[Cluster.clusters]]. `yOf` is the
    * FINAL rank-centre y: gv assigns bottom-up then `translate_drawing`
    * shifts by −bb.LL — the shift is baked in here (`rootHt1 − ht1(maxR)`).
    */
  final case class YInfo(
      ranks:   Map[String, Int],
      yOf:     Map[Int, Pt],
      ht1:     Map[Int, Double],
      ht2:     Map[Int, Double],
      rootHt1: Double,
      rootHt2: Double,
      clHt1:   Vector[Double],
      clHt2:   Vector[Double]
  ) derives CanEqual

  private val yInfoMemo = GraphMemo[YInfo]()
  def yInfo(g: RGraph): YInfo = yInfoMemo(g)(yInfoImpl(g))
  private def yInfoImpl(g: RGraph): YInfo =
    val ranks = Rank.assign(g)
    if ranks.isEmpty then
      return YInfo(Map.empty, Map.empty, Map.empty, Map.empty, 0, 0, Vector.empty, Vector.empty)
    // `abomination` (flat.c:184, and gv really does call it that): a LABELLED
    // NON-ADJACENT flat edge hangs its label vnode one rank ABOVE its own, so
    // an edge on the top rank needs a rank that does not exist yet. gv
    // prepends one and decrements GD_minrank; the new rank starts at
    // ht1 = ht2 = 1 and holds nothing but the label.
    //
    // `Order` already emits those label vnodes at their true rank (rank −1 for
    // a top-rank flat edge), so the span comes from there rather than from a
    // re-derivation here — deciding it locally would need the mincross order
    // for the ADJACENCY half of gv's test. Without this, every rank below the
    // real minimum was simply missing from `yOf`, and the first spline through
    // such a label died on `key not found: -1`.
    val minR = math.min(ranks.values.min, Order.order(g).order.keys.minOption.getOrElse(Int.MaxValue))
    val maxR = ranks.values.max

    val cls     = Cluster.clusters(g)
    val clustOf = if cls.isEmpty then Map.empty[String, Int] else Cluster.clustOf(g)
    val ClOffset = 8.0 // const.h CL_OFFSET (cluster margin)
    val clHt1   = Array.fill(cls.length)(0.0)
    val clHt2   = Array.fill(cls.length)(0.0)
    var rootHt1 = 0.0
    var rootHt2 = 0.0

    // ── node scan (set_ycoords:741) ──────────────────────────────────────
    // pht = primitive per-rank half-height; the same scan seeds each node's
    // innermost cluster ht1/ht2 (± CL_OFFSET) or, for cluster-less nodes at
    // the graph extremes, the root's GD_ht1/GD_ht2 (yoff = 0).
    val pht = mutable.Map.empty[Int, Double].withDefaultValue(0.0)
    def scanNode(name: String, r: Int, h: Double): Unit =
      if h > pht(r) then pht(r) = h
      clustOf.get(name) match
        case Some(ci) =>
          if r == cls(ci).minRank && h + ClOffset > clHt2(ci) then clHt2(ci) = h + ClOffset
          if r == cls(ci).maxRank && h + ClOffset > clHt1(ci) then clHt1(ci) = h + ClOffset
        case None =>
          if r == minR && h > rootHt2 then rootHt2 = h
          if r == maxR && h > rootHt1 then rootHt1 = h
    g.nodes.foreach { n =>
      NodeSize.layoutSize(n, g).foreach(sz => scanNode(n.id, ranks(n.id), sz.halfHeightPt.value))
    }

    // Edge label_vnode (make_chain): a labelled edge seats a label-sized
    // virtual node at the mid rank `(rank(tail)+rank(head))/2`; its
    // half-height drives that (odd, doubled) rank's Y spacing. Plain chain
    // vnodes (ND_ht=1 ⇒ half 0.5) fill the intermediate ranks. A vnode's
    // rank is strictly inside its endpoints' band, so it can never sit at a
    // cluster/root extreme — only `pht` is affected.
    g.edges.iterator.filter(e => e.tail != e.head).zipWithIndex.foreach { (e, dIdx) =>
      for rt <- ranks.get(e.tail); rh <- ranks.get(e.head) do
        var r = math.min(rt, rh) + 1
        while r < math.max(rt, rh) do
          scanNode(LayoutNode.Virtual(dIdx, r).name, r, 0.5)
          r += 1
        // labelled edge ⇒ the mid rank's virtual is the label box. Its
        // rank-axis extent (ND_ht) is dimen.y (label height) for TB, but
        // dimen.x (label width) for a flipped graph (class2.c label_vnode).
        // A FLAT labelled edge (rt == rh) places its label above the edge
        // (makeSimpleFlatLabels), NOT as a rank vnode — it must not inflate
        // its rank's height.
        if rt != rh then
          e.attrs.get("label").filter(_.nonEmpty).foreach { _ =>
            val mid    = (rt + rh) / 2
            val (w, h) = edgeLabelDim(e, g)
            val ht     = if Rank.flip(g) then w else h // rank-axis extent (flip ⇒ width)
            scanNode(LayoutNode.Virtual(dIdx, mid).name, mid, ht / 2.0)
          }
    }

    // ── clust_ht (position.c:680): postorder cluster-ht accumulation ─────
    // Children inflate parents (+margin at shared extremes); a label adds
    // its padded border to the cluster's top; each cluster then pushes its
    // ht into the global per-rank ht1/ht2.
    val ht1 = mutable.Map.empty[Int, Double].withDefaultValue(0.0)
    val ht2 = mutable.Map.empty[Int, Double].withDefaultValue(0.0)
    pht.foreach { (r, h) => ht1(r) = h; ht2(r) = h }
    if cls.nonEmpty then
      val depth = cls.zipWithIndex.map { (c, i) =>
        var d = 0; var p = c.parent
        while p >= 0 do { d += 1; p = cls(p).parent }
        i -> d
      }.toMap
      cls.indices.sortBy(i => -depth(i)).foreach { ci =>
        val c = cls(ci)
        Cluster.childrenOf(g, ci).foreach { cc =>
          if cls(cc).maxRank == c.maxRank && clHt1(cc) + ClOffset > clHt1(ci) then
            clHt1(ci) = clHt1(cc) + ClOffset
          if cls(cc).minRank == c.minRank && clHt2(cc) + ClOffset > clHt2(ci) then
            clHt2(ci) = clHt2(cc) + ClOffset
        }
        // clust_ht (position.c:711): only the UNFLIPPED root puts the label
        // band on the rank axis — a flipped one reserves it on X instead
        // (contain_nodes' side borders), so this whole branch is skipped.
        if c.hasLabel && !Rank.flip(g) then
          clHt1(ci) += c.borderBottomY // labelloc=b
          clHt2(ci) += c.borderTopY    // labelloc=t (the cluster default)
        if clHt2(ci) > ht2(c.minRank) then ht2(c.minRank) = clHt2(ci)
        if clHt1(ci) > ht1(c.maxRank) then ht1(c.maxRank) = clHt1(ci)
      }
      // root level (clust_ht(root)): top-level clusters at the graph
      // extremes push GD_ht1/GD_ht2 of the root (margin = CL_OFFSET).
      Cluster.childrenOf(g, -1).foreach { cc =>
        if cls(cc).maxRank == maxR && clHt1(cc) + ClOffset > rootHt1 then rootHt1 = clHt1(cc) + ClOffset
        if cls(cc).minRank == minR && clHt2(cc) + ClOffset > rootHt2 then rootHt2 = clHt2(cc) + ClOffset
      }

    val gLabelPad = graphLabelPad(g)
    val labelTop  = graphLabelTop(g)

    // A bottom-anchored root label shifts the whole drawing up by its
    // reserved space; a top-anchored one only extends the bbox upward and
    // leaves node Y unchanged (bbox is the writers' concern, not gated here).
    // set_ycoords:783: rank spacing = max(primitive-node sep, cluster sep).
    val rs  = rankSep(g)
    val yOf = mutable.Map.empty[Int, Double]
    yOf(maxR) = ht1(maxR) + (if labelTop then 0.0 else gLabelPad)
    var maxht = 0.0
    var r = maxR - 1
    while r >= minR do
      val d0 = pht(r + 1) + pht(r) + rs           // prim node sep
      val d1 = ht2(r + 1) + ht1(r) + ClOffset      // cluster sep
      val delta = math.max(d0, d1)
      if delta > maxht then maxht = delta
      yOf(r) = yOf(r + 1) + delta
      r -= 1
    // ── adjustRanks (position.c:628), set_ycoords:797 ────────────────────
    // A cluster label on a FLIPPED drawing occupies RANK space, because the
    // label box rotates with the graph: its (padded) WIDTH has to fit between
    // the cluster's top and bottom ranks. clust_ht deliberately skips the
    // label band under flip, so nothing above reserved it — this pass walks
    // the cluster tree, finds each shortfall, and spreads it across the ranks
    // (half below, half above), pushing the ranks outside the cluster along.
    // gv runs it here, between the initial y assignment and `ranksep=equally`.
    if cls.exists(_.hasLabel) && Rank.flip(g) then
      // adjustSimple: `delta` extra rank-axis room for cluster `ci`, split
      // bottom/top; ranks inside the cluster shift up by `delbottom`, ranks
      // ABOVE it (lower index) by `deltop` on top of that.
      def adjustSimple(ci: Int, delta: Double, marginTotal: Double): Unit =
        val mnR2 = cls(ci).minRank
        val mxR2 = cls(ci).maxRank
        val bottom    = (delta + 1) / 2 // C doubles — not integer division
        val delbottom = clHt1(ci) + bottom - (ht1(mxR2) - marginTotal)
        var rr = mxR2
        if delbottom > 0 then
          while rr >= mnR2 do { yOf.get(rr).foreach(y => yOf(rr) = y + delbottom); rr -= 1 }
        val deltop =
          clHt2(ci) + (delta - bottom) + (if delbottom > 0 then delbottom else 0.0) -
            (ht2(mnR2) - marginTotal)
        if deltop > 0 then
          rr = mnR2 - 1
          while rr >= minR do { yOf.get(rr).foreach(y => yOf(rr) = y + deltop); rr -= 1 }
        clHt2(ci) += delta - bottom
        clHt1(ci) += bottom
      // adjustRanks: children first (their growth feeds the parent), then the
      // parent's own label check. Note the root's margin is 0 here, unlike
      // clust_ht's CL_OFFSET.
      def adjustRanks(ci: Int, marginTotal: Double): Unit =
        val margin     = if ci < 0 then 0.0 else ClOffset
        val (mnR2, mxR2) = if ci < 0 then (minR, maxR) else (cls(ci).minRank, cls(ci).maxRank)
        var a1 = if ci < 0 then rootHt1 else clHt1(ci)
        var a2 = if ci < 0 then rootHt2 else clHt2(ci)
        Cluster.childrenOf(g, ci).foreach { cc =>
          adjustRanks(cc, margin + marginTotal)
          if cls(cc).maxRank == mxR2 && clHt1(cc) + margin > a1 then a1 = clHt1(cc) + margin
          if cls(cc).minRank == mnR2 && clHt2(cc) + margin > a2 then a2 = clHt2(cc) + margin
        }
        if ci < 0 then { rootHt1 = a1; rootHt2 = a2 } else { clHt1(ci) = a1; clHt2(ci) = a2 }
        if ci >= 0 && cls(ci).hasLabel then
          // lht = the rotated label's rank-axis extent = its PADDED WIDTH
          // (GD_border[LEFT/RIGHT].y under flip — the x/y swap again).
          val lht   = math.max(cls(ci).borderLeftY(true), cls(ci).borderRightY(true))
          val rht   = yOf(mnR2) - yOf(mxR2)
          val delta = lht - (rht + a1 + a2)
          if delta > 0 then adjustSimple(ci, delta, marginTotal)
        if ci >= 0 then
          if clHt2(ci) > ht2(mnR2) then ht2(mnR2) = clHt2(ci)
          if clHt1(ci) > ht1(mxR2) then ht1(mxR2) = clHt1(ci)
      adjustRanks(-1, 0.0)
      // the shifts above invalidate maxht; only `ranksep=equally` reads it.
      if exactRanksep(g) then
        maxht = 0.0
        var prev = yOf(maxR)
        r = maxR - 1
        while r >= minR do
          val cur = yOf(r)
          if cur - prev > maxht then maxht = cur - prev
          prev = cur
          r -= 1

    // ranksep="… equally" (GD_exact_ranksep): re-space every rank uniformly
    // to the largest gap (set_ycoords:817). No effect when all gaps equal.
    if exactRanksep(g) then
      r = maxR - 1
      while r >= minR do { yOf(r) = yOf(r + 1) + maxht; r -= 1 }

    // translate_drawing (postproc.c): the drawing shifts by −bb.LL, where
    // bb.LL.y = y(maxR) − GD_ht1(root). Bake the shift in (0 without
    // clusters: rootHt1 == ht1(maxR) by construction).
    val shiftY = rootHt1 - ht1(maxR)
    (YInfo(
      ranks,
      yOf.iterator.map((rr, y) => rr -> Pt(y + shiftY)).toMap,
      ht1.toMap.withDefaultValue(0.0),
      ht2.toMap.withDefaultValue(0.0),
      rootHt1, rootHt2,
      clHt1.toVector, clHt2.toVector
    ))

  def yCoords(g: RGraph): Map[String, Pt] =
    val (ranks, yOf) = rankY(g)
    ranks.view.mapValues(yOf).toMap

  // ── root graph label geometry (`do_graph_label`) ─────────────────────────
  // gv computes this once during layout and stores GD_label; the port derives
  // it here — the single home — and both Coord's node shift and the writers'
  // bbox reservation read these accessors.

  /** Rank-axis space the root label reserves = label box + YPAD (2*GAP);
    * 0 with no label. */
  def graphLabelPad(g: RGraph): Double =
    g.rootAttrs.get("label").filter(_.nonEmpty).map { lbl =>
      val fs = g.rootAttrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
      NodeSize.labelHeightPt(lbl, fs, g.name.getOrElse("")) + 2.0 * Gap
    }.getOrElse(0.0)

  /** `labelloc=t` — the root label sits above the drawing (default: below). */
  def graphLabelTop(g: RGraph): Boolean =
    g.rootAttrs.get("labelloc").exists(_.startsWith("t"))

  /** Root-label PADded width — `dimen.x + XPAD` (4*GAP), the cross-axis size
    * gv_postprocess widens the bb to when the label outgrows the drawing.
    * 0 with no label. */
  def graphLabelPaddedWidth(g: RGraph): Double =
    g.rootAttrs.get("label").filter(_.nonEmpty).map { lbl =>
      val fs = g.rootAttrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
      val fn = g.rootAttrs.getOrElse("fontname", "Times")
      NodeSize.labelWidthPt(lbl, fs, fn, g.name.getOrElse("")) + 4.0 * Gap
    }.getOrElse(0.0)

  /** Edge-label virtual-node `ND_rw` (vnode name → order-axis half-width pt) for
    * `make_LR_constraints`/spline bounds. class2.c `label_vnode`: `ND_lw =
    * nodesep`, and `ND_rw = dimen.x` (label width) for TB, but `dimen.y` (label
    * height) for a flipped graph (LR/RL) — the label box rotates with the
    * drawing. The vnode name matches `Order`'s `Virtual(dedgeIdx, midRank)`. */
  private val labelVWMemo = GraphMemo[Map[String, Double]]()
  def labelVnodeWidths(g: RGraph): Map[String, Double] = labelVWMemo(g)(labelVnodeWidthsImpl(g))
  private def labelVnodeWidthsImpl(g: RGraph): Map[String, Double] =
    val ranks = Rank.assign(g)
    val flip  = Rank.flip(g)
    g.edges.iterator.filter(e => e.tail != e.head).zipWithIndex.flatMap { (e, dIdx) =>
      e.attrs.get("label").filter(_.nonEmpty).flatMap { lbl =>
        for
          rt <- ranks.get(e.tail)
          rh <- ranks.get(e.head) if rt != rh
        yield
          val (w, h) = edgeLabelDim(e, g)
          val rw     = if flip then h else w // order-axis extent (flip ⇒ height)
          LayoutNode.Virtual(dIdx, (rt + rh) / 2).name -> rw
      }
    }.toMap

  /** `flat_node` (flat.c:159) sizes a flat-edge label vnode from the label's
    * dimen SWAPped under flip: `lw = rw = dimen.x/2`, `ht = dimen.y`. So under
    * LR/BT a one-line label gives half-width `height/2` (5.4pt at 14pt) and
    * rank-axis extent `width` — the mirror of a chain label vnode, which is
    * asymmetric (`lw = nodesep`, `rw = height`). Keyed by dedge index.
    * Returns (halfWidth, ht) in CANONICAL axes. */
  def flatLabelDim(e: REdge, g: RGraph): (Double, Double) =
    val (w, h) = edgeLabelDim(e, g)
    val (dx, dy) = if Rank.flip(g) then (h, w) else (w, h)
    (dx / 2.0, dy)

end Coord
