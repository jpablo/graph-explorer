package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{Platform, TestHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.scalajs.js

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
  *
  * A click with the command key (⌘ on a Mac, Ctrl on other platforms) opens the link in the browser of the system. In
  * the desktop app, the page asks the shell with the `open_external` command. On the web, the page opens the link in a
  * new tab with `window.open`. The app window stays on the diagram in each case. Only an http or https link opens.
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

  /** Send a click to `target`, and give `defaultPrevented` as the window sees it. With `command`, the click holds the
    * command key of the platform.
    */
  private def clickPrevented(target: dom.Element, command: Boolean = false): Boolean =
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
        metaKey = command && Platform.isMac
        ctrlKey = command && !Platform.isMac
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

  // ---------------------------------------------------------------------------
  // The command key opens the link in the browser of the system.
  // ---------------------------------------------------------------------------

  /** Run `body` with a `window.open` that records its arguments and opens nothing. Give the records. */
  private def recordWindowOpen(body: => Unit): Vector[(String, String)] =
    val opened   = mutable.Buffer.empty[(String, String)]
    val window   = js.Dynamic.global.window
    val original = window.selectDynamic("open")
    val fake: js.Function3[js.Any, js.Any, js.Any, js.Any] = (url, target, _) =>
      opened += (url.toString -> target.toString)
      null
    window.updateDynamic("open")(fake)
    try body
    finally window.updateDynamic("open")(original)
    opened.toVector

  /** Run `body` with a fake desktop shell. Give the URLs of its `open_external` calls. */
  private def recordOpenExternal(body: => Unit): Vector[String] =
    val shell = FakeDesktopShell()
    shell.install()
    try body
    finally shell.uninstall()
    shell.calls.toVector.collect:
      case ("open_external", args) => args.url.asInstanceOf[String]

  test("on the web, a command-click on a node with a URL opens the link in a new tab"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape = linkedShape(canvas, a.toSvg)
        var prevented = false
        val opened    = recordWindowOpen { prevented = clickPrevented(shape, command = true) }
        check(prevented, "a command-click on the node followed its link in the app window")
        check(opened.map(_._1) == Vector(link), s"a command-click did not open the link once in a new tab: $opened")
        check(opened.forall(_._2 == "_blank"), s"the link did not open in a new tab: $opened")

  test("in the desktop app, a command-click on a node with a URL asks the shell to open the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape     = linkedShape(canvas, a.toSvg)
        var prevented = false
        var external  = Vector.empty[String]
        val opened = recordWindowOpen:
          external = recordOpenExternal { prevented = clickPrevented(shape, command = true) }
        check(prevented, "a command-click on the node followed its link in the app window")
        check(external == Vector(link), s"the page did not ask the shell once to open the link: $external")
        check(opened.isEmpty, s"the desktop page also called window.open: $opened")

  test("a command-click on an arrow with a URL opens the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a -> b [URL="$link", label="go"] }""", a): canvas =>
      afterMicrotasks:
        val edge = canvas.svg.querySelectorAll("g.edge").toSeq.headOption
        check(edge.isDefined, "precondition: the canvas has no arrow")
        val shape  = linkedShape(canvas, edge.get.id)
        val opened = recordWindowOpen(clickPrevented(shape, command = true))
        check(opened.map(_._1) == Vector(link), s"a command-click on the arrow did not open the link once: $opened")

  test("a click with no command key does not open the link"):
    val a = NodeId("a")
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape    = linkedShape(canvas, a.toSvg)
        var external = Vector.empty[String]
        val opened = recordWindowOpen:
          external = recordOpenExternal(clickPrevented(shape))
        check(opened.isEmpty && external.isEmpty, s"a plain click opened the link: window.open=$opened shell=$external")

  test("a command-click does not open a link that is not http or https"):
    val a = NodeId("a")
    withCanvas("""digraph { a [shape=box, URL="javascript:alert(1)"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val shape     = linkedShape(canvas, a.toSvg)
        var prevented = false
        val opened    = recordWindowOpen { prevented = clickPrevented(shape, command = true) }
        check(prevented, "a command-click on the node followed its link in the app window")
        check(opened.isEmpty, s"a command-click opened a link that is not http or https: $opened")
