package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** V-11 from the webview's side: the page holds no credential, and the payload
  * it sends over IPC has no room for one.
  *
  * The desktop's half is asserted in `desktop/src-tauri/src/main.rs` — the
  * `document.changed` script it evaluates in this page. Together they cover
  * both directions of the boundary.
  */
class DesktopIpcSpec extends FunSuite with TestHelpers:

  /** Install a fake `window.__TAURI__.core.invoke` that records what it was
    * called with and resolves/rejects on demand.
    */
  private def stubTauri(script: String): Unit =
    js.eval(
      s"""
        if (typeof window === 'undefined') { global.window = {}; }
        window.__ipcCalls = [];
        $script
      """
    )

  private def clearTauri(): Unit =
    js.eval("if (typeof window !== 'undefined') { delete window.__TAURI__; }")

  private def recordedCalls: js.Array[js.Dynamic] =
    js.Dynamic.global.window.selectDynamic("__ipcCalls").asInstanceOf[js.Array[js.Dynamic]]

  override def afterEach(context: AfterEach): Unit = clearTauri()

  test("a save sends exactly path/text/baseRevision — no credential (V-11)"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function(cmd, args) {
          window.__ipcCalls.push({ cmd: cmd, args: args });
          return Promise.resolve({ path: args.path, revision: "b2b2" });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph { a -> b }", "a1a1").map: outcome =>
      val calls = recordedCalls
      assertEquals(calls.length, 1)
      assertEquals(calls(0).selectDynamic("cmd").asInstanceOf[String], DesktopIpc.SaveDocument)

      val keys = js.Object.keys(calls(0).selectDynamic("args").asInstanceOf[js.Object]).toList
      // The whole point of D3: there is nothing here to steal. A new field
      // named `token` or `port` fails this test on sight.
      assertEquals(keys.sorted, List("baseRevision", "path", "text"))

      assertEquals(outcome, DesktopIpc.SaveOutcome.Saved("/tmp/a.dot", "b2b2"))

  test("a revision crosses the boundary as a string, verbatim"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function(cmd, args) {
          window.__ipcCalls.push({ cmd: cmd, args: args });
          return Promise.resolve({ path: args.path, revision: "cafe" });
        } } };
      """
    )

    // This test used to assert the opposite — that a Long arrived as a JS
    // NUMBER — because Scala.js represents Long as a RuntimeLong object that
    // serde would reject as a `u64`, so `saveDocument` called `.toDouble`.
    //
    // Under D1 a revision is a hex content hash. A string crosses as itself,
    // the conversion is gone, and a 64-bit value never has to survive a round
    // trip through a double. The hash must arrive UNTOUCHED, since both sides
    // compare it byte for byte.
    val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", hash).map: _ =>
      val revision = recordedCalls(0).selectDynamic("args").selectDynamic("baseRevision")
      assertEquals(js.typeOf(revision), "string")
      assertEquals(revision.asInstanceOf[String], hash)

  test("a rejected save carrying DOCUMENT_CONFLICT becomes a conflict"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function() {
          return Promise.reject({ code: 'DOCUMENT_CONFLICT', currentRevision: "dead",
                                  attemptedBaseRevision: "beef",
                                  message: 'file changed on disk since it was loaded' });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", "beef").map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Conflict(Some("dead")))

  test("any other rejection surfaces its message rather than vanishing"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function() {
          return Promise.reject({ code: 'DOCUMENT_WRITE_FAILED', message: 'disk full' });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", "a1a1").map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Failed("disk full"))

  test("outside the desktop shell a save is Unavailable, not an exception"):
    clearTauri()
    assert(!DesktopIpc.available)
    // A browser tab has no local file and no IPC. The viewer is the same app in
    // both places, so this has to be an outcome the UI can report, not a throw
    // that lands in the unhandled-rejection handler.
    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", "a1a1").map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Unavailable)

  test("a document.changed event yields only a path and a revision"):
    // Even when the shell sends extra fields, the parsed message has nowhere to
    // put them: the credential is gone from the type, not merely ignored.
    val event = js.Dynamic
      .literal(
        detail = js.Dynamic.literal(
          text = "digraph { a }",
          path = "/tmp/a.dot",
          revision = "c0ffee",
          token = "should-never-be-read",
          port = 61234
        )
      )
      .asInstanceOf[dom.Event]

    val message = DesktopBridge.extractMessage(event)
    assertEquals(
      message,
      Some(DesktopBridge.DesktopMessage("digraph { a }", Some("/tmp/a.dot"), Some("c0ffee")))
    )

  test("a text-only push does not forget which file is open"):
    // `/v1/push-text` emits `{text, path: null, revision: null}` — text with no
    // document behind it. If that cleared the reference, the next ⌘S would
    // report "no active watched file" for a file that is still very much open.
    DesktopBridge.reset()
    DesktopBridge.updateDocumentRef(
      DesktopBridge.DesktopMessage("digraph {}", Some("/tmp/a.dot"), Some("f00d"))
    )
    DesktopBridge.updateDocumentRef(DesktopBridge.DesktopMessage("digraph { b }", None, None))

    assertEquals(
      DesktopBridge.currentDocumentRef,
      Some(DesktopBridge.DocumentRef("/tmp/a.dot", "f00d"))
    )
    DesktopBridge.reset()

  // ------------------------------------------- Phase 1: typed open targets

  private def openEvent(detail: js.Dynamic): dom.Event =
    js.Dynamic.literal(detail = detail).asInstanceOf[dom.Event]

  test("a library open request routes to that record, by id"):
    // The whole point of the typed target: the id survives the trip. `gx open`
    // used to resolve every reference to a PATH and discard the diagram id, so
    // an open could not say which record it meant — and an unbound record,
    // having no path, could not be opened at all.
    val route = DesktopOpenRequests.route(
      openEvent(js.Dynamic.literal(target = js.Dynamic.literal(kind = "library", diagramId = "architecture")))
    )
    assertEquals(route, Some(Route.ProjectDetail("architecture")))

  test("an open request we cannot act on navigates nowhere"):
    // Untrusted input from outside the page. Guessing a route would navigate
    // away from whatever the user is looking at, which is strictly worse than
    // ignoring a request that names nothing.
    val unusable = List(
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "library")),                     // no id
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "library", diagramId = "   ")),   // blank id
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "loose-file", diagramId = "x")),  // newer shell
      js.Dynamic.literal(target = js.Dynamic.literal(diagramId = "architecture")),            // no kind
      js.Dynamic.literal(target = js.Dynamic.literal()),                                      // empty target
      js.Dynamic.literal()                                                                    // no target at all
    )
    for detail <- unusable do
      assertEquals(DesktopOpenRequests.route(openEvent(detail)), None, s"detail: $detail")

  test("an open request for a record the library does not hold routes nowhere"):
    // The shell cannot make this check — D2.5 keeps it diagram-ignorant — so
    // the page has to, and has to SAY so. Navigating anyway would leave the
    // user on an empty diagram while `gx open` reported success.
    //
    // The refusal itself travels through complete_open, which needs a live IPC
    // bridge; what is asserted here is the decision, and the round trip is
    // covered by scripts/local-capabilities-open-handshake-smoke.sh.
    val route = DesktopOpenRequests.route(
      openEvent(js.Dynamic.literal(requestId = 7, target = js.Dynamic.literal(kind = "library", diagramId = "missing")))
    )
    assertEquals(route, Some(Route.ProjectDetail("missing")))

  // ------------------------------------------------ Phase 0: target lifetime
  //
  // The listener is process-global while the target moves with navigation. With
  // nothing ever releasing the target, an unmounted viewer stayed attached and
  // kept receiving file events — the mechanism behind a loose file's source
  // being persisted into whichever library record was last displayed.

  test("detaching the attached viewer clears the target and its save destination"):
    withGraphvizAsync { graphviz =>
      DesktopBridge.reset()
      val state = ViewerState(ProjectId("detach-me"), graphviz)
      DesktopBridge.attach(state)
      DesktopBridge.updateDocumentRef(
        DesktopBridge.DesktopMessage("digraph {}", Some("/tmp/a.dot"), Some("f00d"))
      )
      assert(DesktopBridge.currentDocumentRef.isDefined, "precondition: a destination exists")

      DesktopBridge.detach(state)

      assertEquals(
        DesktopBridge.currentDocumentRef,
        None,
        "the ⌘S destination outlived the viewer that owned it"
      )
      DesktopBridge.reset()
      Future.unit
    }

  /** Laminar mounts the incoming view BEFORE unmounting the outgoing one, so
    * the old viewer's detach arrives after the new one has attached. Clearing
    * unconditionally would drop the live target and leave the window deaf.
    */
  test("a detach from a viewer that is no longer current leaves the live one alone"):
    withGraphvizAsync { graphviz =>
      DesktopBridge.reset()
      val leaving  = ViewerState(ProjectId("leaving"), graphviz)
      val arriving = ViewerState(ProjectId("arriving"), graphviz)

      DesktopBridge.attach(leaving)
      DesktopBridge.attach(arriving) // navigation: the new view mounts first
      DesktopBridge.updateDocumentRef(
        DesktopBridge.DesktopMessage("digraph {}", Some("/tmp/b.dot"), Some("beef"))
      )

      DesktopBridge.detach(leaving) // ...and only then does the old one unmount

      assertEquals(
        DesktopBridge.currentDocumentRef,
        Some(DesktopBridge.DocumentRef("/tmp/b.dot", "beef")),
        "a stale detach tore down the viewer that had just arrived"
      )
      DesktopBridge.reset()
      Future.unit
    }

  test("the session tier releases a detached viewer, and keeps a current one"):
    withGraphvizAsync { graphviz =>
      SessionCommands.reset()
      val leaving  = ViewerState(ProjectId("s-leaving"), graphviz)
      val arriving = ViewerState(ProjectId("s-arriving"), graphviz)

      SessionCommands.attach(leaving)
      SessionCommands.detach(leaving)
      assertEquals(SessionCommands.currentTarget, None, "the session tier kept a departed viewer")

      SessionCommands.attach(leaving)
      SessionCommands.attach(arriving)
      SessionCommands.detach(leaving)
      assert(
        SessionCommands.currentTarget.exists(_ eq arriving),
        "a stale detach cleared the viewer that is actually on screen"
      )
      SessionCommands.reset()
      Future.unit
    }
