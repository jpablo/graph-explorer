package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.graphviz.html.{HtmlAlign, HtmlCell, HtmlFont, HtmlItem, HtmlLabel, HtmlSpan, HtmlTable, HtmlText}
import org.jpablo.graphexplorer.graphviz.html.HtmlParser

/** Editing model for Graphviz HTML-like labels.
  *
  * The grammar is owned by the engine's [[HtmlParser]] (the htmlparse.y port);
  * this object adds what the engine never needed: a canonical PRINTER back to
  * markup (the engine only consumes labels) and grid edits over [[HtmlTable]].
  *
  * Canonical form (what [[parseTable]] returns and edits maintain):
  *   - lowercase tag names and attribute keys, attributes sorted by key;
  *   - adjacent text runs with the same font merged (the parser can produce
  *     splits — e.g. `a<b></b>c` — that would re-merge on reparse);
  *   - derived table fields (border/cellspacing/…) consistent with the raw
  *     `attrs` map, which is what gets printed;
  *   - `vrAfter` bounded by the first row's declared width (that is where the
  *     printer emits `<vr/>` markers).
  *
  * Cell addresses are DECLARED positions `List(rowIdx, cellIdxInRow)` — the
  * same traversal order as `HtmlTableLayout.layout(...).cells`, so a hit-test
  * result maps back by flat index ([[declaredPaths]]).
  */
