package org.jpablo.graphexplorer.viewer.components.labelEditor

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.proseMirror.*

import scala.scalajs.js

/** A label that is a table, in the "Formatted" mode of the label dialogs.
  *
  * The formatted editor shows a table as a grid. Each cell of the table is a cell of the grid, and the text of a cell
  * has its fonts. A cell with a nested table or an image is a fixed cell: the editor shows it, and you cannot change
  * it.
  *
  * The save writes again only the cells that changed. The other cells, the tags of the table, the rows and the white
  * space between them keep their bytes. Thus an open and a save do not write hand-written markup again.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js test bridge and the sbt client, a munit
  * FailException arrives with no message.
  */
class TableLabelSpec extends FunSuite:

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then
      println(s"[TableLabelSpec] $message")
      throw AssertionError(message)

  private def json(node: Node): String = js.JSON.stringify(node.toJSON())

  private def stateOf(d: Node): EditorState = EditorState.create(new EditorStateConfig { doc = d })

  /** The label of an arrow, as a person wrote it. */
  private val arrowLabel =
    """<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="2"><TR><TD ALIGN="LEFT"><FONT FACE="Menlo" COLOR="#244F67" POINT-SIZE="11">rule: ReviewWhen[V2]</FONT></TD></TR><TR><TD ALIGN="LEFT"><FONT FACE="Menlo" COLOR="#37596A" POINT-SIZE="11">= Rule.AnyOf[Rule.Above[500],</FONT></TD></TR></TABLE>"""

  /** A table with two columns, white space between the tags, and lower-case tags. */
  private val grid =
    """<table border="0">
      |  <tr><td>a</td><td><b>b</b></td></tr>
      |  <tr><td colspan="2">c<br/>d</td></tr>
      |</table>""".stripMargin

  /** A table with a nested table and an image in two of its cells. */
  private val fixedCells =
    """<TABLE><TR><TD>text</TD><TD><TABLE><TR><TD>inner</TD></TR></TABLE></TD><TD><IMG SRC="a.png"/></TD></TR></TABLE>"""

  private def table(doc: Node): Node =
    check(doc.childCount == 1 && doc.child(0).`type`.name == LabelSchema.Table, s"not a table document: ${json(doc)}")
    doc.child(0)

  private def cell(doc: Node, row: Int, col: Int): Node = table(doc).child(row).child(col)

  /** The position of the cell node at (`row`, `col`). */
  private def cellPos(doc: Node, row: Int, col: Int): Int =
    val t    = table(doc)
    val rows = (0 until row).map(t.child(_).nodeSize).sum
    val r    = t.child(row)
    val cols = (0 until col).map(r.child(_).nodeSize).sum
    1 + rows + 1 + cols

  /** The position at the end of the text of the first line of the cell. */
  private def cellTextEnd(doc: Node, row: Int, col: Int): Int =
    cellPos(doc, row, col) + 2 + cell(doc, row, col).child(0).content.size

  private def docOf(label: String): Node =
    ProseLabel.fromLabelText(label).getOrElse(throw AssertionError(s"the editor cannot edit: $label"))

  // ---- the cells of the markup -------------------------------------------------

  test("the cell ranges of the markup give the content of each cell"):
    val ranges = TableMarkup.cellRanges(grid).getOrElse(throw AssertionError("no cell ranges"))
    val texts  = ranges.map(_.map((from, to) => grid.substring(from, to)))
    check(texts == List(List("a", "<b>b</b>"), List("c<br/>d")), s"got $texts")

  test("a nested table is one cell of the outer table"):
    val ranges = TableMarkup.cellRanges(fixedCells).getOrElse(throw AssertionError("no cell ranges"))
    val texts  = ranges.map(_.map((from, to) => fixedCells.substring(from, to)))
    check(
      texts == List(List("text", "<TABLE><TR><TD>inner</TD></TR></TABLE>", """<IMG SRC="a.png"/>""")),
      s"got $texts"
    )

  test("a table in a FONT tag has the cell ranges of its table"):
    val label  = """<FONT POINT-SIZE="9"><TABLE><TR><TD>a</TD></TR></TABLE></FONT>"""
    val ranges = TableMarkup.cellRanges(label).getOrElse(throw AssertionError("no cell ranges"))
    check(ranges.map(_.map((f, t) => label.substring(f, t))) == List(List("a")), s"got $ranges")

  test("markup that is not a table has no cell ranges"):
    List("hello", "<B>x</B>", """<IMG SRC="a.png"/>""").foreach: m =>
      check(TableMarkup.cellRanges(m).isEmpty, s"'$m' has cell ranges")

  // ---- the document of a table ---------------------------------------------------

  test("canEdit accepts a table label"):
    List(arrowLabel, grid, fixedCells).foreach: m =>
      check(ProseLabel.canEdit(m), s"'$m' is not editable")

  test("a table label gives a table document with one cell for each cell of the label"):
    val doc = docOf(grid)
    doc.check()
    val t = table(doc)
    check(t.childCount == 2, s"${t.childCount} rows")
    check(t.child(0).childCount == 2 && t.child(1).childCount == 1, s"the cells: ${json(doc)}")
    check(cell(doc, 0, 0).textContent == "a", s"cell 0,0: ${json(cell(doc, 0, 0))}")
    check(cell(doc, 0, 1).child(0).child(0).marks.exists(_.`type`.name == LabelSchema.Bold), "b is not bold")
    check(cell(doc, 1, 0).childCount == 2, s"cell 1,0 does not have two lines: ${json(cell(doc, 1, 0))}")
    check(LabelSchema.numberAttr(cell(doc, 1, 0).attrs, "colspan").contains(2.0), s"no colspan: ${json(cell(doc, 1, 0))}")

  test("a cell with a nested table or an image is a fixed cell"):
    val doc = docOf(fixedCells)
    doc.check()
    val kinds = (0 until 3).map(c => cell(doc, 0, c).child(0).`type`.name).toList
    check(kinds == List(LabelSchema.Line, LabelSchema.Fixed, LabelSchema.Fixed), s"the cells are $kinds")

  // ---- the save ----------------------------------------------------------------

  List(arrowLabel, grid, fixedCells).foreach: m =>
    test(s"an unchanged table keeps the hand-written markup: ${m.take(30)}"):
      val out = ProseLabel.toLabelText(docOf(m), m)
      check(out == m, s"got $out")

  test("an edit of one cell writes that cell again, and the other cells keep their bytes"):
    val doc  = docOf(arrowLabel)
    val tr   = stateOf(doc).tr.insertText("!", cellTextEnd(doc, 0, 0))
    val out  = ProseLabel.toLabelText(tr.doc, arrowLabel)
    val want = arrowLabel.replace(
      """<FONT FACE="Menlo" COLOR="#244F67" POINT-SIZE="11">rule: ReviewWhen[V2]</FONT>""",
      """<FONT FACE="Menlo" POINT-SIZE="11" COLOR="#244F67">rule: ReviewWhen[V2]!</FONT>"""
    )
    check(out == want, s"got  $out\nwant $want")

  test("an edit keeps the white space between the tags and the fixed cells"):
    val doc  = docOf(fixedCells)
    val tr   = stateOf(doc).tr.insertText(" & <more>", cellTextEnd(doc, 0, 0))
    val out  = ProseLabel.toLabelText(tr.doc, fixedCells)
    val want = fixedCells.replace("<TD>text</TD>", "<TD>text &amp; &lt;more&gt;</TD>")
    check(out == want, s"got  $out\nwant $want")

  test("a new line in a cell gives a BR"):
    val doc   = docOf(grid)
    val typed = stateOf(doc).apply(stateOf(doc).tr.insertText("2", cellTextEnd(doc, 0, 0)))
    // The cursor goes between "a" and "2", and the line splits there.
    val at    = typed.apply(typed.tr.setSelection(TextSelection.create(typed.doc, cellPos(doc, 0, 0) + 3)))
    var after = at
    val split = commands.splitBlockAs((_, _, _) => null)(at, (tr: Transaction) => after = at.apply(tr), js.undefined)
    check(split, "the split did not act")
    val out  = ProseLabel.toLabelText(after.doc, grid)
    val want = grid.replace("<td>a</td>", "<td>a<BR/>2</td>")
    check(out == want, s"got  $out\nwant $want")

end TableLabelSpec
