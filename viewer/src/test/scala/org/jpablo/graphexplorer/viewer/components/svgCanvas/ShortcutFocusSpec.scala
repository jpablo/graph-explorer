package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.state.{FitMode, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The keys of the canvas (w, f, ⇧H, h and the others) work when the canvas does not have the focus.
  *
  * Bug: after a diagram opened, w did nothing. The person had to click the diagram first. The canvas listened for
  * keys only on itself, and after the open the focus was on the body of the page. A click on a button of a toolbar
  * also moved the focus away from the canvas.
  *
  * A text field keeps its keys, and a focused button keeps Enter (the browser clicks the button).
  */
class ShortcutFocusSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ShortcutFocusSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private final class Page(val state: ViewerState, val box: dom.html.Element, val fits: ArrayBuffer[FitMode]):
    /** A key as the browser sends it: to the element with the focus, or to the body when no element has it. */
    def press(target: dom.EventTarget, k: String): dom.KeyboardEvent =
      val init = js.Dynamic.literal(key = k, bubbles = true, cancelable = true)
      val ev   = new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit])
      target.dispatchEvent(ev)
      ev

    def addElement(tag: String): dom.html.Element =
      val el = dom.document.createElement(tag).asInstanceOf[dom.html.Element]
      dom.document.body.appendChild(el)
      el

  private def withCanvas(id: String)(body: Page => Unit): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram(id), graphviz, _ => (), initialSource = Some("digraph G { a -> b }"))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val fits     = ArrayBuffer.empty[FitMode]
      state.fitDiagram.events.foreach(fits += _)(using owner)
      val box = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root = render(box, CanvasContainer(state, commands))
      Waits
        .settle(box.querySelectorAll("svg g.node").length == 2, timeoutMs = 10000)
        .map: _ =>
          check(box.querySelectorAll("svg g.node").length == 2, s"$id: precondition: the canvas shows no drawing")
          body(Page(state, box, fits))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  test("after a diagram opens, w fits the width with no click on the canvas"):
    withCanvas("shortcut-focus-open"): p =>
      // After an open, no element has the focus: the browser sends the key to the body.
      Option(dom.document.activeElement).collect { case el: dom.html.Element => el }.foreach(_.blur())
      check(dom.document.activeElement eq dom.document.body, s"precondition: the focus is on ${dom.document.activeElement}")
      val ev = p.press(dom.document.body, "w")
      check(p.fits.toList == List(FitMode.Width), s"w with the focus on the body gave the fits ${p.fits.toList}")
      check(ev.defaultPrevented, "w with the focus on the body was not handled")

  test("after a click on a toolbar button, w fits the width"):
    withCanvas("shortcut-focus-button"): p =>
      val button = p.addElement("button")
      button.focus()
      p.press(button, "w")
      button.remove()
      check(p.fits.toList == List(FitMode.Width), s"w with the focus on a button gave the fits ${p.fits.toList}")

  test("a focused button keeps Enter"):
    withCanvas("shortcut-focus-enter"): p =>
      val button = p.addElement("button")
      button.focus()
      val ev = p.press(button, "Enter")
      button.remove()
      check(!ev.defaultPrevented, "Enter on a focused button was taken by a shortcut, so the button does not click")

  test("a text field keeps its keys"):
    withCanvas("shortcut-focus-input"): p =>
      val field = p.addElement("input")
      field.focus()
      val ev = p.press(field, "w")
      field.remove()
      check(p.fits.isEmpty, s"w in a text field gave the fits ${p.fits.toList}")
      check(!ev.defaultPrevented, "w in a text field was taken by a shortcut")

  test("w on the canvas still fits the width one time"):
    withCanvas("shortcut-focus-canvas"): p =>
      val canvas = p.box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]
      canvas.focus()
      p.press(canvas, "w")
      check(p.fits.toList == List(FitMode.Width), s"w on the canvas gave the fits ${p.fits.toList}")

end ShortcutFocusSpec
