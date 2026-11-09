package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.attributes.views.DiagramAttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{CellPort, SameHead, SameTail, TailTooltip, TailURL}
import org.jpablo.graphexplorer.viewer.layout3d.ForceLayout3D
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The text that a person sees says "arrow", not "edge" (CONTEXT.md).
  *
  * CONTEXT.md gives "arrow" for a connection from one node to another, and it
  * lists "edge" as a word to avoid. The CSS classes, the DOM ids, the `data-`
  * attributes and the names of the DOT grammar keep "edge", because a person
  * does not read them.
  */
class ArrowWordingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ArrowWordingSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val Edge = "(?i)\\bedges?\\b".r

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  test("the placeholders of the attributes and the labels of the 3D layout say arrow"):
    val texts = Vector(SameHead, SameTail, TailTooltip, TailURL, CellPort).map(_.placeholderText) ++
      ForceLayout3D.knobs.map(_.label)
    for text <- texts do check(Edge.findFirstIn(text).isEmpty, s"a person reads 'edge' in: $text")
    check(texts.exists(_.toLowerCase.contains("arrow")), s"no text says arrow: $texts")

  test("the help dialog and the attributes of the diagram say arrow"):
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("arrow-wording"), graphviz)
      val root = render(
        container,
        div(HelpDialog(Var(true), Commands(state, RouterCommands(Router()))), DiagramAttributesView(state))
      )
      after(100).map: _ =>
        val text = container.textContent
        root.unmount()
        check(Edge.findFirstIn(text).isEmpty, s"a person reads '${Edge.findFirstIn(text).getOrElse("")}' in: $text")
        check(text.contains("Create a new arrow between nodes"), s"the help dialog does not describe the arrow drag: $text")
        check(text.contains("Arrows"), s"the attributes of the diagram have no Arrows section: $text")
    .transform: result =>
      container.parentNode.removeChild(container)
      result
