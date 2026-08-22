package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** Phase 2 item 6: dirty and external-conflict state (§7.3, §7.4).
  *
  * The rule under test is the one §7.3 states as a prohibition: dirty text is
  * never replaced silently. Each test therefore asserts what the EDITOR shows
  * after an external change, not which flag was set.
  */
class DocumentConflictSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  private def fileChanged(text: String, path: String, revision: String) =
    DesktopBridge.DesktopMessage(text, Some(path), Some(revision))

  test("a clean editor adopts the file when it changes on disk") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/clean.dot", "rev-1", "digraph G { a }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        DesktopBridge.applyDocumentChange(state, fileChanged("digraph G { b }", "/tmp/clean.dot", "rev-2"))

        assertEquals(state.sourceText.now(), "digraph G { b }", "a clean editor must follow the file")
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict), None)
      }
    }
  }

  test("a dirty editor keeps its text when the file changes, and records the conflict") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/dirty.dot", "rev-1", "digraph G { a }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph G { mine }") // the person types

        DesktopBridge.applyDocumentChange(state, fileChanged("digraph G { theirs }", "/tmp/dirty.dot", "rev-2"))

        assertEquals(
          state.sourceText.now(),
          "digraph G { mine }",
          "§7.3 forbids replacing dirty text — the edit was thrown away"
        )
        assertEquals(
          DesktopDocumentRegistry.get(open.id).flatMap(_.conflict).map(_.text),
          Some("digraph G { theirs }"),
          "both versions must be kept"
        )
      }
    }
  }

  test("accepting the file's version advances the base, so the next save can succeed") {
    val open = DesktopDocumentRegistry.record("/tmp/resolve.dot", "rev-1", "digraph G { a }")
    DesktopDocumentRegistry.markConflict(open.id, "rev-2", "digraph G { theirs }")

    val resolved = DesktopDocumentRegistry.acceptRemote(open.id)

    assertEquals(resolved.map(_.revision), Some("rev-2"), "a stale base makes every later save conflict again")
    assertEquals(resolved.map(_.sourceText), Some("digraph G { theirs }"))
    assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict), None)
  }

  test("a library diagram is never dirty, so it never blocks a navigation") {
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.library("a-record"), graphviz)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph G { edited }")
        assertEquals(
          state.documentIsDirty,
          false,
          "a record saves on every keystroke, so it is never behind"
        )
      }
    }
  }

  test("a dirty file refuses a navigation and holds the route it was asked for") {
    withGraphvizAsync { graphviz =>
      js.eval(
        """
          if (typeof window === 'undefined') { global.window = {}; }
          if (typeof window.location === 'undefined') {
            window.location = { origin: 'http://localhost', search: '', pathname: '/', href: 'http://localhost/' };
          }
          window.history = { pushState: function(_,__,url) {
            var a = new URL(url, 'http://localhost');
            window.location.pathname = a.pathname;
          } };
        """
      )
      dom.window.history.pushState(null, "", "/")

      val open   = DesktopDocumentRegistry.record("/tmp/leaving.dot", "rev-1", "digraph G { a }")
      val state  = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
      val router = Router()

      router.guardNavigation: route =>
        if state.documentIsDirty then
          state.pendingLeave.set(Some(LeaveIntent.Navigate(route)))
          false
        else true

      afterMicrotasks {
        // Clean: the guard lets it through.
        router.navigateTo(Route.Home)
        assertEquals(state.pendingLeave.now(), None)
        assertEquals(dom.window.location.pathname, "/")

        state.replaceSourceDetectingFormat("digraph G { unsaved }")

        router.navigateTo(Route.ProjectDetail("some-record"))

        assertEquals(
          state.pendingLeave.now(),
          Some(LeaveIntent.Navigate(Route.ProjectDetail("some-record"))),
          "§7.4: the dialog must go where the click asked to go"
        )
        assertEquals(
          dom.window.location.pathname,
          "/",
          "the navigation happened anyway, and the unsaved edit went with it"
        )
      }
    }
  }
