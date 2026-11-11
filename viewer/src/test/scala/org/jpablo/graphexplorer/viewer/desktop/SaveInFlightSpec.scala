package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, UnsavedChangesDialog}
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry.{Conflict, Session}
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A second save of a file while the first save is in flight.
  *
  * The page sends each save with the base revision of the session. A second
  * save before the reply of the first one sends the SAME base. The shell
  * writes the first save, so the second save comes back as
  * DOCUMENT_CONFLICT, and the person sees "The file changed on disk" for a
  * write of their own.
  *
  * The shell here is the fake shell, with the order of the real shell for
  * `save_document` ([[answerSavesLater]]).
  */
class SaveInFlightSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "SaveInFlightSpec"

  override def munitFixtures = List(mockStorageFixture())

  private var shell = FakeDesktopShell()

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    shell = FakeDesktopShell()
    shell.install()
    answerSavesLater()

  override def afterEach(context: AfterEach): Unit =
    shell.uninstall()
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  /** Make the fake shell answer `save_document` in the order of the real
    * shell.
    *
    * The real shell runs a command on a thread of its own, so the page can
    * send a second save before the shell starts the first one. For each save,
    * `put_document_snapshot` in `main.rs` compares the base with the file,
    * writes the file, sends the document event, and then replies. The fake
    * writes in the call, and it sends no document event. This wrapper starts
    * each save in a later task, and it sends the event before the reply.
    */
  private def answerSavesLater(): Unit =
    val direct = js.Dynamic.global.window.__TAURI__.core.invoke
      .asInstanceOf[js.Function2[String, js.Any, js.Promise[js.Any]]]
    val later: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
      if command != "save_document" then direct(command, args)
      else
        new js.Promise[js.Any]((resolve, reject) =>
          js.timers.setTimeout(0):
            val written: js.Function1[js.Any, Unit] = reply =>
              val snapshot = reply.asInstanceOf[js.Dynamic]
              DesktopBridge.routeDocumentChange(
                DesktopBridge.DesktopMessage(
                  snapshot.text.asInstanceOf[String],
                  Some(snapshot.path.asInstanceOf[String]),
                  Some(snapshot.revision.asInstanceOf[String])
                )
              )
              resolve(reply)
            val refused: js.Function1[js.Any, Unit] = error => reject(error)
            direct(command, args).asInstanceOf[js.Dynamic].`then`(written, refused)
            ()
          ()
        )
    js.Dynamic.global.window.__TAURI__ = js.Dynamic.literal(core = js.Dynamic.literal(invoke = later))

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** The time for each save of a test to get its reply. */
  private def settle(): Future[Unit] = after(100)

  /** Put `text` in the file at `path`, and record the session as the shell
    * does when it opens the file.
    */
  private def openFile(path: String, text: String): Session =
    shell.files(path) = text
    DesktopDocumentRegistry.record(path, shell.hash(text), text)

  private def conflictOf(open: Session): Option[Conflict] =
    DesktopDocumentRegistry.get(open.id).flatMap(_.conflict)

  private def saveCalls: Int = shell.callsOf("save_document").size

  /** A view of a file, with the messages that its buses showed. */
  private final class FileView(val open: Session, val state: ViewerState, owner: ManualOwner):
    val infos  = mutable.Buffer.empty[String]
    val errors = mutable.Buffer.empty[String]
    state.infoBus.events.foreach(infos += _)(using owner)
    state.errorBus.events.foreach(errors += _)(using owner)
    val commands = Commands(state, RouterCommands(Router()))

  /** Open `text` as the file at `path` in a view, and run `body` after the
    * view starts. The view has its own owner, and the end of the test kills it.
    */
  private def withFile(path: String, text: String)(body: FileView => Future[Unit]): Future[Unit] =
    val open  = openFile(path, text)
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      afterMicrotasks(())
        .flatMap(_ => body(FileView(open, state, owner)))
        .transform: outcome =>
          owner.killSubscriptions()
          outcome

  /** A ⌘S keydown. `prevented` tells if the page took the key from the browser. */
  private final class SaveKey(repeat: Boolean):
    var prevented = false
    val event: dom.KeyboardEvent = js.Dynamic
      .literal(
        key = "s",
        code = "KeyS",
        metaKey = true,
        ctrlKey = false,
        altKey = false,
        shiftKey = false,
        repeat = repeat,
        preventDefault = (() => prevented = true): js.Function0[Unit],
        stopPropagation = (() => ()): js.Function0[Unit]
      )
      .asInstanceOf[dom.KeyboardEvent]

  private def pressSave(view: FileView, repeat: Boolean = false): SaveKey =
    val key = SaveKey(repeat)
    view.commands.handleKeyDown(key.event)
    key

  private val base   = "digraph G {\n  a\n}\n"
  private val first  = "digraph G {\n  a\n  b\n}\n"
  private val second = "digraph G {\n  a\n  b\n  c\n}\n"
  private val theirs = "digraph G {\n  a\n  theirs\n}\n"

  /** The message of `FilePersistence` for a refused compare-and-swap. */
  private val conflictMessage = "The file changed on disk. Reload it and try again."

  // ---------------------------------------------------------------------------
  // Bug 2: two saves in flight at the same time.
  // ---------------------------------------------------------------------------

  // The person presses ⌘S two times, and the second press comes before the
  // reply of the first save. Both saves send the base revision of the file
  // as it was before the first save. The shell writes the first save, and it
  // refuses the second one: the file is not at that base now.
  // Failure: the error bus shows "The file changed on disk. Reload it and
  // try again." for the second save.
  test("a second ⌘S while a save is in flight does not end in a conflict with our own write"):
    withFile("/w/two-saves.dot", base): view =>
      view.state.sourceText.set(first)
      pressSave(view)
      pressSave(view)
      settle().map: _ =>
        same(view.errors.toList, Nil, "a save of our own came back as a conflict")
        same(shell.files.get(view.open.path), Some(first), "the file does not hold the saved text")
        same(conflictOf(view.open), None, "the session has a conflict with our own write")

  // The person types between the two presses. The second save sends the
  // latest text with the old base. The event of the first save arrives
  // while the editor holds the latest text, so the page also takes our own
  // write for a change of a different tool.
  // Failure: the second save comes back as a conflict, the file keeps the
  // text of the first save, and the session shows a conflict.
  test("a second ⌘S with new text while a save is in flight writes the latest text, with no conflict"):
    withFile("/w/two-saves-new-text.dot", base): view =>
      view.state.sourceText.set(first)
      pressSave(view)
      view.state.sourceText.set(second)
      pressSave(view)
      settle().map: _ =>
        same(view.errors.toList, Nil, "a save of our own came back as a conflict")
        same(shell.files.get(view.open.path), Some(second), "the file does not hold the latest text")
        same(conflictOf(view.open), None, "the session has a conflict with our own write")
        same(view.state.documentIsDirty, false, "the latest text does not show as saved")

  // A guard for the fix of the two tests above: the wait must not hide a
  // real conflict. A different tool writes the file while a save is in
  // flight, and a second save waits. The base of the session stays the
  // base of the edit (§7.3), so each save sends a base that is not on disk.
  // This test passes before and after the fix.
  test("a write of a different tool while saves are in flight still comes back as a conflict"):
    val path = "/w/outside-during-save.dot"
    withFile(path, base): view =>
      view.state.sourceText.set(first)
      pressSave(view)
      pressSave(view)
      // The different tool writes before the shell runs the first save, and
      // the watch loop reports the change.
      shell.files(path) = theirs
      DesktopBridge.routeDocumentChange(DesktopBridge.DesktopMessage(theirs, Some(path), Some(shell.hash(theirs))))
      settle().map: _ =>
        same(view.infos.toList, Nil, "a save reported Saved over the change of a different tool")
        check(
          view.errors.nonEmpty && view.errors.forall(_ == conflictMessage),
          s"each save must come back as a conflict: ${view.errors.toList}"
        )
        same(shell.files.get(path), Some(theirs), "a save wrote over the change of a different tool")
        same(conflictOf(view.open).map(_.text), Some(theirs), "the session does not hold the change as a conflict")

  // A held ⌘S sends keydown events with `repeat = true`. Each one starts a
  // new save, and two of them can be in flight at the same time.
  // Failure: each repeat sends one more `save_document`.
  test("a ⌘S keydown with repeat = true does not start another save"):
    withFile("/w/held-save.dot", base): view =>
      view.state.sourceText.set(first)
      pressSave(view)
      settle().flatMap: _ =>
        same(saveCalls, 1, "precondition: the first ⌘S did not send one save")
        val repeats = List.fill(3)(pressSave(view, repeat = true))
        settle().map: _ =>
          same(saveCalls, 1, "a ⌘S keydown with repeat = true started another save")
          same(view.errors.toList, Nil, "a repeat of ⌘S ended in an error")
          check(repeats.forall(_.prevented), "a repeat of ⌘S went to the browser, which opens its own save dialog")

  // The Save button of the "Save this file?" dialog has the same gap. A
  // second click before the reply of the first save starts a second save
  // with the same base.
  // Failure: the error bus shows "The file changed on disk. Reload it and
  // try again." for the second click.
  test("a second click on Save in the leave dialog while a save is in flight does not end in a conflict"):
    val startHref = dom.window.location.href
    val box       = dom.document.createElement("div")
    dom.document.body.appendChild(box)
    val run = withFile("/w/dialog-two-saves.dot", base): view =>
      val root = render(box, UnsavedChangesDialog(view.state, Router()))
      view.state.sourceText.set(first)
      view.state.pendingLeave.set(Some(LeaveIntent.Navigate(Route.Home)))
      after(50)
        .flatMap: _ =>
          val save = box.querySelectorAll("button").toSeq.find(_.textContent.trim == "Save")
          check(save.isDefined, s"precondition: the dialog has no Save button: ${box.innerHTML}")
          save.foreach(_.asInstanceOf[dom.html.Button].click())
          save.foreach(_.asInstanceOf[dom.html.Button].click())
          settle()
        .map: _ =>
          same(view.errors.toList, Nil, "a save of our own came back as a conflict")
          same(shell.files.get(view.open.path), Some(first), "the file does not hold the saved text")
          same(view.state.pendingLeave.now(), None, "the dialog did not close after the save")
        .transform: outcome =>
          root.unmount()
          outcome
    run.transform: outcome =>
      box.parentNode.removeChild(box)
      dom.window.history.replaceState(null, "", startHref)
      outcome
