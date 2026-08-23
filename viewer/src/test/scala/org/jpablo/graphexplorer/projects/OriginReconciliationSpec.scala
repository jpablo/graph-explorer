package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.components.OriginStateBanner
import org.jpablo.graphexplorer.viewer.desktop.OriginReconciler
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Phase 3 items 1, 4 and 6: an origin change reaches its RECORD.
  *
  * Before this, an origin event reached whichever viewer was on screen and
  * replaced its text. These assert the other behaviour: the record decides, and
  * the screen follows the record.
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
      name = id,
      folder = FolderPath.root,
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
      metadata = DiagramMetadata(),
      createdAt = 1,
      updatedAt = 1
    )

  private def withLibrary[A](records: Diagram*)(f: (FakeShell, DesktopLibrary) => Future[A]): Future[A] =
    val shell = FakeShell()
    shell.install()
    OriginReconciler.reset()
    val library = DesktopLibrary(records.toVector)
    Library.install(library)
    f(shell, library).map: a =>
      Library.restoreDefault()
      OriginReconciler.reset()
      shell.uninstall()
      a

  // ---------------------------------------------------- item 1: the index

  test("a record is found by the file it is bound to"):
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (_, library) =>
      assertEquals(library.recordsBoundTo(originPath).map(_.id), List(ProjectId("a")))
      assertEquals(library.recordsBoundTo("/tmp/somewhere-else.dot"), Nil)
      Future.unit

  test("one file backing two records reaches both"):
    // OriginUri's own reason for not keying records by origin: one file may
    // legitimately back several records with different metadata.
    withLibrary(
      bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }"),
      bound("b", "digraph { a }", SyncMode.Pull, base = "digraph { a }")
    ): (_, library) =>
      assertEquals(library.recordsBoundTo(originPath).map(_.id.value).sorted, List("a", "b"))
      Future.unit

  test("a Windows record is found by the path the shell reports"):
    // The shell reports `C:\\Users\\x\\a.dot`. The binding stores a URI, and
    // decoding it back yields `C:/Users/x/a.dot` — the same file, spelled with
    // the other separator. A byte comparison misses it, and the miss is silent:
    // the file reads as unbound and opens as a loose document.
    //
    // Testable on any platform, because it is string handling and not a
    // property of the machine running the test.
    val windows = Diagram(
      id = DiagramId("w"),
      name = "w",
      folder = FolderPath.root,
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
      metadata = DiagramMetadata(),
      createdAt = 1,
      updatedAt = 1
    )

    withLibrary(windows): (_, library) =>
      assertEquals(
        library.recordsBoundTo("C:\\Users\\x\\a.dot").map(_.id.value),
        List("w"),
        "the shell's spelling of a Windows path did not reach its record"
      )
      Future.unit

  // -------------------------------------------- item 4: the record follows

  test("a Pull record whose origin moved adopts the file's text and its hash"):
    val agreed = "digraph { a }"
    withLibrary(bound("a", agreed, SyncMode.Pull, base = agreed)): (_, library) =>
      val changed = "digraph { a -> b }"
      OriginReconciler.reconcile(originPath, changed, hashOf(changed)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Behind))

        val record = library.recordsBoundTo(originPath).head
        assertEquals(record.text, changed, "the record did not follow its origin")
        assertEquals(record.binding.baseHash, ContentHash.fromHex(hashOf(changed)))

  test("a Pull record with local edits keeps them, and reports that it needs a person"):
    // §5.3: a local edit blocks the auto-pull rather than being overwritten.
    // The three-hash comparison calls this Diverged, and nothing is written.
    withLibrary(bound("a", "digraph { mine }", SyncMode.Pull, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      OriginReconciler.reconcile(originPath, theirs, hashOf(theirs)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Diverged))
        assertEquals(
          library.recordsBoundTo(originPath).head.text,
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

        val record = library.recordsBoundTo(originPath).head
        assertEquals(record.text, same)
        assertEquals(record.binding.baseHash, ContentHash.fromHex(hashOf(same)))

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
        assertEquals(divergedNow.get(ProjectId("a")), Some(SyncState.Diverged))
        assertEquals(settled.get(ProjectId("a")), None, "a settled record must stop asking for attention")

  // ------------------------------------------ item 5: the person decides

  test("taking the file's version adopts its text and settles the record"):
    withLibrary(bound("a", "digraph { mine }", SyncMode.Sync, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      OriginReconciler.reconcile(originPath, theirs, hashOf(theirs)).map: _ =>
        OriginReconciler.takeOrigin(ProjectId("a"))

        val record = library.recordsBoundTo(originPath).head
        assertEquals(record.text, theirs, "the record did not take the file's version")
        assertEquals(record.binding.baseHash, ContentHash.fromHex(hashOf(theirs)))
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(ProjectId("a")),
          None,
          "a resolved record must stop asking for attention"
        )

  test("keeping this diagram moves only the baseline, and the record then reads Ahead"):
    // The baseline HAS to move. Left where it was, base, local and remote stay
    // all different — which is Diverged — and the strip would come straight
    // back, so the decision would have changed nothing.
    val mine = "digraph { mine }"
    withLibrary(bound("a", mine, SyncMode.Pull, base = "digraph { agreed }")): (_, library) =>
      val theirs = "digraph { theirs }"
      for
        _   <- OriginReconciler.reconcile(originPath, theirs, hashOf(theirs))
        _    = OriginReconciler.keepRecord(ProjectId("a"))
        kept = library.recordsBoundTo(originPath).head
        // The SAME file, read again. Base and remote now agree and only the
        // record differs, which is Ahead — and Ahead asks nobody anything.
        again <- OriginReconciler.reconcile(originPath, theirs, hashOf(theirs))
      yield
        assertEquals(kept.text, mine, "the file's version overwrote the record")
        assertEquals(kept.binding.baseHash, ContentHash.fromHex(hashOf(theirs)))
        assertEquals(again.map(_._2), List(SyncState.Ahead))
        assertEquals(OriginReconciler.unresolved.observe.now().get(ProjectId("a")), None)

  test("a push that loses its compare-and-swap keeps the divergence, and offers nothing to adopt"):
    // Base and remote agree and only the record differs, so Sync pushes. The
    // shell refuses because the file moved after the read.
    //
    // Two behaviours at once. The divergence must SURVIVE: the state the write
    // reached is reported, not the state the plan predicted, which used to
    // overwrite the mark with the settled `Ahead` the moment it was made. And
    // it must offer no resolution: the page's copy of the file is older than
    // the file, so adopting it would write a version nobody was shown.
    val onDisk = "digraph { agreed }"
    withLibrary(bound("a", "digraph { mine }", SyncMode.Sync, base = onDisk)): (shell, library) =>
      shell.conflictOnWrite = true
      OriginReconciler.reconcile(originPath, onDisk, hashOf(onDisk)).map: outcomes =>
        assertEquals(outcomes.map(_._2), List(SyncState.Diverged))
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(ProjectId("a")),
          Some(SyncState.Diverged),
          "a lost compare-and-swap was recorded and then erased"
        )

        OriginReconciler.takeOrigin(ProjectId("a"))
        assertEquals(
          library.recordsBoundTo(originPath).head.text,
          "digraph { mine }",
          "a stale copy of the file was adopted"
        )
        assertEquals(
          OriginReconciler.unresolved.observe.now().get(ProjectId("a")),
          Some(SyncState.Diverged)
        )

  // ------------------------------------------------ the strip on the screen

  /** Render the strip for one record, and return the element holding it.
    *
    * A real mount rather than an inspection of the signal. The strip's whole
    * job is to turn an `Unresolved` into buttons, and a pattern that matches
    * nothing compiles perfectly and shows nothing.
    */
  private def mountedStrip(id: String): dom.Element =
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    render(container, OriginStateBanner(ViewTarget.library(id)))
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
          "a diverged record was reported with nothing to do about it"
        )

        container.querySelector("button").asInstanceOf[dom.html.Button].click()

        assertEquals(library.recordsBoundTo(originPath).head.text, theirs)
        assertEquals(buttonLabels(container), Nil, "the strip stayed after the decision")
        container.remove()

  test("a record with no divergence shows no strip"):
    val agreed = "digraph { a }"
    withLibrary(bound("a", agreed, SyncMode.Pull, base = agreed)): (_, _) =>
      val container = mountedStrip("a")
      OriginReconciler.reconcile(originPath, "digraph { a -> b }", hashOf("digraph { a -> b }")).map: _ =>
        assertEquals(container.textContent, "", "a Behind record asked for attention")
        container.remove()

  // ------------------------------------ the watch that makes the rest run

  private def unbound(id: String, text: String) =
    Diagram(
      id = DiagramId(id),
      name = id,
      folder = FolderPath.root,
      format = "DOT",
      text = text,
      binding = None,
      metadata = DiagramMetadata(),
      createdAt = 1,
      updatedAt = 1
    )

  test("opening a bound record asks the shell to watch its origin"):
    // The missing link. Reconciliation runs on a document event, and the shell
    // sends one only for a file it watches — and nothing in the page had ever
    // asked for a watch. An origin edit therefore reached the app only after
    // `gx open` had watched the file, and opening the same record from the
    // library was silent.
    withLibrary(bound("a", "digraph { a }", SyncMode.Pull, base = "digraph { a }")): (shell, _) =>
      withGraphvizAsync: graphviz =>
        ViewerState(ViewTarget.library("a"), graphviz, _ => ())
        afterMicrotasks:
          assertEquals(shell.opened.toList, List(originPath))

  test("opening a record with no binding asks for no watch"):
    withLibrary(unbound("a", "digraph { a }")): (shell, _) =>
      withGraphvizAsync: graphviz =>
        ViewerState(ViewTarget.library("a"), graphviz, _ => ())
        afterMicrotasks:
          assertEquals(shell.opened.toList, Nil, "a record with no origin asked to watch something")
