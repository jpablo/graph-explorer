package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{Val, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.jpablo.graphexplorer.viewer.utils.KeyEvents.keyDown
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The command key of the platform + B shows or hides the Library, as the
  * "Toggle Library" button of the toolbar does: ⌘B on a Mac, Ctrl+B on
  * Windows and Linux.
  *
  * The key works from each place of the page, also from a text field such as
  * the source editor. The canvas has its own key listener, but it does not
  * see a key that the focus sends to the editor or to the Library.
  */
class LibraryShortcutSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LibraryShortcutSpec"

  override def munitFixtures = List(mockStorageFixture())

  private final class Page(val state: ViewerState, val box: dom.Element, val field: dom.html.TextArea):
    def visible: Boolean = state.leftPanelVisible.now()

    /** Press a key in the text field, and give back the event. */
    def press(key: String, meta: Boolean = false, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
      val event = keyDown(key, meta, ctrl, shift, alt)
      field.dispatchEvent(event)
      event

    /** The tooltip of the "Toggle Library" button. */
    def tip: String =
      Option(box.querySelector("#toggle-library"))
        .flatMap(e => Option(e.closest("[data-tip]")).orElse(Option(e.querySelector("[data-tip]"))))
        .map(_.getAttribute("data-tip"))
        .getOrElse(box.querySelector("#toggle-library").outerHTML)

  /** Mount the toolbar of a browser diagram and a text field, and run `body`. */
  private def withToolbar(isMac: Boolean)(body: Page => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram("library-shortcut-spec"), graphviz)(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div")
      val field    = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
      dom.document.body.appendChild(box)
      dom.document.body.appendChild(field)
      val root = render(box, Toolbar(Val("Diagram"), commands, state, isMac))
      field.focus()
      afterMicrotasks(())
        .flatMap(_ => body(Page(state, box, field)))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          field.parentNode.removeChild(field)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  test("on a Mac, Cmd+B hides the Library and then shows it again"):
    withToolbar(isMac = true): page =>
      val before = page.visible
      val first  = page.press("b", meta = true)
      check(first.defaultPrevented, "on a Mac, the toolbar did not take Cmd+B")
      check(page.visible == !before, s"Cmd+B did not toggle the Library: visible is ${page.visible}, before $before")
      page.press("b", meta = true)
      check(page.visible == before, "a second Cmd+B did not put the Library back")
      Future.unit

  test("on a Mac, Ctrl+B, Shift+Cmd+B, and Alt+Cmd+B do not toggle the Library"):
    withToolbar(isMac = true): page =>
      val before = page.visible
      val taken = List(
        "Ctrl+B"      -> page.press("b", ctrl = true),
        "Shift+Cmd+B" -> page.press("B", meta = true, shift = true),
        "Alt+Cmd+B"   -> page.press("b", meta = true, alt = true)
      ).collect { case (name, event) if event.defaultPrevented => name }
      same(taken, Nil, "the toolbar took a key that is not Cmd+B")
      check(page.visible == before, "a key that is not Cmd+B toggled the Library")
      Future.unit

  test("on Windows and Linux, Ctrl+B toggles the Library, and Meta+B does not"):
    withToolbar(isMac = false): page =>
      val before = page.visible
      val metaB  = page.press("b", meta = true)
      check(!metaB.defaultPrevented, "off a Mac, the toolbar took Meta+B")
      check(page.visible == before, "off a Mac, Meta+B toggled the Library")
      val ctrlB = page.press("b", ctrl = true)
      check(ctrlB.defaultPrevented, "off a Mac, the toolbar did not take Ctrl+B")
      check(page.visible == !before, "off a Mac, Ctrl+B did not toggle the Library")
      Future.unit

  test("the tooltip of the Toggle Library button shows the key"):
    withToolbar(isMac = true): mac =>
      check(mac.tip.contains("⌘B"), s"on a Mac, the tooltip does not show ⌘B: ${mac.tip}")
      Future.unit
    .flatMap: _ =>
      withToolbar(isMac = false): other =>
        check(other.tip.contains("Ctrl+B"), s"off a Mac, the tooltip does not show Ctrl+B: ${other.tip}")
        Future.unit
