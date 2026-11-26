package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{Val, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The Undo and Redo buttons of the toolbar show the state of the history.
  *
  * A button is disabled when the history has no step for it. The tooltip names the step: "Undo Hide", "Redo Hide".
  * With no step, the tooltip says "Nothing to undo" or "Nothing to redo".
  */
class UndoButtonsSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "UndoButtonsSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val c = NodeId("c")

  private final class Page(val state: ViewerState, val box: dom.Element):
    private def button(id: String): dom.html.Button =
      val el = box.querySelector(s"#$id")
      check(el != null, s"the toolbar has no #$id: ${box.innerHTML.take(400)}")
      el.asInstanceOf[dom.html.Button]
    def undo: dom.html.Button = button("toolbar-undo")
    def redo: dom.html.Button = button("toolbar-redo")
    def tip(b: dom.html.Button): String =
      Option(b.closest("[data-tip]")).map(_.getAttribute("data-tip")).getOrElse("")
    def hideC(): Unit =
      state.selection.set(ElementIds(Set(c)))
      state.history.markGesture()
      state.selection.hide()

  private def withToolbar(body: Page => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val dot      = "digraph G { a -> b; c }"
        val state    = ViewerState(ViewTarget.diagram("undo-buttons-spec"), graphviz, initialSource = Some(dot))(using owner)
        val commands = Commands(state, RouterCommands(Router()))
        val root     = render(box, Toolbar(Val("Diagram"), commands, state))
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(c), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(c), "precondition: the diagram did not load")
            body(Page(state, box))
          .transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  test("with no step, the two buttons are disabled and say that there is nothing to do"):
    withToolbar: p =>
      check(p.undo.disabled, "the Undo button is enabled with no step")
      check(p.redo.disabled, "the Redo button is enabled with no step")
      check(p.tip(p.undo).startsWith("Nothing to undo"), s"the Undo tooltip is '${p.tip(p.undo)}'")
      check(p.tip(p.redo).startsWith("Nothing to redo"), s"the Redo tooltip is '${p.tip(p.redo)}'")
      Future.unit

  test("after a hide, Undo is enabled and its tooltip names the step"):
    withToolbar: p =>
      p.hideC()
      check(!p.undo.disabled, "the Undo button is disabled after a hide")
      check(p.redo.disabled, "the Redo button is enabled with no undone step")
      check(p.tip(p.undo).startsWith("Undo Hide"), s"the Undo tooltip is '${p.tip(p.undo)}'")
      check(p.undo.getAttribute("aria-label").startsWith("Undo Hide"), s"the Undo label is '${p.undo.getAttribute("aria-label")}'")
      Future.unit

  test("a click on Undo moves the step to Redo"):
    withToolbar: p =>
      p.hideC()
      p.undo.click()
      check(!p.state.metadata.hiddenElements.now().contains(c), "the click on Undo did not show c")
      check(p.undo.disabled, "the Undo button is enabled after the only step is undone")
      check(!p.redo.disabled, "the Redo button is disabled after an undo")
      check(p.tip(p.redo).startsWith("Redo Hide"), s"the Redo tooltip is '${p.tip(p.redo)}'")
      Future.unit

end UndoButtonsSpec
