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
  private val RankSep       = 36.0 // POINTS(DEFAULT_RANKSEP = 0.5in)
  private val Gap           = 4.0  // const.h GAP (YPAD = 2*GAP)
  private val DefFontSize   = 14.0 // DEFAULT_FONTSIZE

  /** `edgelabel_ranks` compensates the doubled ranks by halving ranksep:
    * `GD_ranksep = (GD_ranksep + 1) / 2` with int `GD_ranksep` ⇒ 36 → 18. */
  private def rankSep(g: RGraph): Double =
    if Rank.hasEdgeLabel(g) then ((RankSep.toInt + 1) / 2).toDouble else RankSep

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
  private val rankYMemo = GraphMemo[(Map[String, Int], Map[Int, Pt])]()
  def rankY(g: RGraph): (Map[String, Int], Map[Int, Pt]) = rankYMemo(g)(rankYImpl(g))
  private def rankYImpl(g: RGraph): (Map[String, Int], Map[Int, Pt]) =
    val ranks = Rank.assign(g)
    if ranks.isEmpty then return (Map.empty, Map.empty)
    val minR = ranks.values.min
    val maxR = ranks.values.max

    val halfHt = mutable.Map.empty[Int, Double].withDefaultValue(0.0)
    g.nodes.foreach { n =>
      val r = ranks(n.id)
      NodeSize.layoutSize(n, g).foreach { sz =>
        val h = sz.halfHeightPt.value
        if h > halfHt(r) then halfHt(r) = h
      }
    }

    // Edge label_vnode (make_chain): a labelled edge seats a label-sized
    // virtual node at the mid rank `(rank(tail)+rank(head))/2`; its
    // half-height drives that (odd, doubled) rank's Y spacing.
    g.edges.foreach { e =>
      if e.tail != e.head then
        for rt <- ranks.get(e.tail); rh <- ranks.get(e.head) do
          // plain virtual nodes (ND_ht=1 ⇒ half 0.5) occupy every intermediate
          // rank of a spanning/doubled edge — they set that rank's min half-ht.
          var r = math.min(rt, rh) + 1
          while r < math.max(rt, rh) do
            if 0.5 > halfHt(r) then halfHt(r) = 0.5
            r += 1
          // labelled edge ⇒ the mid rank's virtual is the label box. Its
          // rank-axis extent (ND_ht) is dimen.y (label height) for TB, but
          // dimen.x (label width) for a flipped graph (class2.c label_vnode).
          e.attrs.get("label").filter(_.nonEmpty).foreach { _ =>
            val mid    = (rt + rh) / 2
            val (w, h) = edgeLabelDim(e, g)
            val ht     = if Rank.flip(g) then w else h // rank-axis extent (flip ⇒ width)
            val h2     = ht / 2.0
            if h2 > halfHt(mid) then halfHt(mid) = h2
          }
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
    val rs  = rankSep(g)
    val yOf = mutable.Map.empty[Int, Double]
    yOf(maxR) = halfHt(maxR) + (if labelTop then 0.0 else gLabelPad)
    var r = maxR - 1
    while r >= minR do
      yOf(r) = yOf(r + 1) + halfHt(r + 1) + halfHt(r) + rs
      r -= 1

    (ranks, yOf.iterator.map((r, y) => r -> Pt(y)).toMap)

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
