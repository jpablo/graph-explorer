package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.attributes.views.DiagramAttributesView
import org.jpablo.graphexplorer.viewer.components.labelEditor.FormattedEditor
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The title of the diagram has the label editor.
  *
  * The Title row of the Diagram panel is a small text box. The panel is narrow, so the row has a button "Edit the
  * title". The button opens the Edit Title dialog, with the tabs "Formatted" and "Source" and a preview, as the Edit
  * Label dialog has. A title that is a table shows as a grid.
  */
class EditTitleDialogSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "EditTitleDialogSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val title = "<TABLE><TR><TD><B>Big title</B></TD></TR><TR><TD>subtitle</TD></TR></TABLE>"
  private val dot   = s"digraph G { label=<$title>; a }"

  /** The text of the Title row and of the dialog of the title, when the title is `label`. */
  private def dotWith(label: Option[String]) = label.fold("digraph G { a }")(l => s"digraph G { label=<$l>; a }")

  final class Panel(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def editButton: Option[dom.html.Element] = find("#diagram-attributes-view [aria-label='Edit the title']")
    def dialogOpen: Boolean                  = find("#edit-title-dialog dialog").isDefined
    def title: Option[String] = state.fullGraphNow().elements.graphAttributes.get(Label.attrId).map(_.toString)
    def previewText: String = find("#edit-title-dialog .gx-label-preview svg").map(_.textContent).getOrElse("")
    /** The control of the Title row that shows the title. */
    def shown: Option[dom.html.Element] = find("#diagram-attributes-view .gx-title-shown")

  private def withPanel(body: Panel => Future[Unit]): Future[Unit] = withPanelOf(dot)(body)

  private def withPanelOf(dot: String)(body: Panel => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("edit-title-spec"), graphviz, initialSource = Some(dot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(NodeId("a")), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(NodeId("a")), "precondition: the diagram did not load")
            val root = render(box, div(DiagramAttributesView(state), EditTitleDialog(state)))
            body(Panel(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  /** Click the button of the Title row, and wait for the formatted editor of the dialog. */
  private def openDialog(p: Panel): Future[Unit] =
    check(p.editButton.isDefined, s"the Title row has no button 'Edit the title': ${p.box.innerHTML.take(600)}")
    p.editButton.get.click()
    Waits.settle(FormattedEditor.viewOf(p.box).isDefined).map: _ =>
      check(p.dialogOpen, "the button did not open the Edit Title dialog")
      check(FormattedEditor.viewOf(p.box).isDefined, "the dialog has no formatted editor")

  test("the button of the Title row opens the dialog, and a table title shows as a grid"):
    withPanel: p =>
      openDialog(p).map: _ =>
        val cells = p.find("#edit-title-dialog .ProseMirror").toList.flatMap(_.querySelectorAll("td").toList)
        check(cells.map(_.textContent) == List("Big title", "subtitle"), s"the grid shows ${cells.map(_.textContent)}")

  test("an edit and Enter save the title, and close the dialog"):
    withPanel: p =>
      openDialog(p).map: _ =>
        val view = FormattedEditor.viewOf(p.box).get
        val end  = view.state.doc.content.size - 4 // the end of the text of the last cell
        view.dispatch(view.state.tr.insertText("!", end))
        val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
        view.dom.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))
        val want = title.replace("<TD>subtitle</TD>", "<TD>subtitle!</TD>")
        check(p.title.exists(_.contains(want)), s"the title is ${p.title}")
        check(!p.dialogOpen, "the dialog is still open")

  test("the preview draws the title"):
    withPanel: p =>
      openDialog(p)
        .flatMap(_ => Waits.settle(p.previewText.contains("subtitle")))
        .map: _ =>
          check(p.previewText.contains("Big title"), s"the preview shows '${p.previewText}'")

  // ---- the Title row shows the title as the canvas draws it ----------------------------

  test("the Title row shows the title drawn, not its markup"):
    withPanel: p =>
      Waits
        .settle(p.shown.exists(_.querySelector("svg") != null))
        .map: _ =>
          val text = p.shown.flatMap(s => Option(s.querySelector("svg"))).map(_.textContent).getOrElse("")
          check(text.contains("Big title") && text.contains("subtitle"), s"the Title row shows '$text'")
          val raw = p.find("#diagram-attributes-view textarea").map(_.asInstanceOf[dom.html.TextArea].value)
          check(raw.isEmpty, s"the Title row still shows the markup: $raw")

  test("a click on the title in the Title row opens the dialog"):
    withPanel: p =>
      Waits
        .settle(p.shown.isDefined)
        .flatMap: _ =>
          check(p.shown.isDefined, "the Title row does not show the title")
          p.shown.get.click()
          Waits.settle(FormattedEditor.viewOf(p.box).isDefined)
        .map(_ => check(p.dialogOpen, "a click on the title did not open the dialog"))

  test("a diagram with no title shows 'Add a title' in the Title row"):
    withPanelOf(dotWith(None)): p =>
      Waits
        .settle(p.shown.isDefined)
        .map: _ =>
          check(p.shown.exists(_.textContent.contains("Add a title")), s"the Title row shows '${p.shown.map(_.textContent)}'")
          check(p.shown.exists(_.querySelector("svg") == null), "the Title row draws an empty title")

end EditTitleDialogSpec
