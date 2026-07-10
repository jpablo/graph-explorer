package org.jpablo.graphexplorer.graphviz.html

import scala.collection.mutable

/** Layout for HTML `<table>` labels (Graphviz `size_html_tbl` / `pos_html_tbl`,
  * htmltable.c). Computes cell sizes, column widths, row heights, and the cell
  * rectangles used by both sizing and rendering.
  *
  * Coordinates are table-local, y-up, centred on the table (origin = table
  * centre) so the caller just adds the node centre. Supports `colspan`/`rowspan`
  * (grid assignment via `findCol`, CSS-style span-width distribution) and text
  * or nested-table cell content.
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

  /** @param hrs y positions (table-local) of `<hr/>` full-width rules
    * @param vrs x positions (table-local) of `<vr/>` full-height rules */
  final case class Laid(width: Double, height: Double, border: Int, cells: Vector[PlacedCell],
                        hrs: Vector[Double] = Vector.empty, vrs: Vector[Double] = Vector.empty)

  /** Overall table box (points). */
  def size(tbl: HtmlTable, baseSize: Double, baseName: String,
           imgs: ImageDim.Table = ImageDim.empty): (Double, Double) =
    val laid = layout(tbl, baseSize, baseName, imgs)
    (laid.width, laid.height)

  /** Box (outer-table-local, y-up, centred) of the cell whose `PORT` attr
    * matches `port`, searching nested tables too. A nested table is centred on
    * its containing cell's content box, so a found inner-cell box is offset by
    * the accumulated content-box centres of the nesting chain. */
  def cellPortBox(tbl: HtmlTable, port: String, baseSize: Double, baseName: String,
                  imgs: ImageDim.Table = ImageDim.empty): Option[BoxLocal] =
    def rec(t: HtmlTable, ox: Double, oy: Double): Option[BoxLocal] =
      layout(t, baseSize, baseName, imgs).cells.iterator.flatMap { pc =>
        if pc.cell.attrs.get("port").contains(port) then
          Some(BoxLocal(pc.box.llx + ox, pc.box.lly + oy, pc.box.urx + ox, pc.box.ury + oy))
        else
          pc.cell.content match
            case HtmlLabel.Table(inner) => rec(inner, ox + pc.contentBox.cx, oy + pc.contentBox.cy)
            case _                      => None
      }.nextOption()
    rec(tbl, 0.0, 0.0)

  def layout(tbl: HtmlTable, baseSize: Double, baseName: String,
             imgs: ImageDim.Table = ImageDim.empty): Laid =
    val space      = if tbl.cellspacing >= 0 then tbl.cellspacing else CellSpacing
    val tblBorder  = tbl.border
    val pad        = if tbl.cellpadding >= 0 then tbl.cellpadding else CellPadding
    // cell border: table `cellborder` attr, else the table border (0 keeps a
    // borderless table's cells borderless), else DEFAULT_BORDER.
    val cellBorder = tbl.cellborder.getOrElse(if tblBorder == 0 then 0 else Border)

    // 1. grid assignment (processTbl/findCol) + cell (bordered) size.
    //    Cells are placed row-major, each at the leftmost free column that fits
    //    its colspan (skipping cells occupied by rowspans from above).
    final case class Info(row: Int, col: Int, colspan: Int, rowspan: Int,
                          cellW: Double, cellH: Double, cell: HtmlCell)
    val margin   = 2.0 * (pad + cellBorder)
    val occupied = mutable.HashSet.empty[(Int, Int)] // (col, row)
    def spanOf(cell: HtmlCell, key: String): Int =
      cell.attrs.get(key).flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1)
    def findCol(row: Int, colHint: Int, colspan: Int): Int =
      var col = colHint
      var searching = true
      while searching do
        val lastc = col + colspan - 1
        var c     = lastc
        while c >= col && !occupied((c, row)) do c -= 1
        if c >= col then col = c + 1 else searching = false
      col
    val infos    = mutable.ArrayBuffer.empty[Info]
    var ncols    = 0
    var nrows    = 0
    tbl.rows.zipWithIndex.foreach { (row, r) =>
      var c = 0
      row.foreach { cell =>
        val cs = spanOf(cell, "colspan")
        val rs = spanOf(cell, "rowspan")
        c = findCol(r, c, cs)
        for j <- c until c + cs; i <- r until r + rs do occupied += ((j, i))
        // FIXEDSIZE cell: the box IS width×height (points) — the content size is
        // ignored (size_html_cell sets sz=0 for a fixed cell). Otherwise the box
        // is content + 2*(pad + cellBorder).
        val fixed = cell.attrs.get("fixedsize").exists(v => v.toLowerCase == "true" || v.toLowerCase == "fixed")
        val fw    = cell.attrs.get("width").flatMap(_.toDoubleOption)
        val fh    = cell.attrs.get("height").flatMap(_.toDoubleOption)
        val (cellW, cellH) =
          if fixed && fw.isDefined && fh.isDefined then (fw.get, fh.get)
          else
            val (cw, ch) = HtmlLayout.size(cell.content, baseSize, baseName, imgs)
            (cw + margin, ch + margin)
        infos += Info(r, c, cs, rs, cellW, cellH, cell)
        c += cs
        ncols = math.max(c, ncols)
        nrows = math.max(r + rs, nrows)
      }
    }

    // 2/3. column widths (set_cell_widths): single-column cells set the column
    //      minimum; spanning cells widen their columns evenly if wider than the
    //      span (+ internal spacing). Rows are analogous (set_cell_heights).
    val colW = Array.fill(ncols)(0.0)
    infos.filter(_.colspan == 1).foreach(i => colW(i.col) = math.max(colW(i.col), i.cellW))
    infos.filter(_.colspan > 1).foreach { i =>
      val spanW   = (i.col until i.col + i.colspan).map(colW).sum
      val spacing = (i.colspan - 1) * space
      if spanW + spacing < i.cellW then
        val widen = (i.cellW - spacing - spanW) / i.colspan
        for j <- i.col until i.col + i.colspan do colW(j) += widen
    }
    val rowH = Array.fill(nrows)(0.0)
    infos.filter(_.rowspan == 1).foreach(i => rowH(i.row) = math.max(rowH(i.row), i.cellH))
    infos.filter(_.rowspan > 1).foreach { i =>
      val spanH   = (i.row until i.row + i.rowspan).map(rowH).sum
      val spacing = (i.rowspan - 1) * space
      if spanH + spacing < i.cellH then
        val widen = (i.cellH - spacing - spanH) / i.rowspan
        for j <- i.row until i.row + i.rowspan do rowH(j) += widen
    }

    // table box (size_html_tbl)
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
        urx = colStart(info.col + info.colspan) - space,
        lly = rowStart(info.row + info.rowspan) + space,
        ury = rowStart(info.row)
      )
      val inset = cellBorder + pad
      val contentBox = BoxLocal(box.llx + inset, box.lly + inset, box.urx - inset, box.ury - inset)
      PlacedCell(box, contentBox, cellBorder, info.cell)
    }.toVector

    // rule lines sit in the middle of the spacing gap at a row/column boundary.
    val hrs = tbl.hrAfter.filter(b => b >= 1 && b <= nrows).toVector.sorted.map(b => rowStart(b) + space / 2.0)
    val vrs = tbl.vrAfter.filter(b => b >= 1 && b <= ncols).toVector.sorted.map(b => colStart(b) - space / 2.0)

    Laid(wd, ht, tblBorder, placed, hrs, vrs)

end HtmlTableLayout
