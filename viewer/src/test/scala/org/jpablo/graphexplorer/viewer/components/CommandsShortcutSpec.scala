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

  test("no two commands share a shortcut (byShortcut would silently drop one)"):
    withGraphvizAsync { graphviz =>
      given Owner = unsafeWindowOwner
      val state    = ViewerState(ProjectId("shortcut-dup-spec"), graphviz)
      val commands = Commands(state, RouterCommands(Router()))

      // byShortcut is a Map built with .toMap: two commands normalizing to the same
      // shortcut would not fail — the later one would silently shadow the earlier.
      // Dedupe first (a command may be listed under several headers), then compare.
      val declared = commands.byHeader.values.flatten
        .collect { case c @ Command(shortcut = Some(_)) => c }
        .toSeq
        .distinct
      assertEquals(
        commands.byShortcut.size,
        declared.size,
        s"shortcut collision — some of: ${declared.map(_.labelWithShortcut).mkString("; ")}"
      )

      // the bindings this spec was extended for: each traversal family is
      // bare = one hop, shift = transitive.
      def labelOf(sh: Shortcut) = commands.byShortcut.get(sh).map(_.shortLabel)
      assertEquals(labelOf(Shortcut("s")), Some("Select direct successors"))
      assertEquals(labelOf(Shortcut("s", shift = true)), Some("Select all successors"))
      assertEquals(labelOf(Shortcut("p")), Some("Select direct predecessors"))
      assertEquals(labelOf(Shortcut("p", shift = true)), Some("Select all predecessors"))
      // `p` was freed for the above; the displaced command pairs with `n` instead.
      assertEquals(labelOf(Shortcut("n", shift = true)), Some("New backwards node"))
      afterMicrotasks(())
    }
