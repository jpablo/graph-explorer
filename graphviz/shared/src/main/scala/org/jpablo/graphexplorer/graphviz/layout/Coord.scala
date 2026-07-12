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

  /** `GD_ranksep` base (input.c): `POINTS(max(0.02, %lf) | 0.5)`. Default 36pt. */
  private def rankSepBasePt(g: RGraph): Double =
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

  /** Edge-label (width, height) in pt. **HTML-aware**: an HTML label (`<...>`)
    * is parsed and measured by the table/text layout — measuring the raw markup
    * as plain text would count the `<b>`/`</b>` tags and grossly inflate the
    * label-vnode width (05: `<b>html</b> label` was 105.8 pt instead of ~52). */
  private[layout] def edgeLabelDim(e: REdge, g: RGraph): (Double, Double) =
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
    val minR = ranks.values.min
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
        if c.hasLabel && !Rank.flip(g) then
          clHt2(ci) += c.borderTopY // labelloc=t default (BOTTOM border = 0)
        if clHt2(ci) > ht2(c.minRank) then ht2(c.minRank) = clHt2(ci)
        if clHt1(ci) > ht1(c.maxRank) then ht1(c.maxRank) = clHt1(ci)
      }
      // root level (clust_ht(root)): top-level clusters at the graph
      // extremes push GD_ht1/GD_ht2 of the root (margin = CL_OFFSET).
      Cluster.childrenOf(g, -1).foreach { cc =>
        if cls(cc).maxRank == maxR && clHt1(cc) + ClOffset > rootHt1 then rootHt1 = clHt1(cc) + ClOffset
        if cls(cc).minRank == minR && clHt2(cc) + ClOffset > rootHt2 then rootHt2 = clHt2(cc) + ClOffset
      }

    // Root graph label reserves space on its labelloc side (`do_graph_label`,
    // default = bottom for the root); height = label box + YPAD (2*GAP).
    val gLabelPad =
      g.rootAttrs.get("label").filter(_.nonEmpty).map { lbl =>
        val fs = g.rootAttrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(DefFontSize)
        NodeSize.labelHeightPt(lbl, fs, g.name.getOrElse("")) + 2.0 * Gap
      }.getOrElse(0.0)
    val labelTop = g.rootAttrs.get("labelloc").exists(_.startsWith("t"))

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

  /** Edge-label virtual-node `ND_rw` (vnode name → order-axis half-width pt) for
    * `make_LR_constraints`/spline bounds. class2.c `label_vnode`: `ND_lw =
    * nodesep`, and `ND_rw = dimen.x` (label width) for TB, but `dimen.y` (label
    * height) for a flipped graph (LR/RL) — the label box rotates with the
    * drawing. The vnode name matches `Order`'s `Virtual(dedgeIdx, midRank)`. */
  def labelVnodeWidths(g: RGraph): Map[String, Double] =
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

end Coord
