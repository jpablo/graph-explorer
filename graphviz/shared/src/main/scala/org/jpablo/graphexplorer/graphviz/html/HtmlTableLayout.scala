package org.jpablo.graphexplorer.graphviz.html

/** Layout for HTML `<table>` labels (Graphviz `size_html_tbl` / `pos_html_tbl`,
  * htmltable.c). Computes cell sizes, column widths, row heights, and the cell
  * rectangles used by both sizing and rendering. Filled in by the table
  * increment; text labels do not touch this. */
object HtmlTableLayout:

  /** Overall table box (points): outer border + cellspacing + per-cell boxes. */
  def size(tbl: HtmlTable, baseSize: Double, baseName: String): (Double, Double) =
    val laid = layout(tbl, baseSize, baseName)
    (laid.width, laid.height)

  /** A positioned cell: rectangle in table-local coords (origin top-left, y-down)
    * plus its text content for rendering. */
  final case class PlacedCell(x: Double, y: Double, w: Double, h: Double, cell: HtmlCell)

  final case class Laid(width: Double, height: Double, cells: Vector[PlacedCell])

  // TODO(table-increment): real size_html_tbl / pos_html_tbl port.
  def layout(tbl: HtmlTable, baseSize: Double, baseName: String): Laid =
    Laid(0.0, 0.0, Vector.empty)

end HtmlTableLayout
