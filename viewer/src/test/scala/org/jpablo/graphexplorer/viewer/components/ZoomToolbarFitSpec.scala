package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.{FitMode, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The zoom bar has a button for each fit: Fit, Fit width and Fit height. The tooltip of each button gives its key:
  * f, w and ⇧H.
  */
class ZoomToolbarFitSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ZoomToolbarFitSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private def withBar(body: (dom.Element, ArrayBuffer[FitMode]) => Unit): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram("zoom-toolbar-fit-spec"), graphviz)(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val fits     = ArrayBuffer.empty[FitMode]
      state.fitDiagram.events.foreach(fits += _)(using owner)
      val root = render(box, ZoomToolbar(state, commands))
      afterMicrotasks(body(box, fits))
        .transform: outcome =>
          root.unmount()
          box.remove()
          owner.killSubscriptions()
          outcome

  private def button(box: dom.Element, id: String): dom.html.Element =
    val el = box.querySelector(s"#$id")
    check(el != null, s"the zoom bar has no #$id: ${box.innerHTML.take(400)}")
    el.asInstanceOf[dom.html.Element]

  private def tip(el: dom.Element): String =
    Option(el.closest("[data-tip]")).map(_.getAttribute("data-tip")).getOrElse("")

  test("the Fit width and Fit height buttons fit one axis"):
    withBar: (box, fits) =>
      button(box, "zoom-fit-width").click()
      button(box, "zoom-fit-height").click()
      button(box, "zoom-fit").click()
      check(fits.toList == List(FitMode.Width, FitMode.Height, FitMode.Page), s"the fits are ${fits.toList}")

  test("the tooltips of the fit buttons give their keys"):
    withBar: (box, _) =>
      val fit    = tip(button(box, "zoom-fit"))
      val width  = tip(button(box, "zoom-fit-width"))
      val height = tip(button(box, "zoom-fit-height"))
      check(fit.endsWith("(f)"), s"the Fit tooltip is '$fit'")
      check(width.startsWith("Fit width") && width.endsWith("(w)"), s"the Fit width tooltip is '$width'")
      check(height.startsWith("Fit height") && height.contains("h)"), s"the Fit height tooltip is '$height'")

end ZoomToolbarFitSpec
