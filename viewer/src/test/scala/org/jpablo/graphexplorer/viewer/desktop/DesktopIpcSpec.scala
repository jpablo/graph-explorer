package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** V-11 from the webview's side: the page holds no credential, and the payload
  * it sends over IPC has no room for one.
  *
  * The desktop's half is asserted in `desktop/src-tauri/src/main.rs` — the
  * `document.changed` script it evaluates in this page. Together they cover
  * both directions of the boundary.
  */
class DesktopIpcSpec extends FunSuite:

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
          return Promise.resolve({ path: args.path, revision: args.baseRevision + 1 });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph { a -> b }", 4).map: outcome =>
      val calls = recordedCalls
      assertEquals(calls.length, 1)
      assertEquals(calls(0).selectDynamic("cmd").asInstanceOf[String], DesktopIpc.SaveDocument)

      val keys = js.Object.keys(calls(0).selectDynamic("args").asInstanceOf[js.Object]).toList
      // The whole point of D3: there is nothing here to steal. A new field
      // named `token` or `port` fails this test on sight.
      assertEquals(keys.sorted, List("baseRevision", "path", "text"))

      assertEquals(outcome, DesktopIpc.SaveOutcome.Saved("/tmp/a.dot", 5L))

  test("a Long revision crosses the boundary as a JS number"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function(cmd, args) {
          window.__ipcCalls.push({ cmd: cmd, args: args });
          return Promise.resolve({ path: args.path, revision: 2 });
        } } };
      """
    )

    // Scala.js represents Long as a RuntimeLong object, which serde would
    // reject as a u64. The conversion is in `saveDocument`, not at every call
    // site, so it is pinned here.
    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", 1).map: _ =>
      val revision = recordedCalls(0).selectDynamic("args").selectDynamic("baseRevision")
      assertEquals(js.typeOf(revision), "number")

  test("a rejected save carrying DOCUMENT_CONFLICT becomes a conflict"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function() {
          return Promise.reject({ code: 'DOCUMENT_CONFLICT', currentRevision: 9,
                                  attemptedBaseRevision: 4,
                                  message: 'file changed on disk since it was loaded' });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", 4).map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Conflict(Some(9L)))

  test("any other rejection surfaces its message rather than vanishing"):
    stubTauri(
      """
        window.__TAURI__ = { core: { invoke: function() {
          return Promise.reject({ code: 'DOCUMENT_WRITE_FAILED', message: 'disk full' });
        } } };
      """
    )

    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", 1).map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Failed("disk full"))

  test("outside the desktop shell a save is Unavailable, not an exception"):
    clearTauri()
    assert(!DesktopIpc.available)
    // A browser tab has no local file and no IPC. The viewer is the same app in
    // both places, so this has to be an outcome the UI can report, not a throw
    // that lands in the unhandled-rejection handler.
    DesktopIpc.saveDocument("/tmp/a.dot", "digraph {}", 1).map: outcome =>
      assertEquals(outcome, DesktopIpc.SaveOutcome.Unavailable)

  test("a document.changed event yields only a path and a revision"):
    // Even when the shell sends extra fields, the parsed message has nowhere to
    // put them: the credential is gone from the type, not merely ignored.
    val event = js.Dynamic
      .literal(
        detail = js.Dynamic.literal(
          text = "digraph { a }",
          path = "/tmp/a.dot",
          revision = 3,
          token = "should-never-be-read",
          port = 61234
        )
      )
      .asInstanceOf[dom.Event]

    val message = DesktopBridge.extractMessage(event)
    assertEquals(
      message,
      Some(DesktopBridge.DesktopMessage("digraph { a }", Some("/tmp/a.dot"), Some(3L)))
    )

  test("a text-only push does not forget which file is open"):
    // `/v1/push-text` emits `{text, path: null, revision: null}` — text with no
    // document behind it. If that cleared the reference, the next ⌘S would
    // report "no active watched file" for a file that is still very much open.
    DesktopBridge.reset()
    DesktopBridge.updateDocumentRef(
      DesktopBridge.DesktopMessage("digraph {}", Some("/tmp/a.dot"), Some(2L))
    )
    DesktopBridge.updateDocumentRef(DesktopBridge.DesktopMessage("digraph { b }", None, None))

    assertEquals(
      DesktopBridge.currentDocumentRef,
      Some(DesktopBridge.DocumentRef("/tmp/a.dot", 2L))
    )
    DesktopBridge.reset()
