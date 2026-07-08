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

  /** Text-block box: width = widest line, height = Σ line heights. */
  def textSize(block: HtmlText, baseSize: Double, baseName: String): (Double, Double) =
    val ls     = block.spans.map(lineMetrics(_, baseSize, baseName))
    val width  = ls.map(_._1).maxOption.getOrElse(0.0)
    val height = ls.map(_._2).sum
    (width, height)

  /** Overall content box for a label (text or — later — table). */
  def size(label: HtmlLabel, baseSize: Double, baseName: String): (Double, Double) =
    label match
      case HtmlLabel.Text(block) => textSize(block, baseSize, baseName)
      case HtmlLabel.Table(tbl)  => HtmlTableLayout.size(tbl, baseSize, baseName)

end HtmlLayout
