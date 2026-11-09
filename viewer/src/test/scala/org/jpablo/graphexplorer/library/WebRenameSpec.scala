package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.RenameDiagramDialog
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The rename dialog of the WEB app renames a browser diagram.
  *
  * A browser diagram has no file, so the web app keeps a stored name for it.
  * The name is not metadata (CONTEXT.md). This spec types a name into the
  * dialog, clicks Ok, and reads what reached `localStorage`: the payload keeps
  * the shape that `BrowserPayloadSpec` pins, and the index entry has the new
  * name.
  */
class WebRenameSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "WebRenameSpec"

  override def munitFixtures = List(mockStorageFixture())

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  private def payloadOf(id: String): String =
    dom.window.localStorage.getItem(s"[StoredString]graph-explorer.project.$id")

  test("the rename dialog of a browser diagram stores the new name, in the same JSON as before"):
    BrowserLibrary.indexNow().diagrams.foreach(d => BrowserLibrary.deleteDiagram(d.id))
    val id        = BrowserLibrary.createDiagram("Old name", "digraph G { a }")
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Diagram(id), graphviz)
      val root  = render(container, RenameDiagramDialog(state))
      after(50)
        .flatMap: _ =>
          same(state.storedName.now(), "Old name", "precondition: the viewer has the stored name")
          state.renameDialogOpen.set(true)
          after(50)
        .flatMap: _ =>
          val input = container.querySelector("input").asInstanceOf[dom.html.Input]
          check(input != null, "the dialog has no input")
          same(input.value, "Old name", "the dialog does not start with the stored name")
          input.value = "New name"
          input.dispatchEvent(new dom.Event("input", new dom.EventInit { bubbles = true }))
          val ok = container.querySelectorAll("button").toSeq.find(_.textContent.trim == "Ok")
          check(ok.isDefined, s"the dialog has no Ok button: ${container.innerHTML}")
          ok.foreach(_.asInstanceOf[dom.html.Button].click())
          after(50)
        .map: _ =>
          root.unmount()
          same(state.storedName.now(), "New name", "the viewer does not have the new name")
          same(
            payloadOf(id.value),
            """{"projectName":"New name","source":"digraph G { a }","format":"DOT","autoDetectFormat":true}""",
            "the payload is not the pinned shape with the new name"
          )
          same(
            BrowserLibrary.indexNow().diagrams.find(_.id == id).map(_.name),
            Some("New name"),
            "the index entry does not have the new name"
          )
    .transform: result =>
      container.parentNode.removeChild(container)
      result
