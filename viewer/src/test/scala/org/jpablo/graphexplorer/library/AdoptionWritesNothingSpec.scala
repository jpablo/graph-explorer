package org.jpablo.graphexplorer.library

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{DiagramId, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** An open viewer adopts a change from a different writer, and the adoption
  * is not an edit.
  *
  * After the adoption, the snapshot of the viewer is the diagram that the
  * library holds. Thus the "a visit is not an edit" guard of
  * `DesktopLibrary.writeNow` must skip the write. A write here moves
  * `updatedAt`, and it can overwrite a second change from `gx`. The adoption
  * also must not reset what the person has on the screen: the selection and
  * the viewport.
  */
class AdoptionWritesNothingSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  /** The shell commands that a viewer of a library diagram uses, over a map of
    * files. It records each `library_write` of diagram `a`.
    *
    * The shell is global (`window.__TAURI__`). A `DesktopLibrary` of an earlier
    * spec can have a write that waits, and its timer can fire into this shell
    * (for example `local.json` of `LossyCanvasEditSpec`). That write is not
    * from this spec, so the record does not count it.
    */
  private class FakeShell:
    val files: mutable.Map[String, String] = mutable.Map.empty
    val writes: mutable.Buffer[String]     = mutable.Buffer.empty

    def install(): Unit =
      val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
        val a = args.asInstanceOf[js.Dynamic]
        command match
          case "library_list" =>
            js.Promise.resolve[js.Any](
              js.Array(files.toVector.sortBy(_._1).map((name, json) => js.Dynamic.literal(name = name, json = json))*)
            )
          case "library_write" =>
            val name = a.selectDynamic("name").asInstanceOf[String]
            files(name) = a.selectDynamic("json").asInstanceOf[String]
            if name == "a.json" then writes += name
            js.Promise.resolve[js.Any](())
          case "hash_text" =>
            val text = a.selectDynamic("text").asInstanceOf[String]
            js.Promise.resolve[js.Any](f"${text.hashCode & 0xffffffffL}%08x")
          case other => js.Promise.reject(js.JavaScriptException(s"unexpected command $other"))
      js.Dynamic.global.window.__TAURI__ = js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

    def uninstall(): Unit = js.Dynamic.global.window.__TAURI__ = null

  /** `assert`, with a message that reaches the log. In the Playwright runner a
    * failure arrives with an empty message. The console does reach the log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[AdoptionWritesNothingSpec] $clue")
    assert(condition, clue)

  private def idsOf(ids: ElementIds): Set[ElementId] = ids.ids.toSet

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  private val text = "digraph G {\n  a -> b\n}"

  private def stored(metadata: DiagramMetadata, source: String = text) =
    upickle.default.write(
      Diagram(DiagramId("a"), "DOT", source, binding = None, metadata, createdAt = 1, updatedAt = 1)
    )

  /** Open diagram `a`, select `b`, zoom in, let `gx` write `changed`, and run `verify`.
    *
    * `verify` gets the selection and the viewport from before the change.
    */
  private def whenGxWrites(changed: String)(
      verify: (FakeShell, DesktopLibrary, ViewerState, ElementIds, String) => Future[Unit]
  ): Future[Unit] =
    val shell = FakeShell()
    shell.install()
    shell.files("a.json") = stored(DiagramMetadata(name = "Alpha"))
    val owner = ManualOwner()
    DesktopLibrary
      .load()
      .flatMap: loaded =>
        val library = DesktopLibrary(loaded)
        Library.install(library)
        withGraphvizAsync: graphviz =>
          val state     = ViewerState(ViewTarget.diagram("a"), graphviz)(using owner)
          val viewport  = state.transform.observe(using owner)
          afterMicrotasks:
            check(state.sourceText.now() == text, s"precondition: the viewer shows the source: ${state.sourceText.now()}")
            state.selection.set(ElementIds.from(NodeId("b")))
            state.zoomIn()
            val selectionBefore = state.selection.now()
            val viewportBefore  = viewport.now()
            check(viewportBefore.startsWith("scale(1.1"), s"precondition: the zoom changed: $viewportBefore")
            check(shell.writes.isEmpty, s"precondition: the open wrote nothing: ${shell.writes}")
            shell.files("a.json") = changed
            (selectionBefore, viewportBefore)
          .flatMap: (selectionBefore, viewportBefore) =>
            library
              .refresh()
              .flatMap(_ => afterMicrotasks(()))
              .flatMap(_ => verify(shell, library, state, selectionBefore, viewportBefore))
              .map(_ => check(viewport.now() == viewportBefore, s"the adoption moved the viewport: ${viewport.now()}"))
      .transform: result =>
        owner.killSubscriptions()
        Library.restoreDefault()
        shell.uninstall()
        result

  /** Wait past the write debounce, flush, and require that the shell got no write. */
  private def noWriteFollows(shell: FakeShell, library: DesktopLibrary): Future[Unit] =
    after(600).map: _ =>
      library.flush()
      check(shell.writes.isEmpty, s"the adoption wrote the diagram: ${shell.writes}")

  test("a hide and a rename from gx are adopted, and the adoption writes nothing"):
    val changed = stored(DiagramMetadata(name = "Renamed by gx", hiddenElements = Set("node:a")))
    whenGxWrites(changed): (shell, library, state, selectionBefore, _) =>
      check(state.hiddenElements.now().nodeIds == Set(NodeId("a")), s"the hide was not adopted: ${state.hiddenElements.now()}")
      check(state.metadata.name.now() == "Renamed by gx", s"the rename was not adopted: ${state.metadata.name.now()}")
      check(idsOf(state.selection.now()) == idsOf(selectionBefore), s"the adoption reset the selection: ${state.selection.now()}")
      noWriteFollows(shell, library)

  test("a new source and new metadata from gx are adopted, and the adoption writes nothing"):
    val source  = "digraph G {\n  a -> b -> c\n}"
    val changed = stored(DiagramMetadata(name = "Alpha", collapsedGroups = Set("group:g")), source)
    whenGxWrites(changed): (shell, library, state, selectionBefore, _) =>
      check(state.sourceText.now() == source, s"the source was not adopted: ${state.sourceText.now()}")
      check(state.metadata.now().collapsedGroups == Set("group:g"), s"the fold was not adopted: ${state.metadata.now()}")
      after(100).flatMap: _ =>
        // `b` is in the new source too, so the selection keeps it.
        check(idsOf(state.selection.now()) == idsOf(selectionBefore), s"the adoption reset the selection: ${state.selection.now()}")
        noWriteFollows(shell, library)

  test("control: an edit after the adoption does write"):
    // Proof that the tests above do not pass because nothing can write.
    val changed = stored(DiagramMetadata(name = "Alpha", hiddenElements = Set("node:a")))
    whenGxWrites(changed): (shell, library, state, _, _) =>
      state.hiddenElements.add(Set(NodeId("b")))
      afterMicrotasks:
        library.flush()
        check(shell.writes.toList == List("a.json"), s"the edit was not written: ${shell.writes}")
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        check(saved.metadata.hiddenElements == Set("node:a", "node:b"), s"the edit lost the adopted hide: ${saved.metadata.hiddenElements}")
