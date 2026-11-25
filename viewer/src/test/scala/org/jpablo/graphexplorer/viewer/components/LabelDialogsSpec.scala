package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{HtmlElement, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.codeMirror.{ChangeSpec, EditorView, TransactionSpec}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, Attributes, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The label of a new node and of a new group, from the label dialogs.
  *
  * The dialogs must use the rule of the Edit Label dialog: HTML-like markup
  * stays as it is, and plain text gets the escapes of a DOT string.
  *
  * Before the fix, the new-node dialog and the new-group dialog escaped each
  * text. A line break in markup then became the two characters `\n` in the
  * HTML-like label, and Graphviz showed them as text or refused the label.
  *
  * The test types the label in the editor of the dialog and presses Enter.
  */
class LabelDialogsSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LabelDialogsSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val dot = "digraph G { a; b }"

  private val markup = "<B>first</B><BR/>\n<I>second</I>"

  /** Mount `dialog` for a new diagram, run `open`, type `label` in the
    * editor, and press Enter. The result is the state after the save.
    */
  private def saveLabel(name: String, label: String)(dialog: ViewerState => HtmlElement)(open: ViewerState => Unit): Future[ViewerState] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    val result =
      TestHelpers.sharedGraphviz.flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(dot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(NodeId("b")))
          .flatMap: _ =>
            val root = render(box, dialog(state))
            open(state)
            // The label opens in the tab "Formatted". The test types the
            // label in the source.
            Waits
              .settle(box.querySelector("[data-gx-tab=source]") != null)
              .flatMap: _ =>
                box.querySelector("[data-gx-tab=source]").asInstanceOf[dom.html.Element].click()
                Waits.settle(box.querySelector(".cm-content") != null)
              .map: _ =>
                val view = EditorView.findFromDOM(box.querySelector(".cm-editor"))
                view.dispatch(new TransactionSpec {
                  changes = new ChangeSpec {
                    from   = 0
                    insert = label
                  }
                })
                val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
                box.querySelector(".cm-content").dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))
                root.unmount()
                state
    result.transform: outcome =>
      box.parentNode.removeChild(box)
      owner.killSubscriptions()
      outcome

  /** The label of the one new node, or of the one new group. */
  private def newNodeLabel(state: ViewerState): Option[String] =
    val graph = state.fullGraphNow()
    (graph.nodeIds -- Set(NodeId("a"), NodeId("b"))).headOption.flatMap(id => graph.nodes(id).attributes.get(Label.attrId)).map(_.toString)

  private def newGroupLabel(state: ViewerState): Option[String] =
    val graph = state.fullGraphNow()
    graph.groups.values.headOption.flatMap(_.attributes.get(Label.attrId)).map(_.toString)

  private def newNode(label: String) =
    saveLabel("label-dialogs-node", label)(NewNodeLabelDialog(_)): state =>
      state.pendingNewNodeV.set(Some(state.PendingNewNode(Attributes.empty, ArrowDirection.forward)))

  private def newGroup(label: String) =
    saveLabel("label-dialogs-group", label)(NewGroupLabelDialog(_)): state =>
      state.pendingNewGroupV.set(Some(state.PendingNewGroup(ElementIds(Set(NodeId("a"))))))

  test("the new-node dialog keeps HTML-like markup, with its line breaks"):
    newNode(markup).flatMap: state =>
      Waits.settle(newNodeLabel(state).isDefined).map: _ =>
        check(newNodeLabel(state).contains(markup), s"the label of the new node is ${newNodeLabel(state).map(pprint.apply(_))}")

  test("the new-node dialog escapes a plain label"):
    newNode("one\ntwo").flatMap: state =>
      Waits.settle(newNodeLabel(state).isDefined).map: _ =>
        check(newNodeLabel(state).contains("one\\ntwo"), s"the label of the new node is ${newNodeLabel(state).map(pprint.apply(_))}")

  test("the new-group dialog keeps HTML-like markup, with its line breaks"):
    newGroup(markup).flatMap: state =>
      Waits.settle(newGroupLabel(state).isDefined).map: _ =>
        check(newGroupLabel(state).contains(markup), s"the label of the new group is ${newGroupLabel(state).map(pprint.apply(_))}")

  test("the new-group dialog escapes a plain label"):
    newGroup("one\ntwo").flatMap: state =>
      Waits.settle(newGroupLabel(state).isDefined).map: _ =>
        check(newGroupLabel(state).contains("one\\ntwo"), s"the label of the new group is ${newGroupLabel(state).map(pprint.apply(_))}")

  // The Edit Label dialog keeps its element, and mounts it again at each open.
  // The dialog sets the text after the mount of the editor. The preview must
  // then draw the label of the second node, not the label of the first node.
  // (During the work, the app once showed the drawing of the first label
  // after a second open. This test did not fail with that code, so it only
  // guards the flow.)
  test("a second open of the Edit Label dialog draws the label of the second node"):
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    def previewText = Option(box.querySelector(".gx-label-preview svg")).map(_.textContent).getOrElse("")
    def escape(): Unit =
      val init = js.Dynamic.literal(key = "Escape", bubbles = true, cancelable = true)
      // The editor of the open tab: Formatted (ProseMirror) or Source (CodeMirror).
      box.querySelector(".ProseMirror, .cm-content").dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val source = """digraph G { a [label="First"]; b [label="Second"] }"""
        val state  = ViewerState(ViewTarget.diagram("label-dialogs-reopen"), graphviz, initialSource = Some(source))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(NodeId("b")))
          .flatMap: _ =>
            render(box, EditLabelDialog(state))
            state.editingElementV.set(Some(NodeId("a")))
            Waits.settle(previewText.contains("First"))
          .flatMap: _ =>
            escape()
            check(state.editingElementV.now().isEmpty, "precondition: Escape did not close the dialog")
            state.editingElementV.set(Some(NodeId("b")))
            Waits.settle(previewText.contains("Second"), timeoutMs = 2000)
      .transform: outcome =>
        if outcome.isFailure then println(s"[LabelDialogsSpec] the preview after the second open: '$previewText'")
        box.parentNode.removeChild(box)
        owner.killSubscriptions()
        outcome
