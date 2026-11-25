package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

/** A click on an element with a `URL` attribute does not open the link in the app window.
  *
  * Graphviz puts a node or an arrow with a `URL` (or `href`) attribute in an svg `<a>` element. The browser follows
  * the link of an `<a>` element when you click it. In the desktop app, the window then showed the linked page (a
  * GitHub 404 page in the report), and the diagram was gone. The window has no back button. On the web, the tab went
  * away from the app.
  *
  * A click on the canvas must select the element. It must not follow the link.
  *
  * Each test sends a cancelable `click` event to the shape in the link. A listener on the window gets the event last.
  * It records `defaultPrevented`, and then it cancels the event itself, so that the test page does not follow the link
  * when the canvas does not cancel it.
  */
class CanvasLinkSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix = "CanvasLinkSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "canvas-link-spec"

  override def munitFixtures = List(mockStorageFixture())

  private val link = "https://example.com/source.scala#L259"

  /** Send a click to `target`, and give `defaultPrevented` as the window sees it. */
  private def clickPrevented(target: dom.Element): Boolean =
    var prevented = Option.empty[Boolean]
    val guard: scala.scalajs.js.Function1[dom.Event, Unit] = ev =>
      prevented = Some(ev.defaultPrevented)
      ev.preventDefault()
    dom.window.addEventListener("click", guard)
    try
      val init = new dom.MouseEventInit:
        bubbles = true
        cancelable = true
        button = 0
      target.dispatchEvent(new dom.MouseEvent("click", init))
    finally dom.window.removeEventListener("click", guard)
    check(prevented.isDefined, "precondition: the click did not get to the window")
    prevented.get

  /** The shape in the svg link of the element whose group has the id `svgId`. */
  private def linkedShape(canvas: TestCanvas, svgId: String): dom.Element =
    val group = canvas.svg.querySelectorAll("g.node, g.edge").toSeq.find(_.id == svgId)
    check(group.isDefined, s"precondition: the canvas has no group with the id $svgId")
    val anchor = Option(group.get.querySelector("a"))
    check(anchor.isDefined, s"precondition: the group $svgId has no svg link: ${group.get.outerHTML.take(400)}")
    val shape = Option(anchor.get.querySelector("polygon, path, ellipse, text"))
    check(shape.isDefined, s"precondition: the link of $svgId has no shape: ${anchor.get.outerHTML.take(400)}")
    shape.get

  test("a click on a node with a URL does not follow the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape = linkedShape(canvas, a.toSvg)
        check(clickPrevented(shape), "a click on the node followed its link: the app window shows the linked page")

  test("a click on an arrow with a URL does not follow the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a -> b [URL="$link", label="go"] }""", a): canvas =>
      afterMicrotasks:
        val edge = canvas.svg.querySelectorAll("g.edge").toSeq.headOption
        check(edge.isDefined, "precondition: the canvas has no arrow")
        val shape = linkedShape(canvas, edge.get.id)
        check(clickPrevented(shape), "a click on the arrow followed its link: the app window shows the linked page")

  test("a click on a node with an href attribute does not follow the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a [href="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape = linkedShape(canvas, a.toSvg)
        check(clickPrevented(shape), "a click on the node followed its href link")
