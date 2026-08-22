package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.projects.{DesktopLibrary, Library}

import scala.scalajs.js

/** Phase 3 item 6: a bound origin never reaches a viewer on its own.
  *
  * The record owns a bound file (§2). A change to it reconciles against the
  * record, and reaches the screen only if the record adopts it.
  */
class OriginRoutingSpec extends FunSuite:

  private def hashOf(text: String): String = f"${text.hashCode & 0xffffffffL}%08x"

  private val originPath = "/tmp/routed-origin.dot"

  private def installShell(): Unit =
    val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
      val a = args.asInstanceOf[js.Dynamic]
      command match
        case "hash_text" =>
          js.Promise.resolve[js.Any](hashOf(a.selectDynamic("text").asInstanceOf[String]))
        case "library_write" => js.Promise.resolve[js.Any](())
        case other           => js.Promise.reject(js.JavaScriptException(s"unexpected command $other"))
    js.Dynamic.global.window.__TAURI__ =
      js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

  private def boundRecord =
    Diagram(
      id = DiagramId("bound"),
      name = "bound",
      folder = FolderPath.root,
      format = "DOT",
      text = "digraph { a }",
      binding = Some(
        Binding(
          OriginUri.parse(s"file://$originPath").fold(e => fail(e), identity),
          SyncMode.Pull,
          ContentHash.fromHex(hashOf("digraph { a }")),
          lastSyncAt = 0L
        )
      ),
      metadata = DiagramMetadata(),
      createdAt = 1,
      updatedAt = 1
    )

  override def beforeEach(context: BeforeEach): Unit =
    installShell()
    OriginReconciler.reset()
    DesktopDocumentRegistry.reset()
    Library.install(DesktopLibrary(Vector(boundRecord)))

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    OriginReconciler.reset()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null


  test("a change to a bound origin opens no loose document session"):
    // The misrouting §1 lists. A bound file already has a home in the library;
    // a second, competing copy of it is exactly what must not appear.
    val changed = "digraph { a -> b }"

    DesktopBridge.routeDocumentChange(
      DesktopBridge.DesktopMessage(changed, Some(originPath), Some(hashOf(changed)))
    )

    assertEquals(
      DesktopDocumentRegistry.find(originPath),
      None,
      "a bound origin was opened as a loose file"
    )

  test("a change to an unbound file still opens a loose document session"):
    val loose = "/tmp/nobody-claims-this.dot"

    DesktopBridge.routeDocumentChange(
      DesktopBridge.DesktopMessage("digraph { x }", Some(loose), Some("rev-1"))
    )

    assertEquals(DesktopDocumentRegistry.find(loose).map(_.sourceText), Some("digraph { x }"))
