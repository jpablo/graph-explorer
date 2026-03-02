package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.{Owner, unsafeWindowOwner}
import munit.FunSuite
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

class CommandsShortcutSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  test("Cmd/Ctrl+S invokes desktop save bridge when available"):
    withGraphvizAsync { graphviz =>
      given Owner = unsafeWindowOwner
      js.eval(
        """
          if (typeof window === 'undefined') { global.window = {}; }
          if (typeof document === 'undefined') {
            global.document = { activeElement: null };
            window.document = global.document;
          }
          if (typeof window.location === 'undefined') {
            window.location = { origin: 'http://localhost', search: '', pathname: '/', href: 'http://localhost/' };
          }
          if (typeof window.history === 'undefined') {
            window.history = {
              pushState: function(_,__,url) {
                var a = new URL(url, 'http://localhost');
                window.location.pathname = a.pathname;
                window.location.search = a.search;
                window.location.href = a.href;
              }
            };
          }

          window.__saveCallCount = 0;
          window.__graphExplorerDesktopBridge = {
            saveCurrentText: function() { window.__saveCallCount += 1; }
          };
        """
      )

      val state    = ViewerState(ProjectId("save-shortcut-spec"), graphviz)
      val commands = Commands(state, RouterCommands(Router()))

      var prevented = false
      val event = js.Dynamic
        .literal(
          key = "s",
          code = "KeyS",
          metaKey = true,
          ctrlKey = false,
          altKey = false,
          shiftKey = false,
          preventDefault = (() => prevented = true): js.Function0[Unit],
          stopPropagation = (() => ()): js.Function0[Unit]
        )
        .asInstanceOf[dom.KeyboardEvent]

      commands.handleKeyDown(event)

      val saveCallCount = js.Dynamic.global.window.selectDynamic("__saveCallCount").asInstanceOf[Int]
      assertEquals(saveCallCount, 1)
      assert(prevented, "Save shortcut should prevent browser default save behavior")
      afterMicrotasks(())
    }
