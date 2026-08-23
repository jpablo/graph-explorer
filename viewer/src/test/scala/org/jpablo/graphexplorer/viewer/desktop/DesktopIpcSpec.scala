package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.gxcore.model.ContentHash
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
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

  // ------------------------------- Phase 3: hashing the page cannot do itself

  test("the page asks the shell to hash, and sends only the stored text"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function(cmd, args) {
          window.__ipcCalls.push({ cmd: cmd, args: args });
          return Promise.resolve("deadbeef");
        } } };
      """
    )

    DesktopIpc.hashText("digraph G {\r\na\r\n}").map: hash =>
      val calls = recordedCalls
      assertEquals(calls.length, 1)
      assertEquals(calls(0).selectDynamic("cmd").asInstanceOf[String], "hash_text")

      val args = calls(0).selectDynamic("args")
      val keys = js.Object.keys(args.asInstanceOf[js.Object]).toList
      assertEquals(keys.sorted, List("text"))

      // Verbatim, line endings included. The convention is applied HERE, by
      // shared Scala, and the shell hashes the bytes it is given — so a
      // normalising send would silently defeat the CRLF rule.
      assertEquals(args.selectDynamic("text").asInstanceOf[String], "digraph G {\r\na\r\n}")
      assertEquals(hash, ContentHash.fromHex("deadbeef"))

  test("outside the desktop shell a hash FAILS rather than answering wrongly"):
    clearTauri()
    // A save degrades to `Unavailable` because "not saved" is a true and useful
    // answer. A hash has no such fallback: any value it invented would be
    // compared against real ones and would report a phantom conflict.
    DesktopIpc.hashText("digraph G { a }").failed.map: error =>
      assertEquals(error, IpcUnavailable)

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

  test("an event with no path opens no session, and ends none"):
    // Nothing in the shell sends one any more: every emitter carries a path,
    // and `/v1/push-text` names its session and arrives as a session command.
    // What is left is untrusted input, and the answer to it is "nothing" —
    // neither a new session nor the loss of an existing one.
    DesktopBridge.reset()
    DesktopDocumentRegistry.reset()
    DesktopBridge.recordSession(
      DesktopBridge.DesktopMessage("digraph {}", Some("/tmp/a.dot"), Some("f00d"))
    )
    DesktopBridge.recordSession(DesktopBridge.DesktopMessage("digraph { b }", None, None))

    assertEquals(
      DesktopDocumentRegistry.find("/tmp/a.dot").map(_.revision),
      Some("f00d")
    )
    assertEquals(DesktopDocumentRegistry.all.size, 1, "a pathless event opened a second session")
    DesktopBridge.reset()
    DesktopDocumentRegistry.reset()

  // ------------------------------------------- Phase 1: typed open targets

  private def openEvent(detail: js.Dynamic): dom.Event =
    js.Dynamic.literal(detail = detail).asInstanceOf[dom.Event]

  private def everyRecordExists: String => Boolean = _ => true
  private def noRecordExists: String => Boolean     = _ => false

  test("a library open request routes to that record, by id"):
    // The whole point of the typed target: the id survives the trip. `gx open`
    // used to resolve every reference to a PATH and discard the diagram id, so
    // an open could not say which record it meant — and an unbound record,
    // having no path, could not be opened at all.
    val decision = DesktopOpenRequests.decide(
      openEvent(js.Dynamic.literal(target = js.Dynamic.literal(kind = "library", diagramId = "architecture"))),
      everyRecordExists
    )
    decision match
      case DesktopOpenRequests.Decision.Show(route, _) => assertEquals(route, Route.ProjectDetail("architecture"))
      case other                                       => fail(s"expected a route, got $other")

  test("an open request we cannot act on navigates nowhere"):
    // Untrusted input from outside the page. Guessing a route would navigate
    // away from whatever the user is looking at, which is strictly worse than
    // refusing a request that names nothing.
    val unusable = List(
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "library")),                     // no id
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "library", diagramId = "   ")),   // blank id
      js.Dynamic.literal(target = js.Dynamic.literal(kind = "loose-file", diagramId = "x")),  // newer shell
      js.Dynamic.literal(target = js.Dynamic.literal(diagramId = "architecture")),            // no kind
      js.Dynamic.literal(target = js.Dynamic.literal()),                                      // empty target
      js.Dynamic.literal()                                                                    // no target at all
    )
    for detail <- unusable do
      assertEquals(
        DesktopOpenRequests.decide(openEvent(detail), everyRecordExists),
        DesktopOpenRequests.Decision.Reject("VIEW_REJECTED", "the page could not route to that target"),
        s"detail: $detail"
      )

  test("an open request for a record the library does not hold is refused, with a reason"):
    // The shell cannot make this check — D2.5 keeps it diagram-ignorant — so
    // the page has to, and has to SAY so. Navigating anyway would leave the
    // user on an empty diagram while `gx open` reported success.
    val decision = DesktopOpenRequests.decide(
      openEvent(js.Dynamic.literal(requestId = 7, target = js.Dynamic.literal(kind = "library", diagramId = "missing"))),
      noRecordExists
    )
    assertEquals(
      decision,
      DesktopOpenRequests.Decision.Reject("DIAGRAM_NOT_FOUND", "no diagram 'missing' in this library")
    )

  // ------------------------------- Phase 2 item 7: the file open handshake

  test("a file open request routes to that file's session, not to its path"):
    // The defect this repairs: a loose file had no route, so `gx open <path>`
    // reached no viewer on Home and landed in the wrong viewer with a project
    // open — while still reporting success.
    DesktopDocumentRegistry.reset()
    val open = DesktopDocumentRegistry.record("/tmp/opened.dot", "rev-1", "digraph G { a }")

    val decision = DesktopOpenRequests.decide(
      openEvent(js.Dynamic.literal(target = js.Dynamic.literal(kind = "file", path = "/tmp/opened.dot"))),
      noRecordExists
    )

    decision match
      case DesktopOpenRequests.Decision.Show(route, view) =>
        assertEquals(route, Route.LooseDocument(open.id.value))
        // §13: the acknowledgment names the session, and the route carries no path.
        val reported = view.asInstanceOf[js.Dynamic]
        assertEquals(reported.selectDynamic("kind").asInstanceOf[String], "file")
        assertEquals(reported.selectDynamic("sessionId").asInstanceOf[String], open.id.value)
        assertEquals(reported.selectDynamic("revision").asInstanceOf[String], "rev-1")
      case other => fail(s"expected a route to the session, got $other")

    DesktopDocumentRegistry.reset()

  test("a file open request the shell never delivered is refused, not reported as shown"):
    // THE defect item 7 repairs. Before this, a file `show` kept only the
    // NO_WINDOW check, so `gx open <path>` printed "showing ..." for a file no
    // viewer had.
    DesktopDocumentRegistry.reset()

    val decision = DesktopOpenRequests.decide(
      openEvent(js.Dynamic.literal(target = js.Dynamic.literal(kind = "file", path = "/tmp/never-sent.dot"))),
      noRecordExists
    )

    assertEquals(
      decision,
      DesktopOpenRequests.Decision.Reject(
        "DOCUMENT_NOT_FOUND",
        "the page holds no open document for that path"
      )
    )

  test("a second open of one file routes to the SAME session"):
    // §4.2: display repeats, the session does not. Two ids would give one file
    // two routes, and the back button would walk through dead ones.
    DesktopDocumentRegistry.reset()
    val first = DesktopDocumentRegistry.record("/tmp/twice.dot", "rev-1", "digraph G { a }")

    def routeNow() =
      DesktopOpenRequests.decide(
        openEvent(js.Dynamic.literal(target = js.Dynamic.literal(kind = "file", path = "/tmp/twice.dot"))),
        noRecordExists
      ) match
        case DesktopOpenRequests.Decision.Show(route, _) => route
        case other                                       => fail(s"expected a route, got $other")

    val before = routeNow()
    DesktopDocumentRegistry.record("/tmp/twice.dot", "rev-2", "digraph G { b }") // the file changed
    val after = routeNow()

    assertEquals(before, after)
    assertEquals(after, Route.LooseDocument(first.id.value))
    DesktopDocumentRegistry.reset()

  // ------------------------------------------------ Phase 0: target lifetime
  //
  // The listener is process-global while the target moves with navigation. With
  // nothing ever releasing the target, an unmounted viewer stayed attached and
  // kept receiving file events — the mechanism behind a loose file's source
  // being persisted into whichever library record was last displayed.

  test("detaching the attached viewer releases it"):
    withGraphvizAsync { graphviz =>
      DesktopBridge.reset()
      val state = ViewerState(ViewTarget.library("detach-me"), graphviz)
      DesktopBridge.attach(state)
      assert(DesktopBridge.currentTarget.isDefined, "precondition: a viewer is attached")

      DesktopBridge.detach(state)

      assertEquals(
        DesktopBridge.currentTarget,
        None,
        "an unmounted viewer stayed attached and kept receiving file events"
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
      val leaving  = ViewerState(ViewTarget.library("leaving"), graphviz)
      val arriving = ViewerState(ViewTarget.library("arriving"), graphviz)

      DesktopBridge.attach(leaving)
      DesktopBridge.attach(arriving) // navigation: the new view mounts first

      DesktopBridge.detach(leaving) // ...and only then does the old one unmount

      assert(
        DesktopBridge.currentTarget.exists(_ eq arriving),
        "a stale detach tore down the viewer that had just arrived"
      )
      DesktopBridge.reset()
      Future.unit
    }

  test("the session tier releases a detached viewer, and keeps a current one"):
    withGraphvizAsync { graphviz =>
      SessionCommands.reset()
      val leaving  = ViewerState(ViewTarget.library("s-leaving"), graphviz)
      val arriving = ViewerState(ViewTarget.library("s-arriving"), graphviz)

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
