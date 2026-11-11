package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{HtmlElement, child, div, onUnmountCallback, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.UnsavedChangesDialog
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, DocumentSessionId, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The open handshake of the desktop (§4) and the leave guard (§7.4), from
  * `gx open` to the save of the file.
  *
  * The shell is the fake shell. `FakeDesktopShell.show` does the steps of
  * `show_file` in `main.rs`: it watches the file, sends the document event and
  * the open request, and it removes the watch that the open made when the page
  * does not answer `displayed`. With `saveNeedsWatch`, its `save_document`
  * refuses a path with no watch, as `main.rs` does.
  *
  * The page is [[DesktopPage]]: the listeners of `Viewer.startApp`, and a
  * view of a file that `DesktopViewWiring.attach` links, as
  * `Viewer.diagramView` does. The view has no canvas and no editor.
  */
class OpenHandshakeSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "OpenHandshakeSpec"

  override def munitFixtures = List(mockStorageFixture())

  private var shell     = FakeDesktopShell()
  private var startHref = ""

  override def beforeEach(context: BeforeEach): Unit =
    startHref = dom.window.location.href
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    DesktopOpenRequests.reset()
    DesktopClose.reset()
    LiveViewCommands.reset()
    shell = FakeDesktopShell()
    shell.saveNeedsWatch = true
    shell.install()

  override def afterEach(context: AfterEach): Unit =
    shell.uninstall()
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    DesktopOpenRequests.reset()
    DesktopClose.reset()
    LiveViewCommands.reset()
    dom.window.history.replaceState(null, "", startHref)

  private val good    = "digraph G {\n  a -> b\n}\n"
  private val goodB   = "digraph H {\n  c -> d\n}\n"
  private val edited  = "digraph G {\n  a -> b\n  mine\n}\n"
  private val editedB = "digraph H {\n  c -> d\n  mine\n}\n"
  private val broken  = "digraph G {\n  a -> \n"
  private val fixed   = "digraph G {\n  a -> b\n}\n"

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** The value of `future`, or `None` when it does not come in `ms`. */
  private def within[A](ms: Int)(future: Future[A]): Future[Option[A]] =
    Future.firstCompletedOf(List(future.map(Some(_)), after(ms).map(_ => None)))

  /** Wait until `condition` is true, for `ms` at most. */
  private def until(ms: Int)(condition: => Boolean): Future[Boolean] =
    if condition then Future.successful(true)
    else if ms <= 0 then Future.successful(false)
    else after(10).flatMap(_ => until(ms - 10)(condition))

  /** The view on the screen, when it shows the file at `path`. */
  private def onScreen(page: DesktopPage, path: String): Option[ViewerState] =
    page.onScreen.filter(_.documentSessionNow.exists(_.path == path))

  /** True when the view on the screen shows `path`, and its parse is done. */
  private def ready(page: DesktopPage, path: String): Boolean =
    onScreen(page, path).exists(_.loadStatus.now() != DiagramLoadStatus.Loading)

  private def withPage(body: DesktopPage => Future[Unit]): Future[Unit] =
    withGraphvizAsync: graphviz =>
      val page = DesktopPage(graphviz)
      Future
        .delegate(body(page))
        .transform: outcome =>
          page.close()
          outcome

  // ---------------------------------------------------------------------------
  // Finding 9: the shell removed the watch of a file that the page shows.
  // ---------------------------------------------------------------------------

  // Finding 9, trigger (a). `gx open bad.dot`, for a file with no watch whose
  // text does not parse. The view mounts and shows the text, the parse fails,
  // and the page answers `rejected` with PARSE_FAILED. Before the fix, the
  // shell then removed the watch that this open made (`rollback_failed_open`).
  // The view still shows the file, so the person corrects the text and saves.
  // Failure before the fix: the shell has no watch for the file on the
  // screen, and (with no retry of a save) the save fails with "path is not
  // currently watched".
  test("a file that does not parse keeps its watch while its view shows it, so a save after a fix succeeds"):
    val path = "/w/bad.dot"
    shell.files(path) = broken
    withPage: page =>
      within(2000)(shell.show(path)).flatMap: answer =>
        check(answer.isDefined, "precondition: the page did not answer the open")
        until(2000)(ready(page, path)).flatMap: _ =>
          val state = onScreen(page, path)
          check(state.isDefined, s"precondition: the view does not show $path")
          val status = state.map(_.loadStatus.now())
          check(status.exists(_.isInstanceOf[DiagramLoadStatus.Failed]), s"precondition: the text parsed: $status")
          check(shell.watched.contains(path), s"the open removed the watch of the file on the screen; answer: $answer")
          state.get.sourceText.set(fixed)
          state.get.save().map: result =>
            same(
              result,
              SaveResult.Saved,
              s"the save after the fix did not succeed; the open answer was $answer, the watches are ${shell.watched}"
            )

  /** a.dot is on the screen with an edit, and `gx open b.dot` comes. The
    * person clicks `button` in the dialog. Then the person edits b.dot and
    * saves it.
    */
  private def refusedOpenThenSave(button: String, a: String, b: String): Future[Unit] =
    shell.files(a) = good
    shell.files(b) = goodB
    withPage: page =>
      within(2000)(shell.show(a)).flatMap: shownA =>
        same(shownA.map(_.status), Some("displayed"), "precondition: a.dot did not open")
        until(2000)(ready(page, a)).flatMap: _ =>
          val stateA = onScreen(page, a).get
          stateA.sourceText.set(edited)
          val answerB = shell.show(b)
          after(50).flatMap: _ =>
            check(stateA.pendingLeave.now().isDefined, "precondition: the open of b.dot did not ask about the edit")
            check(page.click(button), s"the dialog has no $button button")
            // The page can answer before the click or after it. The fake
            // removes a watch only when the answer comes, so wait for it.
            within(500)(answerB).flatMap: answered =>
              until(2000)(ready(page, b)).flatMap: _ =>
                val stateB = onScreen(page, b)
                check(stateB.isDefined, s"precondition: the view does not show b.dot after $button")
                check(shell.watched.contains(b), s"the open removed the watch of b.dot; answer: $answered")
                stateB.get.sourceText.set(editedB)
                stateB.get.save().map: result =>
                  same(
                    result,
                    SaveResult.Saved,
                    s"the save of b.dot did not succeed; the open answer was $answered, the watches are ${shell.watched}"
                  )

  // Finding 9, trigger (b), with Discard. The leave guard opens the dialog,
  // and the page answers ACTIVATION_REFUSED at once. Before the fix, the shell
  // removed the watch of b.dot that this open made. Then Discard calls
  // `forceNavigateTo`, and the view showed b.dot with no watch.
  // Failure before the fix: the shell has no watch for b.dot on the screen,
  // and (with no retry of a save) the save of b.dot fails with "path is not
  // currently watched".
  test("after Discard in the dialog of a refused open, the file that opens can be saved"):
    refusedOpenThenSave("Discard", "/w/discard-a.dot", "/w/discard-b.dot")

  // Finding 9, trigger (b), with Save. The same order: the answer is
  // ACTIVATION_REFUSED, the shell removed the watch of b.dot before the fix,
  // and Save writes a.dot and then calls `forceNavigateTo`.
  // Failure before the fix: the shell has no watch for b.dot on the screen,
  // and (with no retry of a save) the save of b.dot fails with "path is not
  // currently watched".
  test("after Save in the dialog of a refused open, the file that opens can be saved"):
    refusedOpenThenSave("Save", "/w/save-a.dot", "/w/save-b.dot")

  // ---------------------------------------------------------------------------
  // A save of a file that lost its watch.
  // ---------------------------------------------------------------------------

  /** a.dot is on the screen, and then the shell has no watch for it (for
    * example, after `gx unwatch`).
    */
  private def onScreenWithNoWatch(path: String)(body: ViewerState => Future[Unit]): Future[Unit] =
    shell.files(path) = good
    withPage: page =>
      within(2000)(shell.show(path)).flatMap: shown =>
        same(shown.map(_.status), Some("displayed"), "precondition: the file did not open")
        until(2000)(ready(page, path)).flatMap: _ =>
          shell.watched -= path
          body(onScreen(page, path).get)

  // A watch can go for many causes. Without the retry, each save of the view
  // fails with "path is not currently watched".
  // Failure with no retry: the save comes back as Failed.
  test("a save of a file with no watch registers the watch again and saves"):
    val path = "/w/unwatched.dot"
    onScreenWithNoWatch(path): state =>
      state.sourceText.set(edited)
      state.save().map: result =>
        same(result, SaveResult.Saved, "the save of a file with no watch did not succeed")
        same(shell.files.get(path), Some(edited), "the file does not hold the edit")
        check(shell.watched.contains(path), "the save did not register the watch again")
        same(state.documentIsDirty, false, "the view is dirty after the save")

  // The retry sends the same base revision, so the shell refuses it when the
  // file changed while it had no watch.
  // Failure: the save writes the edit over the outside change.
  test("a save of a file with no watch comes back as a conflict when the file changed in the meantime"):
    val path   = "/w/unwatched-changed.dot"
    val theirs = "digraph G {\n  a -> b\n  theirs\n}\n"
    onScreenWithNoWatch(path): state =>
      // No watch, so the page gets no document event for this change.
      shell.files(path) = theirs
      state.sourceText.set(edited)
      state.save().map: result =>
        check(result.isInstanceOf[SaveResult.Conflict], s"the save did not come back as a conflict: $result")
        same(shell.files.get(path), Some(theirs), "the save wrote the edit over the outside change")
        same(state.sourceText.now(), edited, "the conflict replaced the edit")

  // ---------------------------------------------------------------------------
  // Related 1: the leave guard ran for the route on the screen.
  // ---------------------------------------------------------------------------

  // Related 1. Before the fix, `tryNavigateTo` asked the leave guard also for
  // the route that is on the screen now, and `forceNavigateTo` pushed a second
  // history entry for it. With a dirty file on the screen, a click on the same
  // file in the left panel opened "Save this file?", and a stay loses nothing.
  // Failure before the fix: the guard was asked one time.
  test("a navigation to the route on the screen does not ask the leave guard, and adds no history entry"):
    val router = Router()
    val route  = Route.File("doc-same-route")
    router.forceNavigateTo(route)
    var asked = 0
    val guard: Route => Boolean = _ =>
      asked += 1
      false
    router.guardNavigation(guard)
    // Count the history entries that the router adds. A count of
    // `history.length` stops at the limit of the browser (50 entries).
    js.eval(
      """
        window.__pushes = 0;
        window.__pushState = History.prototype.pushState;
        window.history.pushState = function(s, t, u) {
          window.__pushes += 1;
          return window.__pushState.call(window.history, s, t, u);
        };
      """
    )
    val accepted =
      try router.tryNavigateTo(route)
      finally
        router.clearNavigationGuard(guard)
        js.eval("delete window.history.pushState;")
    val pushes = js.Dynamic.global.window.__pushes.asInstanceOf[Int]
    same(asked, 0, "the leave guard was asked about the route on the screen")
    check(accepted, "the navigation to the route on the screen was refused")
    same(pushes, 0, "the navigation to the route on the screen added a history entry")

  // Related 1, through `gx open`. a.dot holds an edit, and `gx open a.dot`
  // comes. Before the fix, the page asked the leave guard about the route on
  // the screen, opened the dialog, and answered ACTIVATION_REFUSED.
  // Failure before the fix: the answer is `rejected` with ACTIVATION_REFUSED.
  test("an open request for the dirty file on the screen is answered displayed, with no dialog"):
    val a = "/w/same-a.dot"
    shell.files(a) = good
    withPage: page =>
      within(2000)(shell.show(a)).flatMap: first =>
        same(first.map(_.status), Some("displayed"), "precondition: a.dot did not open")
        until(2000)(ready(page, a)).flatMap: _ =>
          val state = onScreen(page, a).get
          state.sourceText.set(edited)
          within(2000)(shell.show(a)).map: second =>
            same(second.map(_.status), Some("displayed"), s"the open of the file on the screen was not displayed: $second")
            same(state.pendingLeave.now(), None, "the open of the file on the screen asked about the edit")
            same(state.sourceText.now(), edited, "the open of the file on the screen dropped the edit")
            check(onScreen(page, a).exists(_ eq state), "the open of the file on the screen made a new view")

  // ---------------------------------------------------------------------------
  // Related 2: a request that the screen left stayed Waiting.
  // ---------------------------------------------------------------------------

  // Related 2. A request waits while its view loads (`Activation.Waiting`).
  // Before the fix, when the screen left its route before the view was Ready,
  // nothing settled the request again, so `gx open` waited for OPEN_TIMEOUT
  // (45 s). A second `gx open` during the load of the first file does this.
  // The two opens come in one block here: the parse of a.dot completes in a
  // later microtask. A Mermaid file has a longer load, and the same order.
  // Failure before the fix: the open of a.dot has no answer after 1 s.
  test("an open request whose route a second open leaves during the load gets a rejection, not silence"):
    val a = "/w/slow-a.dot"
    val b = "/w/next-b.dot"
    shell.files(a) = good
    shell.files(b) = goodB
    withPage: page =>
      val answerA = shell.show(a)
      check(
        onScreen(page, a).exists(_.loadStatus.now() == DiagramLoadStatus.Loading),
        "precondition: the view of a.dot is not loading"
      )
      val answerB = shell.show(b)
      within(1000)(answerB).flatMap: answeredB =>
        within(1000)(answerA).map: answeredA =>
          same(answeredB.map(_.status), Some("displayed"), "precondition: b.dot did not open")
          check(answeredA.isDefined, "the open of a.dot has no answer: gx open waits for OPEN_TIMEOUT")
          same(answeredA.map(_.status), Some("rejected"), s"the open of a.dot was not rejected: $answeredA")
          check(answeredA.flatMap(_.code).exists(_.nonEmpty), s"the rejection of a.dot has no code: $answeredA")

  // Related 2, with a click elsewhere: the person goes to Home during the load.
  // The view of a.dot unmounts, and `detach` clears the view. Before the fix,
  // no view settled the request again.
  // Failure before the fix: the open of a.dot has no answer after 1 s.
  test("an open request whose route the person leaves for Home during the load gets a rejection"):
    val a = "/w/left-a.dot"
    shell.files(a) = good
    withPage: page =>
      val answerA = shell.show(a)
      check(
        onScreen(page, a).exists(_.loadStatus.now() == DiagramLoadStatus.Loading),
        "precondition: the view of a.dot is not loading"
      )
      page.router.navigateTo(Route.Home)
      check(page.onScreen.isEmpty, "precondition: the view of a.dot is still on the screen")
      within(1000)(answerA).map: answered =>
        check(answered.isDefined, "the open of a.dot has no answer: gx open waits for OPEN_TIMEOUT")
        same(answered.map(_.status), Some("rejected"), s"the open of a.dot was not rejected: $answered")
        check(answered.flatMap(_.code).exists(_.nonEmpty), s"the rejection of a.dot has no code: $answered")

/** The page of the desktop: the window listeners of `Viewer.startApp`, and a
  * route signal that shows a view of a file.
  *
  * The view is linked by `DesktopViewWiring.attach`, the function that
  * `Viewer.diagramView` calls, and its teardown is the teardown of `Viewer`.
  * The view shows only the unsaved-changes dialog.
  */
final class DesktopPage(graphviz: Graphviz):

  val router = Router()

  // `Viewer.startApp`: the document listener first, then the open listener.
  DesktopBridge.install()
  DesktopOpenRequests.install(router)

  /** The view that the page shows now. Laminar mounts a new view before it
    * unmounts the old view, so the old teardown clears only its own view.
    */
  var onScreen: Option[ViewerState] = None

  private val box = dom.document.createElement("div")
  dom.document.body.appendChild(box)

  private def fileView(sessionId: String): HtmlElement =
    DocumentSessionId.parse(sessionId).filter(DesktopDocumentRegistry.get(_).isDefined) match
      case None => div()
      case Some(session) =>
        val viewOwner     = new ManualOwner
        val state         = ViewerState(ViewTarget.File(session), graphviz)(using viewOwner)
        val detachDesktop = DesktopViewWiring.attach(state, router)
        onScreen = Some(state)
        div(UnsavedChangesDialog(state, router)).amend(
          onUnmountCallback { _ =>
            detachDesktop()
            state.closePersistence()
            viewOwner.killSubscriptions()
            if onScreen.exists(_ eq state) then onScreen = None
          }
        )

  private val root =
    render(
      box,
      div(
        child <-- router.currentRoute.map:
          case Route.File(sessionId) => fileView(sessionId)
          case _                     => div()
      )
    )

  /** Click the button with the text `label`. False when there is no such
    * button.
    */
  def click(label: String): Boolean =
    box.querySelectorAll("button").toSeq.find(_.textContent.trim == label) match
      case Some(button) =>
        button.asInstanceOf[dom.html.Button].click()
        true
      case None => false

  def close(): Unit =
    root.unmount()
    box.parentNode.removeChild(box)
    ()
