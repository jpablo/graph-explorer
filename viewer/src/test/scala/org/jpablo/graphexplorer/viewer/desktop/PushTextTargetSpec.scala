package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.SessionCommand
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

/** A text push names the document it is aimed at.
  *
  * It used to carry text and nothing else, and the page put it into whichever
  * viewer was on screen. That is a write with no addressee — the same defect
  * class as an open reporting success for a file no viewer showed.
  */
class PushTextTargetSpec extends FunSuite with TestHelpers:

  override def beforeEach(context: BeforeEach): Unit =
    SessionCommands.reset()
    DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    SessionCommands.reset()
    DesktopDocumentRegistry.reset()

  test("a push reaches the session it names") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/pushed.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
      SessionCommands.attach(state)

      afterMicrotasks {
        val outcome = SessionCommands.run(SessionCommand.PushText(open.id.value, "digraph { pushed }"))

        assert(outcome.isRight, s"a push at the open session was refused: $outcome")
        assertEquals(state.sourceText.now(), "digraph { pushed }")
      }
    }
  }

  test("a push at a DIFFERENT session is refused, not applied to what is on screen") {
    withGraphvizAsync { graphviz =>
      val shown = DesktopDocumentRegistry.record("/tmp/shown.dot", "rev-1", "digraph { shown }")
      val other = DesktopDocumentRegistry.record("/tmp/other.dot", "rev-1", "digraph { other }")
      val state = ViewerState(ViewTarget.LooseFile(shown.id), graphviz)
      SessionCommands.attach(state)

      afterMicrotasks {
        val outcome = SessionCommands.run(SessionCommand.PushText(other.id.value, "digraph { WRONG }"))

        assertEquals(
          outcome.left.toOption.map(_.code),
          Some("VIEW_REJECTED"),
          "a push at another document was accepted"
        )
        assertEquals(
          state.sourceText.now(),
          "digraph { shown }",
          "the text landed on the diagram that happened to be on screen"
        )
      }
    }
  }

  test("a push at a library record is refused — only a file has a session") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/unshown.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.library("a-record"), graphviz)
      SessionCommands.attach(state)

      afterMicrotasks {
        val outcome = SessionCommands.run(SessionCommand.PushText(open.id.value, "digraph { WRONG }"))
        assertEquals(outcome.left.toOption.map(_.code), Some("VIEW_REJECTED"))
      }
    }
  }

  test("a session id that is not one is a request error, not a view refusal") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/x.dot", "rev-1", "digraph { a }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
      SessionCommands.attach(state)

      afterMicrotasks {
        // The caller's next move differs: fix the argument, versus open the
        // document first. Two codes, because they are two situations.
        val outcome = SessionCommands.run(SessionCommand.PushText("not-a-session", "x"))
        assertEquals(outcome.left.toOption.map(_.code), Some("INVALID_REQUEST"))
      }
    }
  }

  test("a push with nothing open says NO_SESSION") {
    val outcome = SessionCommands.run(SessionCommand.PushText("doc-abc", "x"))
    assertEquals(outcome.left.toOption.map(_.code), Some("NO_SESSION"))
  }
