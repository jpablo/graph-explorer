package org.jpablo.graphexplorer.viewer.components.labelEditor

import org.jpablo.graphexplorer.graphviz.html.*
import org.jpablo.graphexplorer.viewer.components.labelEditor.LabelSchema.*
import org.jpablo.graphexplorer.viewer.components.proseMirror.{Mark, Node}
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels

import scala.scalajs.js

/** The converters between a label and a document of [[LabelSchema]].
  *
  * A label is the text of a label dialog: HTML-like markup, or plain text with
  * real line breaks. `HtmlLabels.isHtml` tells which, as in the dialogs.
  */
object ProseLabel:

  // ---- HtmlText and documents ------------------------------------------------

  /** The document of a text label. Each line of the label gives a line. Each
    * run gives a text node with the marks of its font. The `font` mark holds
    * the attributes of the run itself: FACE, POINT-SIZE and COLOR.
    *
    * A run with `sub` and `sup` keeps `sub`, because the canvas draws it as
    * `sub`. An empty run gives no text node. A label with no lines gives one
    * empty line.
    */
  def toDoc(text: HtmlText): Node =
    schema.node(Doc, null, linesOf(text), js.Array())

  /** The line nodes of a text, as [[toDoc]] gives them. */
  private def linesOf(text: HtmlText): js.Array[Node] =
    val spans = if text.spans.isEmpty then List(HtmlSpan(Nil, None)) else text.spans
    js.Array(spans.map(lineNode)*)

  private def lineNode(span: HtmlSpan): Node =
    val texts = span.items.filter(_.str.nonEmpty).map(item => schema.text(item.str, marksOf(item.font)))
    val align = stringOrNull(span.align.map(alignValue))
    schema.node(Line, js.Dictionary[js.Any](AlignName -> align), js.Array(texts*), js.Array())

  private def marksOf(f: HtmlFont): js.Array[Mark] =
    val font =
      Option.when(f.name.isDefined || f.size.isDefined || f.color.isDefined):
        schema.mark(
          Font,
          js.Dictionary[js.Any](
            "face"  -> stringOrNull(f.name),
            "size"  -> numberOrNull(f.size),
            "color" -> stringOrNull(f.color)
          )
        )
    val flags = List(
      f.bold      -> Bold,
      f.italic    -> Italic,
      f.underline -> Underline,
      f.overline  -> Overline,
      f.strike    -> Strike,
      f.sub       -> Sub,
      (f.sup && !f.sub) -> Sup
    ).collect { case (true, name) => schema.mark(name, null) }
    js.Array((font.toList ++ flags)*)

  /** The text label of a document. Adjacent runs of the same font are one run.
    * The result is in the form of [[normalize]].
    */
  def fromDoc(doc: Node): HtmlText =
    val spans = children(doc).map: line =>
      val items = children(line).flatMap(n => n.text.toOption.map(s => HtmlItem(s, fontOf(n.marks))))
      HtmlSpan(items, stringAttr(line.attrs, AlignName).flatMap(alignOf))
    normalize(HtmlText(spans))

  private def children(node: Node): List[Node] =
    (0 until node.childCount).toList.map(node.child)

  private def fontOf(marks: js.Array[Mark]): HtmlFont =
    def has(name: String) = marks.exists(_.`type`.name == name)
    val font = marks.find(_.`type`.name == Font)
    HtmlFont(
      size      = font.flatMap(m => numberAttr(m.attrs, "size")),
      name      = font.flatMap(m => stringAttr(m.attrs, "face")),
      color     = font.flatMap(m => stringAttr(m.attrs, "color")),
      bold      = has(Bold),
      italic    = has(Italic),
      underline = has(Underline),
      strike    = has(Strike),
      sub       = has(Sub),
      sup       = has(Sup),
      overline  = has(Overline)
    )

  private def alignValue(a: HtmlAlign): String = a match
    case HtmlAlign.Left   => AlignLeft
    case HtmlAlign.Center => AlignCenter
    case HtmlAlign.Right  => AlignRight
    case HtmlAlign.Text_  => AlignText

  private def alignOf(value: String): Option[HtmlAlign] = value match
    case AlignLeft   => Some(HtmlAlign.Left)
    case AlignCenter => Some(HtmlAlign.Center)
    case AlignRight  => Some(HtmlAlign.Right)
    case AlignText   => Some(HtmlAlign.Text_)
    case _           => None

  /** The text with the empty runs removed and the adjacent runs of the same
    * font joined. `HtmlParser.parse` gives this form for the markup of
    * `HtmlWriter.write`.
    */
  def normalize(text: HtmlText): HtmlText =
    HtmlText(text.spans.map(span => span.copy(items = joinRuns(span.items))))

  private def joinRuns(items: List[HtmlItem]): List[HtmlItem] =
    items
      .filter(_.str.nonEmpty)
      .foldLeft(List.empty[HtmlItem]):
        case (prev :: rest, item) if prev.font == item.font => prev.copy(str = prev.str + item.str) :: rest
        case (acc, item)                                     => item :: acc
      .reverse

  // ---- label texts -----------------------------------------------------------

  /** The document of a plain text. Each "\n" starts a new line. */
  def fromPlain(text: String): Node =
    toDoc(HtmlText(text.split("\n", -1).toList.map(l => HtmlSpan(List(HtmlItem(l, HtmlFont())), None))))

  /** The document of markup that is a text label. None for a table, an image
    * or markup that does not parse.
    */
  def fromMarkup(markup: String): Option[Node] =
    HtmlParser.parse(markup).collect { case HtmlLabel.Text(text) => toDoc(text) }

  /** The document of the text of a label dialog, or None when the formatted
    * editor cannot edit it (see [[canEdit]]).
    */
  def fromLabelText(original: String): Option[Node] =
    if !HtmlLabels.isHtml(original) then Some(fromPlain(original))
    else if canEdit(original) then fromMarkup(original).orElse(tableDoc(original))
    else None

  /** True when the formatted editor can edit the text of a label dialog: plain
    * text, valid markup of a text label, or valid markup of a table whose
    * cells [[TableMarkup]] finds.
    *
    * A table and an image give false. Markup with an error gives false, also
    * when `HtmlParser.parse` reads it: the dialog shows the error, and the
    * user corrects the source. A text label with an `<IMG>` gives false,
    * because the document has no image and an edit removes it.
    */
  def canEdit(original: String): Boolean =
    if !HtmlLabels.isHtml(original) then true
    else
      HtmlParser.parseOrError(original) match
        case Right(HtmlLabel.Text(_))  => !original.toLowerCase.contains("<img")
        case Right(HtmlLabel.Table(_)) => tableDoc(original).isDefined
        case _                         => false

  /** The text for the label dialog after an edit of `doc`.
    *
    *   - When `doc` is equal to the document of `original`, the result is
    *     `original`. Thus an open and a save do not write hand-written markup
    *     again.
    *   - When `doc` has no marks and no alignment, the result is plain text:
    *     the lines with "\n" between them.
    *   - When `doc` is a table, the result is `original` with the content of
    *     each changed cell written again (see [[tableText]]).
    *   - Else the result is the markup of `HtmlWriter.write`.
    *
    * The dialog reads a text as markup when `HtmlLabels.isHtml` is true. A plain
    * text such as "a <b>c</b>" looks like markup. Thus that text is written
    * as markup, and markup with no tag gets a `<FONT>` with no attributes,
    * which changes nothing.
    */
  def toLabelText(doc: Node, original: String): String =
    if fromLabelText(original).exists(_.eq(doc)) then original
    else if isTable(doc) then tableText(doc, original)
    else
      val text      = fromDoc(doc)
      val formatted = text.spans.exists(span => span.align.isDefined || span.items.exists(_.font != HtmlFont()))
      val plain     = text.spans.map(_.items.map(_.str).mkString).mkString("\n")
      if !formatted && !HtmlLabels.isHtml(plain) then plain
      else
        val markup = HtmlWriter.write(text)
        if HtmlLabels.isHtml(markup) then markup else s"<FONT>$markup</FONT>"

  // ---- tables ------------------------------------------------------------------

  private def isTable(doc: Node): Boolean =
    doc.childCount == 1 && doc.child(0).`type`.name == Table

  /** The document of a table label: one cell node for each `<TD>` of the table.
    * None when the markup is not a table, or when [[TableMarkup]] does not find
    * the same cells as `HtmlParser`.
    */
  def tableDoc(markup: String): Option[Node] =
    (HtmlParser.parse(markup), TableMarkup.cellRanges(markup)) match
      case (Some(HtmlLabel.Table(tbl)), Some(ranges)) if ranges.map(_.size) == tbl.rows.map(_.size) =>
        val rows = tbl.rows.zip(ranges).map: (cells, places) =>
          val cellNodes = cells.zip(places).map((c, place) => cellNode(c, markup.substring(place._1, place._2)))
          schema.node(Row, null, js.Array(cellNodes*), js.Array())
        val table = schema.node(Table, null, js.Array(rows*), js.Array())
        Some(schema.node(Doc, null, js.Array(table), js.Array()))
      case _ => None

  /** The node of a cell. `source` is the content of the cell in the markup. A
    * cell with a nested table or an image is a fixed cell.
    */
  private def cellNode(cell: HtmlCell, source: String): Node =
    def int(key: String): Double = cell.attrs.get(key).flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(1).toDouble
    val attrs = js.Dictionary[js.Any](
      "colspan" -> int("colspan"),
      "rowspan" -> int("rowspan"),
      "align"   -> stringOrNull(cell.attrs.get("align").map(_.trim.toLowerCase)),
      "bgcolor" -> stringOrNull(cell.attrs.get("bgcolor"))
    )
    def fixed(kind: String) = js.Array(schema.node(Fixed, js.Dictionary[js.Any](FixedKind -> kind), js.Array(), js.Array()))
    val content = cell.content match
      case HtmlLabel.Text(text) if !source.toLowerCase.contains("<img") => linesOf(text)
      case HtmlLabel.Table(_)                                            => fixed("table")
      case _                                                             => fixed("image")
    schema.node(Cell, attrs, content, js.Array())

  /** The label of an edited table: `original` with the content of each cell
    * that changed written again with `HtmlWriter.write`. The tags of the table,
    * the white space between them and the cells that did not change keep their
    * bytes. The structure of `doc` is the structure of `original`: the editor
    * does not change it.
    */
  private def tableText(doc: Node, original: String): String =
    (tableDoc(original), TableMarkup.cellRanges(original)) match
      case (Some(before), Some(ranges)) =>
        val oldTable = before.child(0)
        val newTable = doc.child(0)
        val changes =
          for
            (places, r) <- ranges.zipWithIndex
            ((from, to), c) <- places.zipWithIndex
            newCell = newTable.child(r).child(c)
            if !newCell.eq(oldTable.child(r).child(c)) && newCell.child(0).`type`.name == Line
          yield (from, to, HtmlWriter.write(fromDoc(newCell)))
        changes.sortBy(-_._1).foldLeft(original):
          case (text, (from, to, markup)) => text.substring(0, from) + markup + text.substring(to)
      case _ => original

end ProseLabel
