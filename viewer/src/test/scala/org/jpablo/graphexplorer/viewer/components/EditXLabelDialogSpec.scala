package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render, Signal}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.labelEditor.FormattedEditor
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, XLabel}
import org.jpablo.graphexplorer.viewer.models.{ArrowId, AttributeId, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The external label (`xlabel`) of a node or an arrow has the label editor.
  *
  * Graphviz draws an `xlabel` outside the element, and it can be HTML-like markup, as a label can. The XLabel row of
  * the "More attributes" card of the context strip is a one-line text box. The row has a button "Edit the external
  * label". The button opens the Edit External Label dialog, with the tabs "Formatted" and "Source" and a preview. The
  * preview draws the label as text with the font of the element, and with no shape, as the canvas draws it.
  */
class EditXLabelDialogSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "EditXLabelDialogSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val a   = NodeId("a")
  private val dot = """digraph G { a [label="node a", xlabel=<<B>ext</B>>]; a -> b [xlabel="plain x"] }"""

  final class Strip(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def button: Option[dom.html.Element] = find("#selection-toolbar [aria-label='Edit the external label']")
    def dialogOpen: Boolean              = find("#edit-xlabel-dialog dialog").isDefined
    def prose: Option[dom.html.Element]  = find("#edit-xlabel-dialog .ProseMirror")
    def preview: Option[dom.html.Element] = find("#edit-xlabel-dialog .gx-label-preview svg")
    def attr(id: ElementId, name: AttributeId): Option[String] =
      val g = state.fullGraphNow()
      val attrs = id match
        case n: NodeId  => g.nodes.get(n).map(_.attributes)
        case r: ArrowId => g.arrows.get(r).map(_.attributes)
        case _          => None
      attrs.flatMap(_.get(name)).map(_.toString)

  private def withStrip(body: Strip => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("edit-xlabel-spec"), graphviz, initialSource = Some(dot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(a), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(a), "precondition: the diagram did not load")
            val commands = Commands(state, RouterCommands(Router()))
            val root     = render(box, div(AttributesToolbar(Signal.fromValue("x"), commands, state), EditXLabelDialog(state)))
            body(Strip(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  /** Select `id`, click the button of the XLabel row, and wait for the formatted editor. */
  private def open(s: Strip, id: ElementId): Future[Unit] =
    s.state.selection.set(ElementIds(Set(id)))
    Waits
      .settle(s.button.isDefined)
      .flatMap: _ =>
        check(s.button.isDefined, s"the XLabel row has no button 'Edit the external label': ${s.box.innerHTML.take(500)}")
        s.button.get.click()
        Waits.settle(FormattedEditor.viewOf(s.box).isDefined)
      .map: _ =>
        check(s.dialogOpen, "the button did not open the dialog")
        check(FormattedEditor.viewOf(s.box).isDefined, "the dialog has no formatted editor")

  private def typeAndEnter(s: Strip, text: String): Unit =
    val view = FormattedEditor.viewOf(s.box).get
    view.dispatch(view.state.tr.insertText(text, view.state.doc.content.size - 1))
    val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
    view.dom.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  test("the button of the XLabel row of a node opens the external label in Formatted"):
    withStrip: s =>
      open(s, a).map: _ =>
        check(s.prose.exists(_.textContent == "ext"), s"the editor shows '${s.prose.map(_.textContent)}'")
        check(s.prose.exists(_.querySelector("b") != null), "ext is not bold")

  test("an edit and Enter save the external label of the node, and the label does not change"):
    withStrip: s =>
      open(s, a).map: _ =>
        typeAndEnter(s, "!")
        check(s.attr(a, XLabel.attrId).exists(_.contains("<B>ext!</B>")), s"the xlabel is ${s.attr(a, XLabel.attrId)}")
        check(s.attr(a, Label.attrId).exists(_.contains("node a")), s"the label is ${s.attr(a, Label.attrId)}")
        check(!s.dialogOpen, "the dialog is still open")

  test("the external label of an arrow opens and saves too"):
    withStrip: s =>
      val arrow = s.state.fullGraphNow().arrows.keys.head
      open(s, arrow).map: _ =>
        check(s.prose.exists(_.textContent == "plain x"), s"the editor shows '${s.prose.map(_.textContent)}'")
        typeAndEnter(s, "y")
        check(s.attr(arrow, XLabel.attrId).exists(_.contains("plain xy")), s"the xlabel is ${s.attr(arrow, XLabel.attrId)}")

  test("the preview draws the external label as text, with no shape"):
    withStrip: s =>
      open(s, a)
        .flatMap(_ => Waits.settle(s.preview.exists(_.textContent.contains("ext"))))
        .map: _ =>
          check(s.preview.exists(_.textContent.contains("ext")), s"the preview shows '${s.preview.map(_.textContent)}'")
          val shapes = s.preview.toList.flatMap(_.querySelectorAll("g.node path, g.node polygon, g.node ellipse").toList)
          check(shapes.isEmpty, s"the preview draws a shape: ${shapes.map(_.outerHTML.take(80))}")

end EditXLabelDialogSpec
