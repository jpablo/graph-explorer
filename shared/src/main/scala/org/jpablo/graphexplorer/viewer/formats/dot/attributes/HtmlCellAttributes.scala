package org.jpablo.graphexplorer.viewer.formats.dot.attributes

import org.jpablo.graphexplorer.viewer.models.AttributeId

/** Attributes of a `<td>` in an HTML-like label.
  *
  * They live INSIDE the label markup, not in the element's attribute map, but
  * they flow through the same row machinery: the editor exposes the selected
  * cell's attrs as an `AttributeUpdates` map keyed by these ids, so every
  * control (color picker, dropdown, number, reset dot) works unchanged.
  *
  * `attrId` is overridden throughout: the derived id would be the Scala object
  * name (`cellbgcolor`), while the markup attribute is `bgcolor`. The `Cell`
  * prefix only keeps these from colliding with the same-named DOT attributes,
  * whose defaults and applicability are different.
  */

enum HtmlCellAlign derives CanEqual:
  case left, center, right, text

object CellAlign extends DotAttributeEnum[HtmlCellAlign]:
  override def attrId          = AttributeId("align")
  val label                    = "Horizontal alignment"
  val default                  = HtmlCellAlign.center
  def values: Array[HtmlCellAlign] = HtmlCellAlign.values

enum HtmlCellVAlign derives CanEqual:
  case top, middle, bottom

object CellVAlign extends DotAttributeEnum[HtmlCellVAlign]:
  override def attrId           = AttributeId("valign")
  val label                     = "Vertical alignment"
  val default                   = HtmlCellVAlign.middle
  def values: Array[HtmlCellVAlign] = HtmlCellVAlign.values

object CellBgColor extends DotAttributeSimple[String]:
  override def attrId          = AttributeId("bgcolor")
  val label                    = "Cell fill"
  val default                  = "#ffffff"
  override val placeholderText = "Enter cell background color"

object CellColor extends DotAttributeSimple[String]:
  override def attrId          = AttributeId("color")
  val label                    = "Cell border color"
  val default                  = "#000000"
  override val placeholderText = "Enter cell border color"

object CellBorder extends DotAttributeSimple[Int]:
  override def attrId = AttributeId("border")
  val label           = "Cell border width"
  val default         = 1

object CellPadding extends DotAttributeSimple[Int]:
  override def attrId = AttributeId("cellpadding")
  val label           = "Cell padding"
  val default         = 2

object CellColSpan extends DotAttributeSimple[Int]:
  override def attrId = AttributeId("colspan")
  val label           = "Columns spanned"
  val default         = 1

object CellRowSpan extends DotAttributeSimple[Int]:
  override def attrId = AttributeId("rowspan")
  val label           = "Rows spanned"
  val default         = 1

object CellPort extends DotAttributeSimple[String]:
  override def attrId          = AttributeId("port")
  val label                    = "Port name"
  val default                  = ""
  override val placeholderText = "Port name (edges attach here)"

object HtmlCellAttributes:
  /** Every attribute the cell editor manages — the write path only touches
    * these ids, so unknown markup attributes on a cell survive an edit. */
  val all: Set[AttributeId] =
    Set(CellAlign, CellVAlign, CellBgColor, CellColor, CellBorder, CellPadding, CellColSpan, CellRowSpan, CellPort)
      .map(_.attrId)
