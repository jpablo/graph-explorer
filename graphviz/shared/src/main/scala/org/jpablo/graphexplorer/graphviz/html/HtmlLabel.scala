package org.jpablo.graphexplorer.graphviz.html

/** Parsed HTML-like label (Graphviz `htmllabel_t`, lib/common/htmltable.h).
  *
  * A label is EITHER a text block (paragraphs of styled runs) OR a table.
  * This models the common subset — text with `<b>/<i>/<u>/<s>/<sub>/<sup>/
  * <font>` styling, `<br/>` line breaks, and `<table>/<tr>/<td>` — enough for
  * the vast majority of real HTML labels. Exotic features (img, hr/vr, nested
  * tables, colspan/rowspan, ports, bgcolor gradients) are not modelled yet.
  */
enum HtmlLabel derives CanEqual:
  case Text(block: HtmlText)
  case Table(tbl: HtmlTable)

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

/** A `<table>` with its attributes + rows of cells. */
final case class HtmlTable(
    rows:        List[List[HtmlCell]],
    border:      Int,
    cellborder:  Option[Int],
    cellspacing: Int,
    cellpadding: Int,
    align:       HtmlAlign,
    attrs:       Map[String, String]
) derives CanEqual

/** A `<td>` cell: contents (text or nested table) + attributes. */
final case class HtmlCell(
    content: HtmlLabel,
    attrs:   Map[String, String]
) derives CanEqual
