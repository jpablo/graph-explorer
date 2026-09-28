package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** Phase 2 item 6: dirty and external-conflict state (§7.3, §7.4).
  *
  * The rule under test is the one §7.3 states as a prohibition: dirty text is
  * never replaced silently. Each test therefore asserts what the EDITOR shows
  * after an external change, not which flag was set.
  */
class DocumentConflictSpec extends FunSuite with TestHelpers:

  private val invokes: mutable.Buffer[(String, js.Dynamic)] = mutable.Buffer.empty

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    invokes.clear()
    js.Dynamic.global.window.__TAURI__ = null

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def installSavingShell(nextRevision: String): Unit =
    val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
      invokes += ((command, args.asInstanceOf[js.Dynamic]))
      js.Promise.resolve[js.Any](
        js.Dynamic.literal(path = args.asInstanceOf[js.Dynamic].path, revision = nextRevision)
      )
    js.Dynamic.global.window.__TAURI__ =
      js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

  private def saveCalls: List[js.Dynamic] =
    invokes.toList.collect:
      case (DesktopIpc.SaveDocument, args) => args

  /** What the shell sends when a watched file changes.
    *
    * Routed through `DesktopBridge` exactly as the real event is. The bridge
    * only records it now — the VIEWER decides what to do, by following its own
    * session (§10). So this exercises the whole path rather than calling the
    * decision directly.
    */
  private def fileChanged(text: String, path: String, revision: String) =
    DesktopBridge.DesktopMessage(text, Some(path), Some(revision))

  test("a clean editor adopts the file when it changes on disk") {
    withGraphvizAsync { graphviz =>
      val open  = DesktopDocumentRegistry.record("/tmp/clean.dot", "rev-1", "digraph G { a }")
      val state = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        DesktopBridge.routeDocumentChange(fileChanged("digraph G { b }", "/tmp/clean.dot", "rev-2"))

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

        DesktopBridge.routeDocumentChange(fileChanged("digraph G { theirs }", "/tmp/dirty.dot", "rev-2"))

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

  test("Load the file adopts the remote text and clears the active viewer conflict") {
    withGraphvizAsync { graphviz =>
      val local  = "digraph G { mine }"
      val remote = "digraph G { theirs }"
      val open   = DesktopDocumentRegistry.record("/tmp/load-file.dot", "rev-1", "digraph G { a }")
      val state  = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        state.replaceSourceDetectingFormat(local)
        DesktopBridge.routeDocumentChange(fileChanged(remote, "/tmp/load-file.dot", "rev-2"))
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict).map(_.text), Some(remote))

        state.resolveDocumentConflict(adoptRemoteText = true)

        assertEquals(state.sourceText.now(), remote)
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict), None)
        assertEquals(state.documentIsDirty, false)
      }
    }
  }

  test("Keep my edit clears the conflict, stays dirty, and saves against the remote revision") {
    withGraphvizAsync { graphviz =>
      val local  = "digraph G { mine }"
      val remote = "digraph G { theirs }"
      val open   = DesktopDocumentRegistry.record("/tmp/keep-edit.dot", "rev-1", "digraph G { a }")
      val state  = ViewerState(ViewTarget.LooseFile(open.id), graphviz)
      installSavingShell(nextRevision = "rev-3")

      afterMicrotasks {
        state.replaceSourceDetectingFormat(local)
        DesktopBridge.routeDocumentChange(fileChanged(remote, "/tmp/keep-edit.dot", "rev-2"))
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict).map(_.text), Some(remote))

        state.resolveDocumentConflict(adoptRemoteText = false)

        assertEquals(state.sourceText.now(), local)
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict), None)
        assertEquals(state.documentIsDirty, true)
      }.flatMap: _ =>
        state.save().map: result =>
          assertEquals(result, SaveResult.Saved)
          assertEquals(saveCalls.size, 1)
          assertEquals(saveCalls.head.selectDynamic("text").asInstanceOf[String], local)
          assertEquals(saveCalls.head.selectDynamic("baseRevision").asInstanceOf[String], "rev-2")
    }
  }

  test("a later disk change automatically loads after Load the file makes the editor clean") {
    withGraphvizAsync { graphviz =>
      val firstRemote = "digraph G { theirs }"
      val laterRemote = "digraph G { later }"
      val open        = DesktopDocumentRegistry.record("/tmp/load-then-follow.dot", "rev-1", "digraph G { a }")
      val state       = ViewerState(ViewTarget.LooseFile(open.id), graphviz)

      afterMicrotasks {
        state.replaceSourceDetectingFormat("digraph G { mine }")
        DesktopBridge.routeDocumentChange(fileChanged(firstRemote, "/tmp/load-then-follow.dot", "rev-2"))
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict).map(_.text), Some(firstRemote))
        state.resolveDocumentConflict(adoptRemoteText = true)

        DesktopBridge.routeDocumentChange(fileChanged(laterRemote, "/tmp/load-then-follow.dot", "rev-3"))

        assertEquals(state.sourceText.now(), laterRemote)
        assertEquals(DesktopDocumentRegistry.get(open.id).flatMap(_.conflict), None)
        assertEquals(state.documentIsDirty, false)
      }
    }
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
