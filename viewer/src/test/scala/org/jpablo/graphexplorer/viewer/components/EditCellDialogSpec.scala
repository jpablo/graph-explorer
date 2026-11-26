package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.labelEditor.FormattedEditor
import org.jpablo.graphexplorer.viewer.components.proseMirror.EditorView
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{SelectedCell, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The text of a cell of a node: the whole table for an HTML table, one field for a record.
  *
  * A double-click on a cell of an HTML-like table (or Enter on a selected cell) opens the Edit Label dialog with the
  * whole table as a grid, and the cursor in that cell. Tab moves to the other cells, and one save keeps the edits of
  * all of them. The save writes again only the cells that changed. The selection of a cell stays for the structure:
  * rows, columns, and the attributes of the cell.
  *
  * A field of a record node has no fonts in Graphviz, and its whole label is record syntax. Thus a record field keeps
  * the Edit Cell dialog, with a plain text box.
  */
class EditCellDialogSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "EditCellDialogSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val n = NodeId("n")
  private val r = NodeId("r")

  private val table = """<TABLE><TR><TD><FONT POINT-SIZE="10">given</FONT></TD><TD>other</TD></TR></TABLE>"""

  private val dot =
    s"""digraph G {
       |  n [shape=plaintext, label=<$table>];
       |  r [shape=record, label="{a|b}"];
       |}""".stripMargin

  final class Dialogs(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def labelDialogOpen: Boolean  = find("#edit-label-dialog dialog").isDefined
    def cellDialogOpen: Boolean   = find("#edit-cell-dialog dialog").isDefined
    def view: EditorView          = FormattedEditor.viewOf(box).getOrElse(throw AssertionError("no formatted editor"))
    def label(id: NodeId): String = state.fullGraphNow().getNode(id).map(_.label.toString).getOrElse("")

    /** The position of the cell node at (`row`, `col`) of the grid. */
    def cellPos(row: Int, col: Int): Int =
      val t    = view.state.doc.child(0)
      val rows = (0 until row).map(t.child(_).nodeSize).sum
      val rw   = t.child(row)
      1 + rows + 1 + (0 until col).map(rw.child(_).nodeSize).sum

    /** The position at the end of the text of the cell. */
    def cellEnd(row: Int, col: Int): Int =
      cellPos(row, col) + view.state.doc.child(0).child(row).child(col).nodeSize - 2

    /** The cell of the cursor, as (row, col). */
    def cursorCell: Option[(Int, Int)] =
      val at = view.state.selection.from
      val t  = view.state.doc.child(0)
      (for
        row <- 0 until t.childCount
        col <- 0 until t.child(row).childCount
        if at > cellPos(row, col) && at < cellPos(row, col) + t.child(row).child(col).nodeSize
      yield (row, col)).headOption

  /** Mount the label dialog and the cell dialog for the diagram. */
  private def withDialogs(body: Dialogs => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("edit-cell-spec"), graphviz, initialSource = Some(dot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(r), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(r), "precondition: the diagram did not load")
            val root = render(box, div(EditLabelDialog(state), EditCellDialog(state)))
            body(Dialogs(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  private def formatted(d: Dialogs): Future[Unit] =
    Waits.settle(FormattedEditor.viewOf(d.box).isDefined).map: _ =>
      check(FormattedEditor.viewOf(d.box).isDefined, s"no formatted editor in ${d.box.innerHTML.take(400)}")

  private def enter(target: dom.Element): Unit =
    val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
    target.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  test("a double-click on a cell of an HTML table opens Edit Label with the whole table, at that cell"):
    withDialogs: d =>
      d.state.recordCells.editCell(SelectedCell(n, List(0, 1)))
      formatted(d).map: _ =>
        check(d.labelDialogOpen, "the Edit Label dialog did not open")
        check(!d.cellDialogOpen, "the Edit Cell dialog opened")
        val cells = d.find("#edit-label-dialog .ProseMirror").toList.flatMap(_.querySelectorAll("td").toList)
        check(cells.map(_.textContent) == List("given", "other"), s"the grid shows ${cells.map(_.textContent)}")
        check(d.cursorCell.contains((0, 1)), s"the cursor is in the cell ${d.cursorCell}, not in (0, 1)")

  test("Enter on a selected cell of an HTML table opens Edit Label at that cell"):
    withDialogs: d =>
      d.state.selection.set(ElementIds(Set(n)))
      d.state.recordCells.selectCell(n, List(0, 0))
      check(d.state.recordCells.enterOrEdit(), "Enter did not act on the selected cell")
      formatted(d).map: _ =>
        check(d.labelDialogOpen && !d.cellDialogOpen, "Enter did not open the Edit Label dialog")
        check(d.cursorCell.contains((0, 0)), s"the cursor is in the cell ${d.cursorCell}, not in (0, 0)")

  test("an edit of two cells and Enter save both cells, and keep the other markup"):
    withDialogs: d =>
      d.state.recordCells.editCell(SelectedCell(n, List(0, 0)))
      formatted(d).map: _ =>
        d.view.dispatch(d.view.state.tr.insertText("2", d.cellEnd(0, 1)))
        d.view.dispatch(d.view.state.tr.insertText("1", d.cellEnd(0, 0)))
        enter(d.view.dom)
        val want = table.replace(">given<", ">given1<").replace("<TD>other</TD>", "<TD>other2</TD>")
        check(d.label(n) == want, s"the label is ${d.label(n)}\nnot          $want")
        check(!d.labelDialogOpen, "the dialog is still open")

  test("a field of a record node keeps the Edit Cell dialog with its text box"):
    withDialogs: d =>
      d.state.recordCells.editCell(SelectedCell(r, List(0)))
      Waits
        .settle(d.find("#edit-cell-dialog textarea").isDefined)
        .map: _ =>
          check(d.cellDialogOpen, "the Edit Cell dialog did not open for a record field")
          check(!d.labelDialogOpen, "the Edit Label dialog opened for a record field")
          check(d.find("#edit-cell-dialog [data-gx-tab]").isEmpty, "the record field has the tabs of the label editor")

end EditCellDialogSpec
