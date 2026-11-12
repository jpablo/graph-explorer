package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot.minimalGraphText
import org.jpablo.graphexplorer.viewer.utils.{Platform, TestHelpers}
import org.jpablo.graphexplorer.viewer.utils.KeyEvents.keyDown
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The ⌘K palette (`CommandsPanel`), as a person uses it: a click on a row,
  * the arrow keys, Enter, and the shortcut that focuses the search box.
  */
class CommandsPanelSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "CommandsPanelSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** A palette in the document, with the routes that its router went to. */
  private final class Palette(
      val box:    dom.Element,
      val field:  dom.html.TextArea,
      val routes: mutable.Buffer[Route],
      catcher:    js.Dynamic
  ):

    /** The errors that the listeners of the palette threw. */
    def listenerErrors: List[String] = catcher.caught.asInstanceOf[js.Array[String]].toList

    def input: dom.html.Input = box.querySelector("input[type=search]").asInstanceOf[dom.html.Input]

    /** The command rows, in the order of the page. The section titles are not rows. */
    def rows: Seq[dom.Element] = box.querySelectorAll("ul.menu a").toSeq

    def labels: Seq[String] = rows.map(_.firstElementChild.textContent)

    /** The positions of the rows that show the highlight. */
    def highlighted: Seq[Int] =
      rows.zipWithIndex.collect { case (row, i) if row.classList.contains("menu-active") => i }

    /** Type `term` in the search box. */
    def search(term: String): Unit =
      input.value = term
      input.dispatchEvent(dom.Event("input", new dom.EventInit { bubbles = true }))

    /** Press `key` in the search box. */
    def press(key: String): Unit = input.dispatchEvent(keyDown(key))

    /** The keys that the search box shows for the palette shortcut. */
    def hint: String =
      input.parentElement.querySelectorAll(":scope > kbd").toSeq.map(_.textContent).mkString(" ")

  /** Catch the errors that the listeners of the page throw, until `restore`.
    *
    * A DOM listener that throws does not throw to `dispatchEvent`. The browser
    * sends the error to `window` as an `error` event, and the test runner
    * stops the whole run for that event. Thus each listener that an element
    * adds after this call goes in a wrapper. The wrapper keeps the error in
    * `caught`, and the error does not go to `window`.
    *
    * Unmount the palette before `restore`: the removal of a listener must
    * find its wrapper.
    */
  private def catchListenerErrors(): js.Dynamic =
    js.eval(
      """(function() {
        |  var proto = EventTarget.prototype;
        |  var add = proto.addEventListener, remove = proto.removeEventListener;
        |  var wrappers = new WeakMap(), caught = [];
        |  function wrap(fn) {
        |    var w = wrappers.get(fn);
        |    if (!w) {
        |      w = function(event) {
        |        try { return fn.call(this, event); } catch (error) { caught.push(String(error)); }
        |      };
        |      wrappers.set(fn, w);
        |    }
        |    return w;
        |  }
        |  proto.addEventListener = function(type, fn, options) {
        |    var own = this instanceof Element && typeof fn === 'function';
        |    return add.call(this, type, own ? wrap(fn) : fn, options);
        |  };
        |  proto.removeEventListener = function(type, fn, options) {
        |    var w = typeof fn === 'function' ? wrappers.get(fn) : undefined;
        |    return remove.call(this, type, w || fn, options);
        |  };
        |  return {
        |    caught: caught,
        |    restore: function() { proto.addEventListener = add; proto.removeEventListener = remove; }
        |  };
        |})()""".stripMargin
    ).asInstanceOf[js.Dynamic]

  /** Mount the palette of a browser diagram, and run `body` after the mount.
    * The end of the test removes the palette, and puts the URL back.
    *
    * `isMac` selects the platform of the palette. The page also has a text
    * field outside the palette, as the source editor is.
    */
  private def withPalette(isMac: Boolean = Platform.isMac)(body: Palette => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val catcher  = catchListenerErrors()
      val router   = Router()
      val routes   = mutable.Buffer.empty[Route]
      router.currentRoute.changes.foreach(routes += _)(using owner)
      val state    = ViewerState(ViewTarget.diagram("commands-panel-spec"), graphviz)(using owner)
      val commands = Commands(state, RouterCommands(router))
      val box      = dom.document.createElement("div")
      val field    = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
      dom.document.body.appendChild(box)
      dom.document.body.appendChild(field)
      val root = render(box, CommandsPanel(state, commands, isMac))
      afterMicrotasks(())
        .flatMap(_ => body(Palette(box, field, routes, catcher)))
        .transform: outcome =>
          root.unmount()
          catcher.restore()
          box.parentNode.removeChild(box)
          field.parentNode.removeChild(field)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  private def openedDiagrams(palette: Palette): Seq[Route.Diagram] =
    palette.routes.toSeq.collect { case route: Route.Diagram => route }

  // ---------------------------------------------------------------------------
  // Bug 1: "Create new diagram" throws when the palette runs it.
  // ---------------------------------------------------------------------------

  // The palette runs each command with `cmd.execute()`, with no argument.
  // Before the fix, `createDiagram` was a command of one argument, and
  // `CmdAction.execute` threw IllegalArgumentException("argument required").
  // The new diagram must open with the text that the Create Diagram buttons
  // give it (`minimalGraphText`).
  // Failure before the fix: the click gives an error with "argument
  // required", and no new diagram opens.
  test("a click on Create new diagram in the palette opens a new diagram"):
    withPalette(): palette =>
      val row = palette.box.querySelector("#cmd-create-new-diagram")
      check(row != null, s"precondition: the palette has no row for Create new diagram: ${palette.labels}")
      row.dispatchEvent(dom.MouseEvent("mousedown", new dom.MouseEventInit { bubbles = true; cancelable = true }))
      afterMicrotasks:
        same(palette.listenerErrors, Nil, "Create new diagram threw when the palette ran it")
        check(openedDiagrams(palette).nonEmpty, s"the palette did not open a new diagram: ${palette.routes}")
        same(
          openedDiagrams(palette).map(_.source),
          Seq(Some(minimalGraphText)),
          "the new diagram does not open with the text that the Create Diagram buttons give"
        )

  // The same command, from the keyboard: Enter runs the highlighted row with
  // `visibleCmds(idx).execute()`.
  // Failure: Enter gives an error with "argument required", and no new
  // diagram opens.
  test("Enter on Create new diagram in the palette opens a new diagram"):
    withPalette(): palette =>
      palette.search("Create new diagram")
      palette.press("ArrowDown")
      check(
        palette.highlighted.headOption.map(palette.labels).contains("Create new diagram"),
        s"precondition: ArrowDown did not highlight Create new diagram: ${palette.highlighted} of ${palette.labels}"
      )
      palette.press("Enter")
      afterMicrotasks:
        same(palette.listenerErrors, Nil, "Create new diagram threw when the palette ran it")
        check(openedDiagrams(palette).nonEmpty, s"the palette did not open a new diagram: ${palette.routes}")
        same(
          openedDiagrams(palette).map(_.source),
          Seq(Some(minimalGraphText)),
          "the new diagram does not open with the text that the Create Diagram buttons give"
        )

  // ---------------------------------------------------------------------------
  // Bug 3: the highlight shows on a row that Enter does not run.
  // ---------------------------------------------------------------------------

  // The palette finds the index of a row with `indexWhere(_.shortLabel ==
  // cmd.shortLabel)`. A command in two sections (New node, Create new
  // diagram, Navigate home, Rename diagram) gets the index of its FIRST copy.
  // At that index two rows show the highlight. At the index of the second
  // copy, no row shows it. Enter runs the command at the index, in the order
  // of the rows, so the highlight must be on the row at the index.
  // Failure: some positions have two highlighted rows, and some have none.
  test("ArrowDown highlights exactly one row at each position, and it is the row that Enter runs"):
    withPalette(): palette =>
      val labels = palette.labels
      val twice  = labels.groupBy(identity).collect { case (label, copies) if copies.size > 1 => label }
      check(twice.nonEmpty, s"precondition: no command is in two sections of the palette: $labels")
      val seen = labels.indices.map: _ =>
        palette.press("ArrowDown")
        palette.highlighted
      val wrong = seen.zipWithIndex.collect:
        case (rows, position) if rows != Seq(position) =>
          s"position $position (${labels(position)}) highlights ${rows.map(row => s"$row (${labels(row)})").mkString("[", ", ", "]")}"
      check(
        wrong.isEmpty,
        s"${wrong.size} of ${labels.size} positions do not highlight exactly the row that Enter runs: ${wrong.mkString("; ")}"
      )
      Future.unit

  // ---------------------------------------------------------------------------
  // Bug 4: the palette key is not the command key of the platform.
  // ---------------------------------------------------------------------------

  /** Press a key in `target`, and give back the event. */
  private def pressIn(
      target: dom.EventTarget,
      key:    String,
      meta:   Boolean = false,
      ctrl:   Boolean = false,
      shift:  Boolean = false,
      alt:    Boolean = false
  ): dom.KeyboardEvent =
    val event = keyDown(key, meta, ctrl, shift, alt)
    target.dispatchEvent(event)
    event

  // At first the palette read only `metaKey`, so Ctrl+K did nothing on
  // Windows and Linux. Then it read `metaKey || ctrlKey`. On a Mac, Ctrl+K is
  // the key of "delete to the end of the line" in a text field and in
  // CodeMirror, so the palette took that key from the editor. The palette key
  // is the command key of the platform: ⌘K on a Mac.
  // Failure with `metaKey || ctrlKey`: on a Mac, the palette calls
  // preventDefault for Ctrl+K, and it moves the focus to the search box.
  test("on a Mac, Cmd+K focuses the search box, and Ctrl+K stays with the text field"):
    withPalette(isMac = true): palette =>
      palette.field.focus()
      val ctrlK = pressIn(palette.field, "k", ctrl = true)
      check(!ctrlK.defaultPrevented, "on a Mac, the palette called preventDefault for Ctrl+K")
      check(dom.document.activeElement eq palette.field, "on a Mac, Ctrl+K moved the focus out of the text field")
      val cmdK = pressIn(palette.field, "k", meta = true)
      check(cmdK.defaultPrevented, "on a Mac, the palette did not take Cmd+K")
      check(dom.document.activeElement eq palette.input, "on a Mac, Cmd+K did not focus the search box")
      same(palette.hint, "⌘ K", "on a Mac, the hint does not show Cmd+K")
      Future.unit

  // On Windows and Linux, the palette key is Ctrl+K. Meta is the Windows key
  // or the Super key there, and the system uses it.
  // Failure with `metaKey || ctrlKey`: the palette takes Meta+K.
  test("on Windows and Linux, Ctrl+K focuses the search box, and Meta+K does not"):
    withPalette(isMac = false): palette =>
      palette.field.focus()
      val metaK = pressIn(palette.field, "k", meta = true)
      check(!metaK.defaultPrevented, "off a Mac, the palette called preventDefault for Meta+K")
      check(dom.document.activeElement eq palette.field, "off a Mac, Meta+K moved the focus out of the text field")
      val ctrlK = pressIn(palette.field, "k", ctrl = true)
      check(ctrlK.defaultPrevented, "off a Mac, the palette did not take Ctrl+K")
      check(dom.document.activeElement eq palette.input, "off a Mac, Ctrl+K did not focus the search box")
      same(palette.hint, "Ctrl K", "off a Mac, the hint does not show Ctrl+K")
      Future.unit

  // Shift+⌘K is "delete line" in CodeMirror (Shift-Mod-k). The palette key
  // has no Shift and no Alt, as the ⌘S key has no Alt.
  // Failure before the fix: the palette takes Shift+⌘K and Alt+⌘K from the
  // text field.
  test("the command key with Shift or Alt is not the palette key"):
    withPalette(isMac = true): palette =>
      palette.field.focus()
      val taken = List(
        "Shift+Cmd+K" -> pressIn(palette.field, "K", meta = true, shift = true),
        "Alt+Cmd+K"   -> pressIn(palette.field, "k", meta = true, alt = true)
      ).collect { case (name, event) if event.defaultPrevented => name }
      same(taken, Nil, "the palette took a key with Shift or Alt")
      check(dom.document.activeElement eq palette.field, "a key with Shift or Alt moved the focus to the search box")
      Future.unit
