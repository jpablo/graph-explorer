package org.jpablo.graphexplorer.graphviz.html

import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics

/** Sizing for parsed HTML-like labels (Graphviz `size_html_txt` /
  * `size_html_tbl`, htmltable.c). Returns the content box in points; the caller
  * ([[org.jpablo.graphexplorer.graphviz.layout.NodeSize]]) then feeds it through
  * the same `poly_init` PAD + shape-fit as a regular label, so a plain-text HTML
  * label sizes byte-identically to the equivalent quoted label. */
object HtmlLayout:

  private val LineSpacing = 1.20 // const.h LINESPACING

  /** Resolved per-run font metrics (size + bold/italic → FontMetrics flags). */
  def itemWidth(it: HtmlItem, baseSize: Double, baseName: String): Double =
    val sz  = it.font.size.getOrElse(baseSize)
    val nm  = it.font.name.getOrElse(baseName)
    val low = nm.toLowerCase
    val bld = it.font.bold || low.contains("bold")
    val itl = it.font.italic || low.contains("italic") || low.contains("oblique")
    sz * FontMetrics.estimateTextWidth1pt(nm, it.str, bld, itl)

  private def itemSize(it: HtmlItem, baseSize: Double): Double =
    it.font.size.getOrElse(baseSize) * LineSpacing

  /** Per-line width + height (`size_html_txt`: line width = Σ item widths,
    * line height = max item `sz.y`). Empty lines use `(int)(fs·LINESPACING)`. */
  def lineMetrics(span: HtmlSpan, baseSize: Double, baseName: String): (Double, Double) =
    if span.items.isEmpty then (0.0, (baseSize * LineSpacing).toInt.toDouble)
    else
      val w = span.items.map(itemWidth(_, baseSize, baseName)).sum
      val h = span.items.map(itemSize(_, baseSize)).max
      (w, h)

  /** One laid-out text line: width, its height contribution (`lsize`) and the
    * baseline advance from the previous baseline (`lfsize`). */
  final case class HtmlLine(width: Double, lsize: Double, lfsize: Double) derives CanEqual
  /** `size_html_txt`'s full result: box + per-line baselines + the `simple`
    * flag (which also picks the emit-side `yoffset_centerline`). */
  final case class HtmlTextLayout(width: Double, height: Double, simple: Boolean,
                                  lines: Vector[HtmlLine]) derives CanEqual

  /** Full `size_html_txt` (htmltable.c:932) transcription.
    *
    * `simple` (≤1 item per line, no style flags, uniform font) keeps the
    * familiar LINESPACING line heights; a NON-simple block (any `<B>`/mixed
    * fonts…) uses the RAW max font size per line (`lsize = mxfsize`) — 3
    * lines at 14pt are 42pt tall, not 50.4 — with baselines advanced by
    * `mxfsize − maxoffset` (first) / `mxfsize + ysize − curbline − maxoffset`.
    * A single-line block is always `mxysize` tall. `maxoffset` = the
    * textspan `yoffset_centerline` = 0.1·fontsize (textspan.c:43). */
  def textLayout(block: HtmlText, baseSize: Double, baseName: String): HtmlTextLayout =
    val spans = block.spans
    def flagged(f: HtmlFont): Boolean =
      f.bold || f.italic || f.underline || f.strike || f.sub || f.sup
    val allItems = spans.flatMap(_.items)
    val simple =
      spans.forall(_.items.sizeIs <= 1) && allItems.forall(it => !flagged(it.font)) &&
        allItems.map(it => (it.font.size.getOrElse(baseSize), it.font.name.getOrElse(baseName)))
          .distinct.sizeIs <= 1
    var ysize    = 0.0
    var curbline = 0.0
    var xsize    = 0.0
    var firstMxy = 0.0
    val lines = Vector.newBuilder[HtmlLine]
    spans.zipWithIndex.foreach { (sp, i) =>
      val width     = sp.items.map(itemWidth(_, baseSize, baseName)).sum
      val mxfsize   = sp.items.map(it => it.font.size.getOrElse(baseSize)).maxOption.getOrElse(0.0)
      val mxysize   = sp.items.map(it => it.font.size.getOrElse(baseSize) * LineSpacing).maxOption.getOrElse(0.0)
      val maxoffset = sp.items.map(it => 0.1 * it.font.size.getOrElse(baseSize)).maxOption.getOrElse(0.0)
      if i == 0 then firstMxy = mxysize
      val (lsize, lfsize) =
        if simple then (mxysize, if i == 0 then mxfsize else mxysize)
        else (mxfsize, if i == 0 then mxfsize - maxoffset
                       else mxfsize + ysize - curbline - maxoffset)
      curbline += lfsize
      ysize += lsize
      xsize = math.max(xsize, width)
      lines += HtmlLine(width, lsize, lfsize)
    }
    val height = if spans.length == 1 then firstMxy else ysize
    HtmlTextLayout(xsize, height, simple, lines.result())

  /** Text-block box (width = widest line; height per [[textLayout]]). */
  def textSize(block: HtmlText, baseSize: Double, baseName: String): (Double, Double) =
    val t = textLayout(block, baseSize, baseName)
    (t.width, t.height)

  /** Overall content box for a label (text, table, or image). An image's box is
    * its drawn size (natural × 72/96); an unknown `src` (no dimensions given)
    * contributes 0, matching `size_html_img`'s missing-file fallback. */
  def size(label: HtmlLabel, baseSize: Double, baseName: String,
           imgs: ImageDim.Table = ImageDim.empty): (Double, Double) =
    label match
      case HtmlLabel.Text(block)   => textSize(block, baseSize, baseName)
      case HtmlLabel.Table(tbl)    => HtmlTableLayout.size(tbl, baseSize, baseName, imgs)
      case HtmlLabel.Image(src, _) => imgs.get(src).map(_.drawn).getOrElse((0.0, 0.0))

end HtmlLayout
