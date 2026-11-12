package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{Owner, unsafeWindowOwner}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** §7.4's other half: the window closing while an edit is not on disk.
  *
  * The shell decides from a flag the page keeps current. These assert that the
  * flag is reported, and that the shell's question raises the same three-answer
  * dialog a navigation raises.
  */
class DesktopCloseSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DesktopCloseSpec"

  // A `ViewerState` of a test that gives no view owner takes this owner.
  private given Owner = unsafeWindowOwner

  /** An owner that tells how many subscriptions it holds now. */
  private final class CountingOwner extends Owner:
    def live: Int = subscriptions.length

    override def killSubscriptions(): Unit = super.killSubscriptions()

  private val invokes: mutable.Buffer[(String, js.Dynamic)] = mutable.Buffer.empty

  private def installShell(): Unit =
    invokes.clear()
    val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
      invokes += ((command, args.asInstanceOf[js.Dynamic]))
      js.Promise.resolve[js.Any](())
    js.Dynamic.global.window.__TAURI__ =
      js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

  override def beforeEach(context: BeforeEach): Unit =
    DesktopClose.reset()
    DesktopOpenRequests.reset()
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()
    installShell()

  override def afterEach(context: AfterEach): Unit =
    DesktopClose.reset()
    DesktopOpenRequests.reset()
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def unsavedReports: List[Boolean] =
    invokes.toList.collect:
      case ("set_unsaved", args) => args.selectDynamic("unsaved").asInstanceOf[Boolean]

  private def requestClose(): Unit =
    dom.window.dispatchEvent(dom.CustomEvent("ge:close.requested", js.undefined))

  test("the page tells the shell when an edit is not on disk, and when it is again") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/close.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      DesktopClose.install(state)

      afterMicrotasks {
        assertEquals(unsavedReports.lastOption, Some(false), "a fresh file has nothing unsaved")

        state.replaceSourceDetectingFormat("digraph { edited }")
        assertEquals(unsavedReports.lastOption, Some(true), "the shell was not told about the edit")

        // The shell decides from this flag alone, so a close after this point
        // must be refused rather than silently discarding the edit.
        DesktopDocumentRegistry.record("/tmp/close.dot", "rev-2", "digraph { edited }")
        assertEquals(unsavedReports.lastOption, Some(false), "a saved file still reported as unsaved")
      }
    }
  }

  test("a close request with an unsaved edit raises the dialog, and confirms nothing yet") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/close-dirty.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      DesktopClose.install(state)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph { edited }")
        requestClose()

        assertEquals(
          state.pendingLeave.now(),
          Some(LeaveIntent.CloseWindow),
          "the close did not raise the unsaved-changes question"
        )
        assertEquals(
          invokes.toList.map(_._1).count(_ == "confirm_close"),
          0,
          "the window was let go before the person answered"
        )
      }
    }
  }

  test("a close request with nothing unsaved is confirmed at once") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/close-clean.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      DesktopClose.install(state)

      afterMicrotasks {
        requestClose()

        assertEquals(state.pendingLeave.now(), None, "a clean page must not ask anything")
        assertEquals(invokes.toList.map(_._1).count(_ == "confirm_close"), 1)
      }
    }
  }

  test("a detached view reports that it no longer holds anything unsaved") {
    // Without this, navigating away from a dirty file would leave the shell
    // refusing every close for an edit that no longer exists.
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/close-detach.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      DesktopClose.install(state)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph { edited }")
        assertEquals(unsavedReports.lastOption, Some(true))

        DesktopClose.detach(state)

        assertEquals(unsavedReports.lastOption, Some(false), "the shell was left refusing closes")
      }
    }
  }

  // Finding 7. `install` subscribed to `documentDirty` with the owner of the
  // caller, and `Viewer` gave the window owner. `detach` clears only the
  // target. Thus a view that went away still reported `set_unsaved` for its
  // file. Show a.dot clean, then show b.dot and edit it. A change of a.dot
  // outside, and a restore, made the dead a.dot view send true and then false.
  // b.dot does not report again (`distinct`), so a close passed
  // `confirmed || !unsaved` in `main.rs`, and the b.dot edit was lost.
  // Failure: the last `set_unsaved` value is false while b.dot holds an edit.
  test("a view that went away does not report for its file, so the flag stays true for the dirty view on screen") {
    val textA  = "digraph { a }"
    val textB  = "digraph { b }"
    val router = Router()
    withGraphvizAsync { graphviz =>
      val fileA  = DesktopDocumentRegistry.record("/w/close-gone.dot", "rev-a1", textA)
      val fileB  = DesktopDocumentRegistry.record("/w/close-live.dot", "rev-b1", textB)
      val ownerA = ManualOwner()
      val ownerB = ManualOwner()
      // The view of a.dot, as `Viewer.diagramView` makes it: a view owner, and
      // the desktop links of `DesktopViewWiring`.
      val stateA  = ViewerState(ViewTarget.File(fileA.id), graphviz)(using ownerA)
      val detachA = DesktopViewWiring.attach(stateA, router)
      var detachB = () => ()

      afterMicrotasks(())
        .flatMap: _ =>
          // A navigation to b.dot. Laminar mounts the new view BEFORE it
          // unmounts the old view, so the links of B come before the teardown
          // of A. The teardown is the `onUnmountCallback` of `Viewer`.
          val stateB = ViewerState(ViewTarget.File(fileB.id), graphviz)(using ownerB)
          detachB = DesktopViewWiring.attach(stateB, router)
          detachA()
          stateA.closePersistence()
          ownerA.killSubscriptions()
          afterMicrotasks(stateB)
        .map: stateB =>
          stateB.replaceSourceDetectingFormat("digraph { b; edited }")
          same(unsavedReports.lastOption, Some(true), "precondition: the edit of b.dot did not reach the shell")
          val before = unsavedReports.size

          // A different writer changes a.dot, and then puts the old text back.
          // The shell still watches a.dot, so each change is a document event.
          DesktopDocumentRegistry.record("/w/close-gone.dot", "rev-a2", "digraph { a; outside }")
          DesktopDocumentRegistry.record("/w/close-gone.dot", "rev-a1", textA)

          same(stateB.documentIsDirty, true, "precondition: b.dot lost its edit")
          same(
            unsavedReports.lastOption,
            Some(true),
            s"the shell holds unsaved = false while b.dot holds an edit; reports: $unsavedReports"
          )
          same(unsavedReports.drop(before), Nil, "the view of a.dot that went away sent set_unsaved")
        .transform: outcome =>
          detachB()
          ownerA.killSubscriptions()
          ownerB.killSubscriptions()
          outcome
    }
  }

  // Finding 7, the owner. A subscription of a view on the window owner never
  // ends. Thus, before the fix, each file view that was ever shown stayed in
  // memory, with its whole `ViewerState`, and it continued to compute its
  // unsaved state.
  // Failure before the fix: `install` adds no subscription to the owner of the view.
  test("the close guard follows a view on the owner of the view, so the view is released when it goes") {
    withGraphvizAsync { graphviz =>
      val open   = DesktopDocumentRegistry.record("/w/close-owner.dot", "rev-1", "digraph { a }")
      val owner  = CountingOwner()
      val state  = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      val before = owner.live
      DesktopClose.install(state)
      val after = owner.live
      DesktopClose.detach(state)
      owner.killSubscriptions()
      afterMicrotasks:
        check(after > before, s"install put no subscription on the owner of the view: $before before, $after after")
    }
  }

  // Finding 7, the report rule. Laminar mounts the new view before it
  // unmounts the old view. In that time two views are linked, and only the
  // view on the screen may change the flag.
  // Failure with no rule: the old view sends true and false while the new
  // view holds an edit.
  test("only the view on the screen reports, also while the old view still lives") {
    val textA = "digraph { a }"
    withGraphvizAsync { graphviz =>
      val fileA  = DesktopDocumentRegistry.record("/w/close-old.dot", "rev-a1", textA)
      val fileB  = DesktopDocumentRegistry.record("/w/close-new.dot", "rev-b1", "digraph { b }")
      val ownerA = ManualOwner()
      val ownerB = ManualOwner()
      val stateA = ViewerState(ViewTarget.File(fileA.id), graphviz)(using ownerA)
      DesktopClose.install(stateA)

      afterMicrotasks(())
        .flatMap: _ =>
          val stateB = ViewerState(ViewTarget.File(fileB.id), graphviz)(using ownerB)
          DesktopClose.install(stateB)
          afterMicrotasks(stateB)
        .map: stateB =>
          stateB.replaceSourceDetectingFormat("digraph { b; edited }")
          same(unsavedReports.lastOption, Some(true), "precondition: the edit of b.dot did not reach the shell")
          val before = unsavedReports.size

          // The old view changes before its teardown.
          stateA.replaceSourceDetectingFormat("digraph { a; typed }")
          stateA.replaceSourceDetectingFormat(textA)

          same(unsavedReports.lastOption, Some(true), s"the old view changed the flag; reports: $unsavedReports")
          same(unsavedReports.drop(before), Nil, "the view that is not on the screen sent set_unsaved")
        .transform: outcome =>
          DesktopClose.detach(stateA)
          ownerA.killSubscriptions()
          ownerB.killSubscriptions()
          outcome
    }
  }
