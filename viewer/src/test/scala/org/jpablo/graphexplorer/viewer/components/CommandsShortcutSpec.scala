package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.{Owner, unsafeWindowOwner}
import munit.FunSuite
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

class CommandsShortcutSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  test("Cmd/Ctrl+S saves through the viewer, with no desktop bridge present"):
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

          // The global is gone entirely now — Phase 4 removed it, and nothing
          // ever called it. This delete is what the test asserted BEFORE that,
          // when the object still existed and ⌘S had to work without it. Kept
          // so the shortcut is still exercised against a page that has no
          // desktop surface at all.
          delete window.__graphExplorerDesktopBridge;
        """
      )

      val state    = ViewerState(ViewTarget.library("save-shortcut-spec"), graphviz)
      val commands = Commands(state, RouterCommands(Router()))

      var reported = List.empty[String]
      state.infoBus.events.foreach(message => reported = reported :+ message)

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

      assert(prevented, "Save shortcut should prevent browser default save behavior")
      afterMicrotasks {
        assertEquals(
          reported,
          List("Saved"),
          "⌘S must save through the viewer's own store, with no desktop bridge present"
        )
      }
    }

  test("Cmd/Ctrl+V is left for the browser's paste event, not consumed here"):
    withGraphvizAsync { graphviz =>
      given Owner = unsafeWindowOwner
      val state    = ViewerState(ViewTarget.library("paste-passthrough-spec"), graphviz)
      val commands = Commands(state, RouterCommands(Router()))

      // `pasteDiagram` advertises ⌘V so the menus can show it, which puts it in
      // `byShortcut`. Dispatching it here would preventDefault, and the default
      // action of this keydown IS the paste event the canvas listens for — the
      // gesture would stop working, and the clipboard-permission prompt would
      // come back with it.
      def press(meta: Boolean, ctrl: Boolean) =
        var prevented = false
        val event = js.Dynamic
          .literal(
            key = "v",
            code = "KeyV",
            metaKey = meta,
            ctrlKey = ctrl,
            altKey = false,
            shiftKey = false,
            preventDefault = (() => prevented = true): js.Function0[Unit],
            stopPropagation = (() => ()): js.Function0[Unit]
          )
          .asInstanceOf[dom.KeyboardEvent]
        commands.handleKeyDown(event)
        prevented

      assert(!press(meta = true, ctrl = false), "Cmd+V must reach the browser")
      assert(!press(meta = false, ctrl = true), "Ctrl+V must reach the browser")
      afterMicrotasks(())
    }

  test("no two commands share a shortcut (byShortcut would silently drop one)"):
    withGraphvizAsync { graphviz =>
      given Owner = unsafeWindowOwner
      val state    = ViewerState(ViewTarget.library("shortcut-dup-spec"), graphviz)
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