object HtmlLabelOps:

  /** Path of a cell in a top-level table: List(rowIdx, cellIdxInRow), declared order. */
  type Path = List[Int]

  // ── parse ───────────────────────────────────────────────────────────────

  /** The label as a canonical table, when it is one. */
  def parseTable(markup: String): Option[HtmlTable] =
    HtmlParser.parse(markup) match
      case Some(HtmlLabel.Table(t)) => Some(normalizeTable(t))
      case _                        => None

  // ── print ───────────────────────────────────────────────────────────────

  def printLabel(label: HtmlLabel): String = label match
    case HtmlLabel.Table(t)          => printTable(t)
    case HtmlLabel.Text(t)           => printText(t)
    case HtmlLabel.Image(src, scale) => imgMarkup(src, scale)

  /** Pretty markup: structural tags one per line (the tokenizer skips stray
    * whitespace between them), cell CONTENT tight (whitespace inside `<td>`
    * is significant text).
    */
  def printTable(tbl: HtmlTable): String = printTableIndented(tbl, 0)

  private def printTableIndented(tbl: HtmlTable, level: Int): String =
    val ind  = "  " * level
    val ind1 = "  " * (level + 1)
    val ind2 = "  " * (level + 2)
    val sb   = StringBuilder()
    sb ++= s"$ind<table${attrsMarkup(tbl.attrs)}>\n"
    for r <- 0 to tbl.rows.length do
      if tbl.hrAfter(r) then sb ++= s"$ind1<hr/>\n"
      if r < tbl.rows.length then
        sb ++= s"$ind1<tr>\n"
        val cells = tbl.rows(r)
        for c <- 0 to cells.length do
          if r == 0 && tbl.vrAfter(c) then sb ++= s"$ind2<vr/>\n"
          if c < cells.length then sb ++= printCell(cells(c), level + 2)
        sb ++= s"$ind1</tr>\n"
    sb ++= s"$ind</table>"
    sb.toString

  private def printCell(cell: HtmlCell, level: Int): String =
    val ind = "  " * level
    cell.content match
      case HtmlLabel.Table(inner) =>
        s"$ind<td${attrsMarkup(cell.attrs)}>\n${printTableIndented(inner, level + 1)}\n$ind</td>\n"
      case HtmlLabel.Image(src, scale) =>
        s"$ind<td${attrsMarkup(cell.attrs)}>${imgMarkup(src, scale)}</td>\n"
      case HtmlLabel.Text(t) =>
        s"$ind<td${attrsMarkup(cell.attrs)}>${printText(t)}</td>\n"

  /** Text block: runs wrapped in minimal style tags, `<br/>` between lines.
    * A line's alignment rides on the `<br/>` that ENDS it (the parser's
    * flush-on-br rule), so a last line needs a trailing br when it carries an
    * alignment — or when it is an empty line that only the br makes exist
    * (`a<br/><br/>` parses to two spans; dropping the last br would lose one).
    */
  def printText(text: HtmlText): String =
    val sb = StringBuilder()
    text.spans.zipWithIndex.foreach: (span, i) =>
      sb ++= span.items.map(printItem).mkString
      val alignAttr = span.align.map(a => s""" align="${alignName(a)}"""").getOrElse("")
      val isLast    = i == text.spans.length - 1
      val lastNeedsBr = span.align.isDefined || (span.items.isEmpty && text.spans.length > 1)
      if !isLast || lastNeedsBr then sb ++= s"<br$alignAttr/>"
    sb.toString

  private def printItem(it: HtmlItem): String =
    val f   = it.font
    var out = escText(it.str)
    if f.sup then out = s"<sup>$out</sup>"
    if f.sub then out = s"<sub>$out</sub>"
    if f.strike then out = s"<s>$out</s>"
    if f.underline then out = s"<u>$out</u>"
    if f.italic then out = s"<i>$out</i>"
    if f.bold then out = s"<b>$out</b>"
    val fontAttrs =
      f.size.map(sz => s""" point-size="${fmtNum(sz)}"""").getOrElse("") +
        f.name.map(n => s""" face="${escAttr(n)}"""").getOrElse("") +
        f.color.map(c => s""" color="${escAttr(c)}"""").getOrElse("")
    if fontAttrs.nonEmpty then s"<font$fontAttrs>$out</font>" else out

  private def imgMarkup(src: String, scale: Option[String]): String =
    val sc = scale.map(s => s""" scale="${escAttr(s)}"""").getOrElse("")
    s"""<img src="${escAttr(src)}"$sc/>"""

  private def attrsMarkup(attrs: Map[String, String]): String =
    attrs.toList.sortBy(_._1).map((k, v) => s""" $k="${escAttr(v)}"""").mkString

  private def alignName(a: HtmlAlign): String = a match
    case HtmlAlign.Left   => "left"
    case HtmlAlign.Center => "center"
    case HtmlAlign.Right  => "right"
    case HtmlAlign.Text_  => "text"

  private def escText(s: String): String =
    s.flatMap:
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case c   => c.toString

  private def escAttr(s: String): String =
    s.flatMap:
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case '"' => "&quot;"
      case c   => c.toString

  private def fmtNum(d: Double): String =
    if d == d.toLong.toDouble then d.toLong.toString else d.toString

  // ── normalization ───────────────────────────────────────────────────────

  /** Rebuild a table with derived fields consistent with `attrs` (the printer
    * emits only `attrs`; a reparse re-derives) — mirrors the parser's mkTable.
    */
  def withDerived(
      rows:    List[List[HtmlCell]],
      attrs:   Map[String, String],
      hrAfter: Set[Int] = Set.empty,
      vrAfter: Set[Int] = Set.empty
  ): HtmlTable =
    def int(k: String, d: Int) = attrs.get(k).flatMap(_.toIntOption).getOrElse(d)
    val align = attrs.get("align").map(_.toLowerCase) match
      case Some("left")  => HtmlAlign.Left
      case Some("right") => HtmlAlign.Right
      case Some("text")  => HtmlAlign.Text_
      case _             => HtmlAlign.Center
    val boundedVr = if rows.isEmpty then Set.empty[Int] else vrAfter.filter(_ <= rows.head.length)
    HtmlTable(
      rows        = rows,
      border      = int("border", HtmlTable.DefaultBorder),
      cellborder  = attrs.get("cellborder").flatMap(_.toIntOption),
      cellspacing = int("cellspacing", HtmlTable.DefaultCellSpacing),
      cellpadding = int("cellpadding", HtmlTable.DefaultCellPadding),
      align       = align,
      attrs       = attrs,
      hrAfter     = hrAfter,
      vrAfter     = boundedVr
    )

  def normalizeTable(tbl: HtmlTable): HtmlTable =
    withDerived(tbl.rows.map(_.map(normalizeCell)), tbl.attrs, tbl.hrAfter, tbl.vrAfter)

  private def normalizeCell(cell: HtmlCell): HtmlCell =
    cell.copy(content = normalizeLabel(cell.content))

  private def normalizeLabel(l: HtmlLabel): HtmlLabel = l match
    case HtmlLabel.Table(t) => HtmlLabel.Table(normalizeTable(t))
    case HtmlLabel.Text(t)  => HtmlLabel.Text(normalizeText(t))
    case img                => img

  /** Merge adjacent same-font runs and drop empty ones: `a<b></b>c` parses as
    * two plain runs that any reparse of the printed form would merge.
    */
  def normalizeText(t: HtmlText): HtmlText =
    def mergeSpan(span: HtmlSpan): HtmlSpan =
      val merged = span.items.filter(_.str.nonEmpty).foldLeft(List.empty[HtmlItem]):
        case (acc :+ last, it) if last.font == it.font => acc :+ HtmlItem(last.str + it.str, last.font)
        case (acc, it)                                 => acc :+ it
      span.copy(items = merged)
    HtmlText(t.spans.map(mergeSpan))

  // ── queries ─────────────────────────────────────────────────────────────

  /** Declared paths in the SAME order as `HtmlTableLayout.layout(...).cells`
    * (both traverse rows/cells in declaration order) — flat index k here is
    * flat index k there.
    */
  def declaredPaths(tbl: HtmlTable): Vector[Path] =
    tbl.rows.zipWithIndex.flatMap((row, r) => row.indices.map(c => List(r, c))).toVector

  def cellAt(tbl: HtmlTable, path: Path): Option[HtmlCell] =
    path match
      case r :: c :: Nil => tbl.rows.lift(r).flatMap(_.lift(c))
      case _             => None

  /** Every port name in the table, nested tables included. */
  def ports(tbl: HtmlTable): Set[String] =
    def goCell(cell: HtmlCell): Set[String] =
      cell.attrs.get("port").toSet ++ (cell.content match
        case HtmlLabel.Table(inner) => ports(inner)
        case _                      => Set.empty)
    tbl.rows.flatten.flatMap(goCell).toSet

  /** The nearest existing cell to `path` after an edit invalidated it. */
  def nearestPath(tbl: HtmlTable, path: Path): Path =
    if tbl.rows.isEmpty then Nil
    else
      val r   = path.headOption.getOrElse(0).max(0).min(tbl.rows.length - 1)
      val row = tbl.rows(r)
      if row.isEmpty then List(r, 0) // degenerate; kept addressable
      else
        val c = path.lift(1).getOrElse(0).max(0).min(row.length - 1)
        List(r, c)

  // ── edits (total: an invalid path returns the input) ────────────────────

  def modifyCell(tbl: HtmlTable, path: Path)(f: HtmlCell => HtmlCell): HtmlTable =
    path match
      case r :: c :: Nil if tbl.rows.lift(r).exists(_.indices.contains(c)) =>
        val rows = tbl.rows.updated(r, tbl.rows(r).updated(c, f(tbl.rows(r)(c))))
        withDerived(rows, tbl.attrs, tbl.hrAfter, tbl.vrAfter)
      case _ => tbl

  /** Set a cell's content from dialog text: markup when it parses as markup,
    * plain text (newlines → line breaks) otherwise.
    */
  def setCellText(tbl: HtmlTable, path: Path, display: String): HtmlTable =
    modifyCell(tbl, path)(_.copy(content = contentFromDisplay(display)))

  def setCellAttr(tbl: HtmlTable, path: Path, key: String, value: Option[String]): HtmlTable =
    modifyCell(tbl, path): cell =>
      cell.copy(attrs = value.fold(cell.attrs - key)(v => cell.attrs + (key -> v)))

  /** Dialog text for a cell: plain text when the content is unstyled, markup
    * otherwise (round-trips through [[setCellText]] either way).
    */
  def cellDisplayText(cell: HtmlCell): String =
    cell.content match
      case HtmlLabel.Text(t) if t.spans.forall(s => s.align.isEmpty && s.items.forall(_.font == HtmlFont())) =>
        t.spans.map(_.items.map(_.str).mkString).mkString("\n")
      case HtmlLabel.Text(t)           => printText(t)
      case HtmlLabel.Table(t)          => printTable(t)
      case HtmlLabel.Image(src, scale) => imgMarkup(src, scale)

  private def contentFromDisplay(display: String): HtmlLabel =
    val parsed = Option.when(display.contains('<'))(display).flatMap(HtmlParser.parse)
    parsed.map(normalizeLabel).getOrElse:
      val spans = display.replace("\r\n", "\n").split("\n", -1).toList.map: line =>
        HtmlSpan(if line.isEmpty then Nil else List(HtmlItem(line.filter(_ >= ' '), HtmlFont())), None)
      HtmlLabel.Text(normalizeText(HtmlText(spans)))

  def emptyCell: HtmlCell = HtmlCell(HtmlLabel.Text(HtmlText(List(HtmlSpan(Nil, None)))), Map.empty)

  /** Insert a row of empty cells at `at` (0..rows.length). Row width follows
    * the widest declared row. An `<hr/>` sitting exactly at the insertion
    * boundary shifts BELOW the new row — the row lands adjacent to the row it
    * was inserted from, not on the far side of a divider.
    */
  def insertRow(tbl: HtmlTable, at: Int): HtmlTable =
    if at < 0 || at > tbl.rows.length then tbl
    else
      val width  = tbl.rows.map(_.length).maxOption.getOrElse(1).max(1)
      val newRow = List.fill(width)(emptyCell)
      val rows   = tbl.rows.patch(at, List(newRow), 0)
      withDerived(rows, tbl.attrs, tbl.hrAfter.map(b => if b >= at then b + 1 else b), tbl.vrAfter)

  def deleteRow(tbl: HtmlTable, at: Int): HtmlTable =
    if !tbl.rows.indices.contains(at) then tbl
    else
      val rows0 = tbl.rows.patch(at, Nil, 1)
      val rows  = if rows0.isEmpty then List(List(emptyCell)) else rows0
      withDerived(rows, tbl.attrs, tbl.hrAfter.map(b => if b > at then b - 1 else b), tbl.vrAfter)

  /** Insert a column of empty cells at declared position `at` in every row. */
  def insertCol(tbl: HtmlTable, at: Int): HtmlTable =
    if at < 0 then tbl
    else
      val rows = tbl.rows.map(row => row.patch(at.min(row.length), List(emptyCell), 0))
      withDerived(rows, tbl.attrs, tbl.hrAfter, tbl.vrAfter.map(b => if b > at then b + 1 else b))

  def deleteCol(tbl: HtmlTable, at: Int): HtmlTable =
    if at < 0 || !tbl.rows.exists(_.indices.contains(at)) then tbl
    else
      val rows0 = tbl.rows.map(row => if row.indices.contains(at) then row.patch(at, Nil, 1) else row)
      val rows  = if rows0.forall(_.isEmpty) then List(List(emptyCell)) else rows0
      withDerived(rows, tbl.attrs, tbl.hrAfter, tbl.vrAfter.map(b => if b > at then b - 1 else b))

end HtmlLabelOps
