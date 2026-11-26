package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.html.HtmlLabel
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.labelEditor.{FormattedEditor, LabelSchema}
import org.jpablo.graphexplorer.viewer.components.proseMirror.EditorView
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabelOps
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{SelectedCell, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The Edit Cell dialog has the label editor for a cell of an HTML table.
  *
  * A cell of an HTML-like table holds a text with fonts, as a label does. The dialog shows the tabs "Formatted" and
  * "Source", the toolbar of the formats, and a preview. The preview draws the whole node, with the edited cell in its
  * table. A field of a record node has no fonts in Graphviz, so its dialog keeps the plain text box.
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

  private val dot =
    """digraph G {
      |  n [shape=plaintext, label=<<TABLE><TR><TD><FONT POINT-SIZE="10">given</FONT></TD><TD>other</TD></TR></TABLE>>];
      |  r [shape=record, label="{a|b}"];
      |}""".stripMargin

  final class Dialog(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def view: EditorView = FormattedEditor.viewOf(box).getOrElse(throw AssertionError("no formatted editor"))
    def label(id: NodeId): String = state.fullGraphNow().getNode(id).map(_.label.toString).getOrElse("")
    /** The cells of the HTML table of the node `n`. */
    def cells: List[List[HtmlLabel]] =
      HtmlLabelOps.parseTable(label(n)).toList.flatMap(_.rows.map(_.map(_.content)))
    def previewText: String = find(".gx-label-preview svg").map(_.textContent).getOrElse("")

  /** Mount the dialog for the diagram, and open it on `cell`. */
  private def withDialog(cell: SelectedCell)(body: Dialog => Future[Unit]): Future[Unit] =
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
            val root = render(box, EditCellDialog(state))
            state.editingCellV.set(Some(cell))
            body(Dialog(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  private def enter(target: dom.Element): Unit =
    val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
    target.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  private def withFormatted(cell: SelectedCell)(body: Dialog => Unit): Future[Unit] =
    withDialog(cell): d =>
      Waits
        .settle(FormattedEditor.viewOf(d.box).isDefined)
        .map: _ =>
          check(FormattedEditor.viewOf(d.box).isDefined, s"no formatted editor in ${d.box.innerHTML.take(400)}")
          body(d)

  test("a cell of an HTML table opens in Formatted, with the fonts of the cell"):
    withFormatted(SelectedCell(n, List(0, 0))): d =>
      val active = d.find("[data-gx-tab=formatted]").exists(_.classList.contains("tab-active"))
      check(active, "the Formatted tab is not active")
      val prose = d.find(".ProseMirror")
      check(prose.exists(_.textContent == "given"), s"the editor shows '${prose.map(_.textContent)}'")
      check(prose.exists(_.querySelector("[data-size='10']") != null), s"the cell font is not there: ${prose.map(_.innerHTML)}")

  test("an edit in Formatted and Enter save the cell, and the other cell does not change"):
    withFormatted(SelectedCell(n, List(0, 0))): d =>
      val end = d.view.state.doc.content.size - 1
      d.view.dispatch(d.view.state.tr.insertText("!", end))
      enter(d.view.dom)
      val texts = d.cells.flatten.map:
        case HtmlLabel.Text(t) => t.spans.flatMap(_.items.map(_.str)).mkString
        case other             => other.toString
      check(texts == List("given!", "other"), s"the cells are $texts; the label is ${d.label(n)}")
      check(d.label(n).contains("POINT-SIZE=\"10\"") || d.label(n).toLowerCase.contains("point-size=\"10\""), s"the font is gone: ${d.label(n)}")
      check(d.state.editingCellV.now().isEmpty, "the dialog is still open")

  test("bold in a cell saves a bold cell"):
    withFormatted(SelectedCell(n, List(0, 1))): d =>
      d.view.dispatch(
        d.view.state.tr.addMark(1, 1 + "other".length, LabelSchema.schema.mark(LabelSchema.Bold, null))
      )
      enter(d.view.dom)
      val bold = d.cells.lift(0).flatMap(_.lift(1)).exists:
        case HtmlLabel.Text(t) => t.spans.flatMap(_.items).forall(_.font.bold)
        case _                 => false
      check(bold, s"the cell is not bold: ${d.label(n)}")

  test("the preview draws the whole node, with the edited cell in its table"):
    withDialog(SelectedCell(n, List(0, 0))): d =>
      Waits
        .settle(FormattedEditor.viewOf(d.box).isDefined && d.previewText.nonEmpty)
        .flatMap: _ =>
          check(FormattedEditor.viewOf(d.box).isDefined, "no formatted editor")
          val end = d.view.state.doc.content.size - 1
          d.view.dispatch(d.view.state.tr.insertText("NEW", end))
          Waits.settle(d.previewText.contains("givenNEW"))
        .map: _ =>
          check(d.previewText.contains("givenNEW"), s"the preview does not show the edit: '${d.previewText}'")
          check(d.previewText.contains("other"), s"the preview does not show the other cell: '${d.previewText}'")

  test("a field of a record node keeps the plain text box"):
    withDialog(SelectedCell(r, List(0))): d =>
      Waits
        .settle(d.find("textarea").isDefined)
        .map: _ =>
          check(d.find("textarea").isDefined, s"no text box in ${d.box.innerHTML.take(400)}")
          check(d.find("[data-gx-tab]").isEmpty, "the record field has the tabs of the label editor")

end EditCellDialogSpec
