package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.components.OriginStateBanner
import org.jpablo.graphexplorer.viewer.desktop.OriginReconciler
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Phase 3 items 1, 4 and 6: a change to an origin reaches its DIAGRAM.
  *
  * Before this change, an origin event went to the viewer on the screen and
  * replaced its text. These tests check the other behaviour: the diagram makes
  * the decision, and then the screen shows the diagram.
  */
class OriginReconciliationSpec extends FunSuite with TestHelpers:

  private given com.raquo.airstream.ownership.Owner = com.raquo.laminar.api.L.unsafeWindowOwner

  /** A stand-in for the shell. Its `hash_text` is deterministic and injective
    * over the text, which is all the three-hash comparison asks of a digest.
    */
  private class FakeShell:
    val files: mutable.Map[String, String]   = mutable.Map.empty
    val written: mutable.Buffer[String]      = mutable.Buffer.empty

    /** The file moves between the read and the write, so the shell's
      * compare-and-swap refuses. This is the one failure the page cannot
      * recover from with the copy it holds.
      */
    var conflictOnWrite: Boolean = false

    /** The paths the page asked the shell to watch, in order. */
    val opened: mutable.Buffer[String] = mutable.Buffer.empty

    def install(): Unit =
      val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
        val a = args.asInstanceOf[js.Dynamic]
        command match
          case "hash_text" =>
            js.Promise.resolve[js.Any](hashOf(a.selectDynamic("text").asInstanceOf[String]))
          case "open_document" =>
            val path = a.selectDynamic("path").asInstanceOf[String]
            opened += path
            js.Promise.resolve[js.Any](js.Dynamic.literal(path = path, revision = "seed", text = ""))
          case "save_document" =>
            val path = a.selectDynamic("path").asInstanceOf[String]
            val text = a.selectDynamic("text").asInstanceOf[String]
            if conflictOnWrite then
              js.Promise.reject(js.Dynamic.literal(code = "DOCUMENT_CONFLICT", currentRevision = "beef"))
            else
              written += path
              js.Promise.resolve[js.Any](js.Dynamic.literal(path = path, revision = hashOf(text)))
          case "library_write" =>
            val name = a.selectDynamic("name").asInstanceOf[String]
            files(name) = a.selectDynamic("json").asInstanceOf[String]
            written += name
            js.Promise.resolve[js.Any](())
          case "library_list" => js.Promise.resolve[js.Any](js.Array())
          case other          => js.Promise.reject(js.JavaScriptException(s"unexpected command $other"))

      js.Dynamic.global.window.__TAURI__ =
        js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

    def uninstall(): Unit = js.Dynamic.global.window.__TAURI__ = null

  private def hashOf(text: String): String = f"${text.hashCode & 0xffffffffL}%08x"

  private val originPath = "/tmp/origin.dot"

  private def bound(id: String, text: String, mode: SyncMode, base: String) =
    Diagram(
      id = DiagramId(id),
      format = "DOT",
      text = text,
      binding = Some(
        Binding(
          OriginUri.parse(s"file://$originPath").fold(e => fail(e), identity),
          mode,
          ContentHash.fromHex(hashOf(base)),
          lastSyncAt = 0L
        )
      ),
      metadata = DiagramMetadata(name = id, folder = FolderPath.root),
      createdAt = 1,
      updatedAt = 1
    )

  private def withLibrary[A](diagrams: Diagram*)(f: (FakeShell, DesktopLibrary) => Future[A]): Future[A] =
    val shell = FakeShell()
    shell.install()
    OriginReconciler.reset()
    val library = DesktopLibrary(diagrams.toVector)
    Library.install(library)
    f(shell, library).map: a =>
      Library.restoreDefault()
      OriginReconciler.reset()
      shell.uninstall()
      a

  // ---------------------------------------------------- item 1: the index

  test("a diagram is found by the file it is bound to"):
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (_, library) =>
      assertEquals(library.diagramsBoundTo(originPath).map(_.id), List(DiagramId("a")))
      assertEquals(library.diagramsBoundTo("/tmp/somewhere-else.dot"), Nil)
      Future.unit

  test("one file backing two diagrams reaches both"):
    // This is the reason that OriginUri does not use the origin as the key of
    // a diagram: one file can be the origin of several diagrams with different
    // metadata.
    withLibrary(
      bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }"),
      bound("b", "digraph { a }", SyncMode.Pull, base = "digraph { a }")
    ): (_, library) =>
      assertEquals(library.diagramsBoundTo(originPath).map(_.id.value).sorted, List("a", "b"))
      Future.unit

  test("a Windows diagram is found by the path the shell reports"):
    // The shell reports `C:\\Users\\x\\a.dot`. The binding stores a URI, and
    // decoding it back yields `C:/Users/x/a.dot` — the same file, spelled with
    // the other separator. A byte comparison misses it, and the miss is silent:
    // the file reads as unbound and opens as a loose document.
    //
    // Testable on any platform, because it is string handling and not a
    // property of the machine running the test.
    val windows = Diagram(
      id = DiagramId("w"),
      format = "DOT",
      text = "digraph { a }",
      binding = Some(
        Binding(
          OriginUri.parse("file:///C:/Users/x/a.dot").fold(e => fail(e), identity),
          SyncMode.Pull,
          ContentHash.fromHex(hashOf("digraph { a }")),
          lastSyncAt = 0L
        )
      ),
      metadata = DiagramMetadata(name = "w", folder = FolderPath.root),
      createdAt = 1,
      updatedAt = 1
    )

    withLibrary(windows): (_, library) =>
      assertEquals(
        library.diagramsBoundTo("C:\\Users\\x\\a.dot").map(_.id.value),
        List("w"),
        "the shell's spelling of a Windows path did not reach its diagram"
      )
      Future.unit

  // ------------------------------------- item 4: the diagram takes the change

  test("a Pull diagram whose origin moved adopts the file's text and its hash"):
    val agreed = "digraph { a }"
    withLibrary(bound("a", agreed, SyncMode.Pull, base = agreed)): (_, library) =>
      val changed = "digraph { a -> b }"
      OriginReconciler.reconcile(originPath, changed, hashOf(changed)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Behind))

        val diagram = library.diagramsBoundTo(originPath).head
        assertEquals(diagram.text, changed, "the diagram did not follow its origin")
        assertEquals(diagram.binding.baseHash, ContentHash.fromHex(hashOf(changed)))

  test("a Pull diagram with local edits keeps them, and reports that it needs a person"):
    // §5.3: a local edit blocks the auto-pull rather than being overwritten.
    // The three-hash comparison calls this Diverged, and nothing is written.
    withLibrary(bound("a", "digraph { mine }", SyncMode.Pull, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      OriginReconciler.reconcile(originPath, theirs, hashOf(theirs)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Diverged))
        assertEquals(
          library.diagramsBoundTo(originPath).head.text,
          "digraph { mine }",
          "a local edit was overwritten by the file"
        )

  test("a byte-identical regeneration advances the baseline and rewrites no text"):
    // The row that is easy to omit and expensive to omit: a generator that
    // rewrites a file to the same content hits this on every run.
    val same = "digraph { a }"
    withLibrary(bound("a", same, SyncMode.Sync, base = "something older")): (_, library) =>
      OriginReconciler.reconcile(originPath, same, hashOf(same)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Converged))

        val diagram = library.diagramsBoundTo(originPath).head
        assertEquals(diagram.text, same)
        assertEquals(diagram.binding.baseHash, ContentHash.fromHex(hashOf(same)))

  test("a divergence is remembered for the UI, and agreement clears it"):
    withLibrary(bound("a", "digraph { mine }", SyncMode.Sync, base = "digraph { agreed }")): (_, _) =>
      val theirs = "digraph { theirs }"
      for
        _ <- OriginReconciler.reconcile(originPath, theirs, hashOf(theirs))
        divergedNow = OriginReconciler.unresolved.observe.now()
        // The person resolves it outside this page, and the file now matches.
        _ <- OriginReconciler.reconcile(originPath, "digraph { mine }", hashOf("digraph { mine }"))
        settled = OriginReconciler.unresolved.observe.now()
      yield
        assertEquals(divergedNow.get(DiagramId("a")), Some(SyncState.Diverged))
        assertEquals(settled.get(DiagramId("a")), None, "a settled diagram must stop asking for attention")

  // ------------------------------------------ item 5: the person decides

  test("taking the file's version adopts its text and settles the diagram"):
    withLibrary(bound("a", "digraph { mine }", SyncMode.Sync, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      OriginReconciler.reconcile(originPath, theirs, hashOf(theirs)).map: _ =>
        OriginReconciler.takeOrigin(DiagramId("a"))

        val diagram = library.diagramsBoundTo(originPath).head
        assertEquals(diagram.text, theirs, "the diagram did not take the file's version")
        assertEquals(diagram.binding.baseHash, ContentHash.fromHex(hashOf(theirs)))
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(DiagramId("a")),
          None,
          "a resolved diagram must stop asking for attention"
        )

  test("keeping this diagram moves only the baseline, and the diagram then reads Ahead"):
    // The baseline HAS to move. Left where it was, base, local and remote stay
    // all different — which is Diverged — and the strip would come straight
    // back, so the decision would have changed nothing.
    val mine = "digraph { mine }"
    withLibrary(bound("a", mine, SyncMode.Pull, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      for
        _   <- OriginReconciler.reconcile(originPath, theirs, hashOf(theirs))
        _    = OriginReconciler.keepDiagram(DiagramId("a"))
        kept = library.diagramsBoundTo(originPath).head
        // The app reads the SAME file again. Now the base and the remote agree,
        // and only the diagram is different. That state is Ahead, and Ahead
        // asks no question of the person.
        again <- OriginReconciler.reconcile(originPath, theirs, hashOf(theirs))
      yield
        assertEquals(kept.text, mine, "the file's version overwrote the diagram")
        assertEquals(kept.binding.baseHash, ContentHash.fromHex(hashOf(theirs)))
        assertEquals(again.map(_._2), List(SyncState.Ahead))
        assertEquals(OriginReconciler.unresolved.observe.now().get(DiagramId("a")), None)

  test("a push that loses its compare-and-swap keeps the divergence, and offers nothing to adopt"):
    // The base and the remote agree, and only the diagram is different, so Sync
    // pushes. The shell refuses, because the file changed after the read.
    //
    // This test checks two behaviours. First, the divergence must STAY. The
    // reconciler reports the state that the write reached, not the state that
    // the plan predicted. Before, the predicted state replaced the mark with the
    // settled `Ahead` immediately after the reconciler made the mark. Second,
    // the divergence must offer no resolution. The copy of the file in the page
    // is older than the file, so an adoption writes a version that no person saw.
    val onDisk = "digraph { agreed }"
    withLibrary(bound("a", "digraph { mine }", SyncMode.Sync, base = onDisk)): (shell, library) =>
      shell.conflictOnWrite = true
      OriginReconciler.reconcile(originPath, onDisk, hashOf(onDisk)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Diverged))
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(DiagramId("a")),
          Some(SyncState.Diverged),
          "a lost compare-and-swap was recorded and then erased"
        )

        OriginReconciler.takeOrigin(DiagramId("a"))
        assertEquals(
          library.diagramsBoundTo(originPath).head.text,
          "digraph { mine }",
          "a stale copy of the file was adopted"
        )
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(DiagramId("a")),
          Some(SyncState.Diverged)
        )

  // ------------------------------------------------ the strip on the screen

  /** Render the strip for one diagram, and return the element that holds it.
    *
    * This is a real mount, not an inspection of the signal. The only job of the
    * strip is to change an `Unresolved` into buttons. A pattern that matches
    * nothing compiles without an error and shows nothing.
    */
  private def mountedStrip(id: String): dom.Element =
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    render(container, OriginStateBanner(ViewTarget.diagram(id)))
    container

  private def buttonLabels(container: dom.Element): List[String] =
    container.querySelectorAll("button").toList.map(_.textContent)

  test("a divergence the page can resolve shows both actions, and a click carries one out"):
    withLibrary(bound("a", "digraph { mine }", SyncMode.Pull, base = "digraph { agreed }")): (_, library) =>
      val theirs    = "digraph { theirs }"
      val container = mountedStrip("a")

      OriginReconciler.reconcile(originPath, theirs, hashOf(theirs)).map: _ =>
        assertEquals(
          buttonLabels(container),
          List("Take the file's version", "Keep this diagram"),
          "a diverged diagram was reported with nothing to do about it"
        )

        container.querySelector("button").asInstanceOf[dom.html.Button].click()

        assertEquals(library.diagramsBoundTo(originPath).head.text, theirs)
        assertEquals(buttonLabels(container), Nil, "the strip stayed after the decision")
        container.remove()

  test("a diagram with no divergence shows no strip"):
    val agreed = "digraph { a }"
    withLibrary(bound("a", agreed, SyncMode.Pull, base = agreed)): (_, _) =>
      val container = mountedStrip("a")
      OriginReconciler.reconcile(originPath, "digraph { a -> b }", hashOf("digraph { a -> b }")).map: _ =>
        assertEquals(container.textContent, "", "a Behind diagram asked for attention")
        container.remove()

  // ------------------------------------ the watch that makes the rest run

  private def unbound(id: String, text: String) =
    Diagram(
      id = DiagramId(id),
      format = "DOT",
      text = text,
      binding = None,
      metadata = DiagramMetadata(name = id, folder = FolderPath.root),
      createdAt = 1,
      updatedAt = 1
    )

  test("opening a bound diagram asks the shell to watch its origin"):
    // Before, this part did not exist. Reconciliation runs on a document event, and
    // the shell sends one only for a file that it watches. But no code in the
    // page asked for a watch. Thus an edit to an origin reached the app only
    // after `gx open` had a watch on the file. When a person opened the same
    // diagram from the library, the app did not see the edit.
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (shell, _) =>
      withGraphvizAsync: graphviz =>
        ViewerState(ViewTarget.diagram("a"), graphviz, _ => ())
        afterMicrotasks:
          assertEquals(shell.opened.toList, List(originPath))

  test("opening a diagram with no binding asks for no watch"):
    withLibrary(unbound("a", "digraph { a }")): (shell, _) =>
      withGraphvizAsync: graphviz =>
        ViewerState(ViewTarget.diagram("a"), graphviz, _ => ())
        afterMicrotasks:
          assertEquals(shell.opened.toList, Nil, "a diagram with no origin asked to watch something")

  // ------------------------------------------- the diagram reaches the SCREEN

  test("a diagram changed from outside reaches an open viewer"):
    // No test checked this step before. `restorePersistedState` read the store
    // one time at mount. Thus a pull went into the diagram, but the screen did
    // not show it until the person opened the view again. Also, "take the
    // file's version" removed the strip, but the screen still showed the old
    // text.
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (_, _) =>
      withGraphvizAsync: graphviz =>
        val state   = ViewerState(ViewTarget.diagram("a"), graphviz, _ => ())
        val changed = "digraph { a -> b }"
        OriginReconciler.reconcile(originPath, changed, hashOf(changed)).flatMap: _ =>
          afterMicrotasks:
            assertEquals(state.sourceText.now(), changed, "the screen did not follow the diagram")

  test("a viewer's own edit is not echoed back over it"):
    // The store's stream carries this viewer's writes back. Adopting them
    // blindly would fight the person typing.
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (_, _) =>
      withGraphvizAsync: graphviz =>
        val state = ViewerState(ViewTarget.diagram("a"), graphviz, _ => ())
        val mine  = "digraph { mine }"
        state.replaceSourceDetectingFormat(mine)
        afterMicrotasks:
          assertEquals(state.sourceText.now(), mine, "the viewer's own edit was overwritten")
