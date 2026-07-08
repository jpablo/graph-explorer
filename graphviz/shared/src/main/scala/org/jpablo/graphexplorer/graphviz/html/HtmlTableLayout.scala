package org.jpablo.graphexplorer.graphviz.html

/** Layout for HTML `<table>` labels (Graphviz `size_html_tbl` / `pos_html_tbl`,
  * htmltable.c). Computes cell sizes, column widths, row heights, and the cell
  * rectangles used by both sizing and rendering.
  *
  * Coordinates are table-local, y-up, centred on the table (origin = table
  * centre) so the caller just adds the node centre. Scope: colspan/rowspan = 1
  * (spans deferred), text or nested-table cell content.
  */
object HtmlTableLayout:

  private val CellSpacing = 2 // DEFAULT_CELLSPACING
  private val CellPadding = 2 // DEFAULT_CELLPADDING
  private val Border      = 1 // DEFAULT_BORDER

  /** A box in table-local, y-up coordinates (origin = table centre). */
  final case class BoxLocal(llx: Double, lly: Double, urx: Double, ury: Double):
    def cx: Double = (llx + urx) / 2.0
    def cy: Double = (lly + ury) / 2.0

  /** A positioned cell: its border box, the inner content box (border+pad
    * inset, where the child is centred), the drawn cell-border width, and the
    * cell itself. */
  final case class PlacedCell(box: BoxLocal, contentBox: BoxLocal, cellBorder: Int, cell: HtmlCell)

  final case class Laid(width: Double, height: Double, border: Int, cells: Vector[PlacedCell])

  /** Overall table box (points). */
  def size(tbl: HtmlTable, baseSize: Double, baseName: String): (Double, Double) =
    val laid = layout(tbl, baseSize, baseName)
    (laid.width, laid.height)

  def layout(tbl: HtmlTable, baseSize: Double, baseName: String): Laid =
    val space      = if tbl.cellspacing >= 0 then tbl.cellspacing else CellSpacing
    val tblBorder  = tbl.border
    val pad        = if tbl.cellpadding >= 0 then tbl.cellpadding else CellPadding
    // cell border: table `cellborder` attr, else the table border (0 keeps a
    // borderless table's cells borderless), else DEFAULT_BORDER.
    val cellBorder = tbl.cellborder.getOrElse(if tblBorder == 0 then 0 else Border)

    val nrows = tbl.rows.length
    val ncols = tbl.rows.map(_.length).maxOption.getOrElse(0)

    // 1. cell content + cell (bordered) size = content + 2*(pad + cellBorder)
    final case class Info(row: Int, col: Int, cellW: Double, cellH: Double, cell: HtmlCell)
    val margin = 2.0 * (pad + cellBorder)
    val infos = for
      (row, r)  <- tbl.rows.zipWithIndex
      (cell, c) <- row.zipWithIndex
    yield
      val (cw, ch) = HtmlLayout.size(cell.content, baseSize, baseName)
      Info(r, c, cw + margin, ch + margin, cell)

    // 2. column widths = max cell width in column; row heights = max in row
    val colW = Vector.tabulate(ncols)(c => infos.filter(_.col == c).map(_.cellW).maxOption.getOrElse(0.0))
    val rowH = Vector.tabulate(nrows)(r => infos.filter(_.row == r).map(_.cellH).maxOption.getOrElse(0.0))

    // 3. table box (size_html_tbl)
    val wd = (ncols + 1) * space + 2 * tblBorder + colW.sum
    val ht = (nrows + 1) * space + 2 * tblBorder + rowH.sum

    // 4. positions (pos_html_tbl) with pos = [-wd/2,wd/2] x [-ht/2,ht/2], y-up.
    //    colStart(i) = left x of column i; rowStart(i) = top y of row i.
    val posLLx = -wd / 2.0
    val posURy = ht / 2.0
    val colStart = Array.ofDim[Double](ncols + 1)
    var x = posLLx + tblBorder + space
    for i <- 0 to ncols do
      colStart(i) = x
      if i < ncols then x += colW(i) + space
    val rowStart = Array.ofDim[Double](nrows + 1)
    var y = posURy - tblBorder - space
    for i <- 0 to nrows do
      rowStart(i) = y
      if i < nrows then y -= rowH(i) + space

    // 5. placed cells (colspan/rowspan = 1)
    val placed = infos.map { info =>
      val box = BoxLocal(
        llx = colStart(info.col),
        urx = colStart(info.col + 1) - space,
        lly = rowStart(info.row + 1) + space,
        ury = rowStart(info.row)
      )
      val inset = cellBorder + pad
      val contentBox = BoxLocal(box.llx + inset, box.lly + inset, box.urx - inset, box.ury - inset)
      PlacedCell(box, contentBox, cellBorder, info.cell)
    }.toVector

    Laid(wd, ht, tblBorder, placed)

end HtmlTableLayout
