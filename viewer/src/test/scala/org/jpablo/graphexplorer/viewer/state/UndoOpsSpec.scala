package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, AttributeId, AttributeUpdates, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Undo and redo of the diagram.
  *
  * One history for each view of a diagram holds the source text, the hidden elements, the folded groups and the
  * selection. Each change after a gesture of the user (a click, a key) is a step: an edit on the canvas, a hide, a fold,
  * an edit in a panel, an edit in the source. ⌘Z (Ctrl+Z) undoes, and ⇧⌘Z (Ctrl+Shift+Z, Ctrl+Y) redoes, on the canvas
  * and in the source editor. Undo puts back the selection of before the step.
  *
  * A gesture is one step: a slider drag is one step. Typing is one step until a pause. The load of a diagram is not a
  * step.
  */
class UndoOpsSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "UndoOpsSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val a = NodeId("a")
  private val c = NodeId("c")
  private val g = GroupId("cluster_x")

  private val dot = """digraph G { a -> b; subgraph cluster_x { c; d } }"""

  final class View(val state: ViewerState, val commands: Commands):
    def nodes: Set[NodeId]    = state.fullGraphNow().nodeIds
    def hidden: ElementIds    = state.metadata.hiddenElements.now()
    def folded: Set[GroupId]  = state.metadata.collapsedGroups.now()
    def text: String          = state.sourceText.now()
    /** A click or a key of the user: the next change is a new step. */
    def gesture(): Unit = state.history.markGesture()
    def key(k: String, meta: Boolean = false, shift: Boolean = false, ctrl: Boolean = false): Unit =
      val init = js.Dynamic.literal(key = k, metaKey = meta, shiftKey = shift, ctrlKey = ctrl, bubbles = true, cancelable = true)
      commands.handleKeyDown(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))
    def undoKey(): Unit = key("z", meta = true)
    def redoKey(): Unit = key("z", meta = true, shift = true)
    def select(ids: NodeId*): Unit = state.selection.set(ElementIds(ids.toSet))
    def setColor(id: NodeId, color: String): Unit =
      state.elementAttributesUpdates(ElementIds(Set(id))).set(
        AttributeUpdates(Map(AttributeId("color") -> AttrStatus.Single(AttrValue(color))))
      )

  private def withView(body: View => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state    = ViewerState(ViewTarget.diagram("undo-ops-spec"), graphviz, initialSource = Some(dot))(using owner)
        val commands = Commands(state, RouterCommands(Router()))
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(c), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(c), "precondition: the diagram did not load")
            body(View(state, commands))
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  /** Wait until `condition` is true: the parse of a new text is paced. */
  private def until(condition: => Boolean): Future[Unit] = Waits.settle(condition, timeoutMs = 3000)

  test("the load of a diagram is not a step"):
    withView: v =>
      Future(check(!v.state.history.canUndoNow, "the history can undo the load of the diagram"))

  test("⌘Z undoes a delete on the canvas, and ⇧⌘Z deletes again"):
    withView: v =>
      v.select(a)
      v.gesture()
      v.key("Backspace")
      until(!v.nodes.contains(a))
        .flatMap: _ =>
          check(!v.nodes.contains(a), "precondition: the delete did not remove a")
          v.undoKey()
          until(v.nodes.contains(a))
        .flatMap: _ =>
          check(v.nodes.contains(a), s"⌘Z did not bring a back: ${v.text}")
          v.redoKey()
          until(!v.nodes.contains(a))
        .map: _ =>
          check(!v.nodes.contains(a), s"⇧⌘Z did not delete a again: ${v.text}")

  test("Ctrl+Z undoes and Ctrl+Y redoes, as on Windows and Linux"):
    withView: v =>
      v.gesture()
      v.setColor(a, "red")
      val edited = v.text
      v.key("z", ctrl = true)
      check(!v.text.contains("red"), s"Ctrl+Z did not undo: ${v.text}")
      v.key("y", ctrl = true)
      check(v.text == edited, s"Ctrl+Y did not redo: ${v.text}")
      Future.unit

  test("a hide and a fold can be undone"):
    withView: v =>
      v.select(c)
      v.gesture()
      v.state.selection.hide()
      check(v.hidden.contains(c), "precondition: the hide did not hide c")
      v.gesture()
      v.state.toggleGroupCollapsed(g)
      check(v.folded.contains(g), "precondition: the fold did not fold the group")
      v.undoKey()
      check(!v.folded.contains(g), "undo did not unfold the group")
      check(v.hidden.contains(c), "the first undo also showed c")
      v.undoKey()
      check(!v.hidden.contains(c), "the second undo did not show c")
      Future.unit

  test("two gestures are two steps, and the changes of one gesture are one step"):
    withView: v =>
      v.gesture()
      v.setColor(a, "red")
      v.setColor(a, "green")
      v.setColor(a, "blue")
      v.gesture()
      v.setColor(c, "red")
      v.undoKey()
      check(v.text.contains("blue") && !v.text.contains("red"), s"the first undo did not revert only the second gesture: ${v.text}")
      v.undoKey()
      check(!v.text.contains("blue") && !v.text.contains("green") && !v.text.contains("red"),
        s"the drag of one gesture is more than one step: ${v.text}")
      check(!v.state.history.canUndoNow, "the history has more steps than the two gestures")
      Future.unit

  test("undo puts back the selection of before the step"):
    withView: v =>
      v.select(a)
      v.gesture()
      v.key("Backspace")
      until(!v.nodes.contains(a))
        .flatMap: _ =>
          v.select()
          v.undoKey()
          until(v.nodes.contains(a))
        .map: _ =>
          val sel = v.state.selection.now().toString
          check(sel.contains("a"), s"the selection after undo is $sel")

  test("a new change after an undo removes the redo step"):
    withView: v =>
      v.gesture()
      v.setColor(a, "red")
      v.undoKey()
      // The graph of the restored text comes after a paced parse. Until then, the
      // state refuses an edit on the canvas, so the test waits as a user does.
      until(v.state.fullGraphNow().nodes.get(a).exists(_.attributes.get(AttributeId("color")).isEmpty))
        .map: _ =>
          v.gesture()
          v.setColor(c, "green")
          check(v.text.contains("green"), s"precondition: the edit after the undo did not act: ${v.text}")
          check(!v.state.history.canRedoNow, "the redo step of the undone change is still there")

  /** The desktop shell sends the Edit menu items to the page as window events, as it does for Quit
    * (`ge:close.requested`).
    */
  private def menu(item: String): Unit =
    dom.window.dispatchEvent(new dom.CustomEvent(s"ge:menu.$item", js.Dynamic.literal().asInstanceOf[dom.CustomEventInit]))

  test("Edit → Undo and Edit → Redo of the desktop menu use the diagram history"):
    withView: v =>
      v.select(c)
      v.gesture()
      v.state.selection.hide()
      check(v.hidden.contains(c), "precondition: the hide did not hide c")
      menu("undo")
      check(!v.hidden.contains(c), "Edit → Undo did not undo the hide")
      menu("redo")
      check(v.hidden.contains(c), "Edit → Redo did not hide c again")
      Future.unit

  test("Edit → Undo in a text field is the undo of the field, not of the diagram"):
    withView: v =>
      v.select(c)
      v.gesture()
      v.state.selection.hide()
      val field = dom.document.createElement("input").asInstanceOf[dom.html.Input]
      dom.document.body.appendChild(field)
      field.focus()
      menu("undo")
      val stillHidden = v.hidden.contains(c)
      field.remove()
      check(stillHidden, "Edit → Undo in a text field undid the hide of the diagram")
      Future.unit

  test("⌘Z in the source editor uses the same history as the canvas"):
    withView: v =>
      val box  = dom.document.createElement("div")
      dom.document.body.appendChild(box)
      val root = render(box, CodeMirror(v.state))
      v.gesture()
      v.state.selection.set(ElementIds(Set(c)))
      v.state.selection.hide()
      val content = box.querySelector(".cm-content")
      check(content != null, "precondition: no source editor")
      // The undo key of the platform: ⌘Z on the Mac, Ctrl+Z on Linux (the CI). CodeMirror reads ⌘ and Ctrl
      // together as `Meta-Ctrl-z`, which is not the undo key.
      val mac  = dom.window.navigator.platform.toLowerCase.contains("mac")
      val init = js.Dynamic.literal(key = "z", metaKey = mac, ctrlKey = !mac, bubbles = true, cancelable = true)
      content.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))
      val shown = !v.hidden.contains(c)
      root.unmount()
      box.remove()
      check(shown, "⌘Z in the source editor did not undo the hide")
      Future.unit

end UndoOpsSpec
