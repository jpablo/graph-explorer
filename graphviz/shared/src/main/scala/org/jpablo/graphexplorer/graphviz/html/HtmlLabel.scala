package org.jpablo.graphexplorer.graphviz.html

/** Parsed HTML-like label (Graphviz `htmllabel_t`, lib/common/htmltable.h).
  *
  * A label is EITHER a text block (paragraphs of styled runs) OR a table.
  * Modelled: text with `<b>/<i>/<u>/<s>/<sub>/<sup>/<font>` styling, `<br/>`
  * line breaks, `<table>/<tr>/<td>` with colspan/rowspan and nesting, cell
  * ports (incl. compass), `<img>`, `<hr/>`/`<vr/>` rules, and bgcolor
  * (incl. gradients, rendered by Svg).
  */
enum HtmlLabel derives CanEqual:
  case Text(block: HtmlText)
  case Table(tbl: HtmlTable)
  /** An `<IMG SRC="…" SCALE="…"/>` (Graphviz `htmlimg_t`). The image's natural
    * size is not in the markup — it comes from the external image-dimension
    * table ([[ImageDim]], mirroring viz-js's `images` render option). */
  case Image(src: String, scale: Option[String])

/** Natural size (points) of a referenced image, keyed by `src` in an
  * [[HtmlImages]] map. Mirrors the dimensions viz-js's `images` option supplies:
  * graphviz can't read the file, so the caller states the size. The *drawn* box
  * is this size × 72/96 (`gvusershape_size` DPI scaling). */
final case class ImageDim(w: Double, h: Double) derives CanEqual:
  /** Drawn box size (points): natural size × 72/96, truncated to whole points.
    * `size_html_img` stores the result in an integer `box`, so `(int)(50×0.75)`
    * = 37 — the truncation is load-bearing for byte-exact layout. */
  def drawn: (Double, Double) = ((w * ImageDim.Scale).toInt.toDouble, (h * ImageDim.Scale).toInt.toDouble)

object ImageDim:
  val Scale = 72.0 / 96.0 // 0.75 — gvusershape_size point↔px DPI conversion
  type Table = Map[String, ImageDim]
  val empty: Table = Map.empty

/** Font/style context carried down the tag tree (Graphviz `textfont_t` +
  * HTML flags). `None` fields inherit from the enclosing environment. */
final case class HtmlFont(
    size:      Option[Double] = None,
    name:      Option[String] = None,
    color:     Option[String] = None,
    bold:      Boolean = false,
    italic:    Boolean = false,
    underline: Boolean = false,
    strike:    Boolean = false,
    sub:       Boolean = false,
    sup:       Boolean = false
) derives CanEqual

/** One styled text run within a line. `size`/`yoffset` filled during sizing. */
final case class HtmlItem(str: String, font: HtmlFont) derives CanEqual

/** One line (Graphviz `textspan_t`): a list of runs + optional horizontal
  * alignment. `None` inherits the enclosing default (cell `align`, else the
  * label default = centre). */
final case class HtmlSpan(items: List[HtmlItem], align: Option[HtmlAlign]) derives CanEqual

/** A text block = paragraphs/lines separated by `<br/>` (Graphviz `htmltxt_t`). */
final case class HtmlText(spans: List[HtmlSpan]) derives CanEqual

enum HtmlAlign derives CanEqual:
  case Left, Center, Right, Text_

/** A `<table>` with its attributes + rows of cells.
  * @param hrAfter boundary indices (row-below) with an `<hr/>` full-width rule
  * @param vrAfter column boundaries (col-to-the-right) with a `<vr/>` full-height rule */
final case class HtmlTable(
    rows:        List[List[HtmlCell]],
    border:      Int,
    cellborder:  Option[Int],
    cellspacing: Int,
    cellpadding: Int,
    align:       HtmlAlign,
    attrs:       Map[String, String],
    hrAfter:     Set[Int] = Set.empty,
    vrAfter:     Set[Int] = Set.empty
) derives CanEqual

object HtmlTable:
  /** Graphviz `htmltable_t` defaults (htmltable.c) — the single source for
    * both the parser's missing-attr substitution and the layout's
    * negative-value clamping. */
  val DefaultBorder      = 1
  val DefaultCellSpacing = 2
  val DefaultCellPadding = 2

/** A `<td>` cell: contents (text or nested table) + attributes. */
final case class HtmlCell(
    content: HtmlLabel,
    attrs:   Map[String, String]
) derives CanEqual
