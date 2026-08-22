package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.laminar.api.L.{Owner, unsafeWindowOwner}
import munit.FunSuite
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
class DesktopCloseSpec extends FunSuite with TestHelpers:

  private given Owner = unsafeWindowOwner

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
    DesktopDocumentRegistry.reset()
    installShell()

  override def afterEach(context: AfterEach): Unit =
    DesktopClose.reset()
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
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
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
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
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
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
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
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
      DesktopClose.install(state)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph { edited }")
        assertEquals(unsavedReports.lastOption, Some(true))

        DesktopClose.detach(state)

        assertEquals(unsavedReports.lastOption, Some(false), "the shell was left refusing closes")
      }
    }
  }
