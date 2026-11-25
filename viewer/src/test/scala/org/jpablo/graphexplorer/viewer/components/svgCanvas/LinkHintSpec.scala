package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{Platform, TestHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** The canvas shows which elements have a link, and what the link is.
  *
  *   - A link icon (`gx-link-badge`) shows at the top-left corner of a linked node while the pointer is on the node or
  *     on the icon. For an arrow, the icon is at the top-right corner of its label.
  *   - A strip at the bottom-left corner of the canvas (`#gx-link-status`) shows the URL and the hint "click to open"
  *     while the pointer is on a linked element or on its icon. A browser does the same for a link.
  *   - A click on the icon opens the link, with no command key.
  *   - While the command key (⌘ on a Mac, Ctrl on other platforms) is down, the canvas has the class
  *     `gx-links-revealed`. The stylesheet then shows the icon of each linked element, and gives a linked element the
  *     hand pointer. A browser does not send a key-up when the window loses the focus (⌘-Tab), so a blur and a mouse
  *     event with no command key also end the state.
  *
  * Only an http or https link gets the icon and the strip, because only such a link opens.
  */
class LinkHintSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix = "LinkHintSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "link-hint-spec"

  override def munitFixtures = List(mockStorageFixture())

  private val link  = "https://example.com/source.scala#L259"
  private val a     = NodeId("a")
  private val badge = "g.gx-link-badge"

  private def badges(canvas: TestCanvas): Seq[dom.Element] = canvas.svg.querySelectorAll(badge).toSeq

  private def isActive(el: dom.Element): Boolean = el.classList.contains("gx-badge-active")

  /** The text of the URL strip, or None when the strip does not show. */
  private def status(canvas: TestCanvas): Option[String] =
    Option(canvas.box.querySelector("#gx-link-status")).map(_.textContent)

  private def mouse(kind: String, target: dom.Element): Unit =
    val init = new dom.MouseEventInit:
      bubbles = true
      cancelable = true
    target.dispatchEvent(new dom.MouseEvent(kind, init))

  /** The pointer moves onto `target`. */
  private def hover(target: dom.Element): Unit = mouse("mouseover", target)

  /** The shape of the node `id`. */
  private def nodeShape(canvas: TestCanvas, id: NodeId): dom.Element =
    val group = canvas.nodeGroup(id)
    check(group.isDefined, s"precondition: the canvas has no node $id")
    val shape = Option(group.get.querySelector("polygon, ellipse, path"))
    check(shape.isDefined, s"precondition: the node $id has no shape")
    shape.get

  /** Run `body` with a `window.open` that records the URL and opens nothing. Give the URLs. */
  private def recordWindowOpen(body: => Unit): Vector[String] =
    val opened   = mutable.Buffer.empty[String]
    val window   = js.Dynamic.global.window
    val original = window.selectDynamic("open")
    val fake: js.Function3[js.Any, js.Any, js.Any, js.Any] = (url, _, _) =>
      opened += url.toString
      null
    window.updateDynamic("open")(fake)
    try body
    finally window.updateDynamic("open")(original)
    opened.toVector

  test("a node with a URL gets one link icon, and a node with no URL gets none"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; b [shape=box]; a -> b }""", a): canvas =>
      afterMicrotasks:
        check(badges(canvas).size == 1, s"the canvas has ${badges(canvas).size} link icons, not 1")

  test("the link icon is at the top-left corner of its node"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        check(badges(canvas).size == 1, "precondition: the node has no link icon")
        hover(nodeShape(canvas, a))
        val icon = badges(canvas).head.getBoundingClientRect()
        val node = nodeShape(canvas, a).getBoundingClientRect()
        val (cx, cy) = (icon.left + icon.width / 2, icon.top + icon.height / 2)
        check(
          math.abs(cx - node.left) < 2 && math.abs(cy - node.top) < 2,
          f"the center of the icon is at ($cx%.1f, $cy%.1f), not at the corner (${node.left}%.1f, ${node.top}%.1f)"
        )

  test("the link icon shows only while the pointer is on its node"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val icon = badges(canvas).headOption
        check(icon.isDefined, "precondition: the node has no link icon")
        check(!isActive(icon.get), "the link icon shows before the pointer is on the node")
        hover(nodeShape(canvas, a))
        check(isActive(icon.get), "the link icon does not show while the pointer is on the node")
        hover(nodeShape(canvas, NodeId("b")))
        check(!isActive(icon.get), "the link icon still shows when the pointer is on a different node")

  test("the strip shows the URL while the pointer is on a linked node"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        check(status(canvas).isEmpty, s"the strip shows before the pointer is on the node: ${status(canvas)}")
        hover(nodeShape(canvas, a))
      .flatMap: _ =>
        afterMicrotasks:
          val text = status(canvas)
          check(text.exists(_.contains(link)), s"the strip does not show the URL: $text")
          check(text.exists(_.contains("click to open")), s"the strip does not show the hint: $text")
          mouse("mouseleave", canvas.svg)
      .flatMap: _ =>
        afterMicrotasks:
          check(status(canvas).isEmpty, s"the strip still shows after the pointer left the drawing: ${status(canvas)}")

  test("the pointer on the link icon keeps the icon and the strip"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val icon = badges(canvas).headOption
        check(icon.isDefined, "precondition: the node has no link icon")
        hover(nodeShape(canvas, a))
        hover(icon.get.querySelector("circle"))
        check(isActive(icon.get), "the link icon goes away when the pointer moves onto it")
      .flatMap: _ =>
        afterMicrotasks:
          check(status(canvas).exists(_.contains(link)), s"the strip does not show the URL: ${status(canvas)}")

  test("a click on the link icon opens the link, with no command key, and does not change the selection"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        val icon = badges(canvas).headOption
        check(icon.isDefined, "precondition: the node has no link icon")
        hover(nodeShape(canvas, a))
        val before = canvas.state.selection.now().toString
        val opened = recordWindowOpen:
          mouse("mousedown", icon.get)
          mouse("mouseup", icon.get)
          mouse("click", icon.get)
        check(opened == Vector(link), s"a click on the icon did not open the link once: $opened")
        check(canvas.state.selection.now().toString == before, "a click on the icon changed the selection")

  test("an arrow with a URL gets a link icon, and the strip shows its URL"):
    withCanvas(s"""digraph { a -> b [URL="$link", label="go"] }""", a): canvas =>
      afterMicrotasks:
        check(badges(canvas).size == 1, s"the arrow has ${badges(canvas).size} link icons, not 1")
        val edge = canvas.svg.querySelector("g.edge path")
        check(edge != null, "precondition: the canvas has no arrow")
        hover(edge)
        check(isActive(badges(canvas).head), "the link icon of the arrow does not show while the pointer is on it")
      .flatMap: _ =>
        afterMicrotasks:
          check(status(canvas).exists(_.contains(link)), s"the strip does not show the URL of the arrow: ${status(canvas)}")

  test("a link that is not http or https gets no icon and no strip"):
    withCanvas("""digraph { a [shape=box, URL="javascript:alert(1)"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        check(badges(canvas).isEmpty, "a javascript: link got a link icon")
        hover(nodeShape(canvas, a))
      .flatMap: _ =>
        afterMicrotasks:
          check(status(canvas).isEmpty, s"the strip shows a javascript: link: ${status(canvas)}")

  // ---------------------------------------------------------------------------
  // Hold the command key to show all the links.
  // ---------------------------------------------------------------------------

  private val revealed = "gx-links-revealed"

  private def container(canvas: TestCanvas): dom.Element =
    val c = canvas.box.querySelector("#canvas-container")
    check(c != null, "precondition: the canvas has no #canvas-container")
    c

  private def isRevealed(canvas: TestCanvas): Boolean = container(canvas).classList.contains(revealed)

  private val commandName = if Platform.isMac then "Meta" else "Control"

  /** A key event on the document for the command key of the platform (or for `key`). */
  private def key(kind: String, down: Boolean, keyName: String = commandName): Unit =
    val init = new dom.KeyboardEventInit:
      bubbles = true
      cancelable = true
    init.key = keyName
    init.metaKey = down && keyName == "Meta"
    init.ctrlKey = down && keyName == "Control"
    dom.document.dispatchEvent(new dom.KeyboardEvent(kind, init))

  private def mouseMove(target: dom.Element, command: Boolean): Unit =
    val init = new dom.MouseEventInit:
      bubbles = true
      cancelable = true
    init.metaKey = command && Platform.isMac
    init.ctrlKey = command && !Platform.isMac
    target.dispatchEvent(new dom.MouseEvent("mousemove", init))

  test("while the command key is down, the canvas shows all the links"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        check(!isRevealed(canvas), "the links show before the command key is down")
        key("keydown", down = true)
      .flatMap: _ =>
        afterMicrotasks:
          check(isRevealed(canvas), "the links do not show while the command key is down")
          key("keyup", down = false)
      .flatMap: _ =>
        afterMicrotasks:
          check(!isRevealed(canvas), "the links still show after the command key is up")

  test("a different key does not show the links"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        key("keydown", down = true, keyName = "Shift")
        key("keydown", down = true, keyName = if Platform.isMac then "Control" else "Meta")
      .flatMap: _ =>
        afterMicrotasks:
          check(!isRevealed(canvas), "a key that is not the command key shows the links")

  test("the links go away when the window loses the focus while the command key is down"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        key("keydown", down = true)
      .flatMap: _ =>
        afterMicrotasks:
          check(isRevealed(canvas), "precondition: the links do not show while the command key is down")
          dom.window.dispatchEvent(new dom.Event("blur"))
      .flatMap: _ =>
        afterMicrotasks:
          check(!isRevealed(canvas), "the links still show after the window lost the focus")

  test("a mouse move gives the state of the command key, also when a key event is missing"):
    withCanvas(s"""digraph { a [shape=box, URL="$link"]; a -> b }""", a): canvas =>
      afterMicrotasks:
        mouseMove(nodeShape(canvas, a), command = true)
      .flatMap: _ =>
        afterMicrotasks:
          check(isRevealed(canvas), "a mouse move with the command key does not show the links")
          mouseMove(nodeShape(canvas, a), command = false)
      .flatMap: _ =>
        afterMicrotasks:
          check(!isRevealed(canvas), "a mouse move with no command key does not end the state")
