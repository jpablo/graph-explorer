package org.jpablo.graphexplorer.library

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.{DiagramId, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** An open viewer of a file adopts a change to the entry of the file from a
  * different writer, and the adoption is not an edit.
  *
  * After the adoption, the metadata of the viewer is the metadata of the
  * entry. Thus the "a visit is not an edit" guard of
  * `DesktopLibrary.writeMetadata` must skip the write. A write here moves
  * `updatedAt`, and it can overwrite a second change from `gx`. The adoption
  * also must not reset what the person has on the screen: the selection and
  * the viewport.
  */
class AdoptionWritesNothingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "AdoptionWritesNothingSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def idsOf(ids: ElementIds): Set[ElementId] = ids.ids.toSet

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  private val path = "/w/a.dot"
  private val text = "digraph G {\n  a -> b\n}"

  /** Open `path`, select `b`, zoom in, let `gx` change the entry, and run
    * `verify`. `verify` gets the selection and the viewport from before the
    * change.
    */
  private def whenGxWrites(change: DiagramMetadata => DiagramMetadata)(
      verify: (FakeDesktopShell, ViewerState, ElementIds) => Future[Unit]
  ): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.install()
    shell.files(path) = text
    shell.putEntry(LibraryEntry(path, DiagramMetadata(name = "a"), createdAt = 1, updatedAt = 1))
    val owner = ManualOwner()
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        library.open(DiagramId(shell.keyOf(path))).flatMap: _ =>
          val session = DesktopDocumentRegistry.find(path).getOrElse(fail("no session"))
          withGraphvizAsync: graphviz =>
            val state    = ViewerState(ViewTarget.File(session.id), graphviz)(using owner)
            val viewport = state.transform.observe(using owner)
            afterMicrotasks:
              check(state.sourceText.now() == text, s"precondition: the viewer shows the file: ${state.sourceText.now()}")
              state.selection.set(ElementIds.from(NodeId("b")))
              state.zoomIn()
              val selectionBefore = state.selection.now()
              val viewportBefore  = viewport.now()
              check(viewportBefore.startsWith("scale(1.1"), s"precondition: the zoom changed: $viewportBefore")
              same(shell.callsOf("library_write").size, 0, "precondition: the open wrote nothing")
              val current = shell.entryOf(path).getOrElse(fail("no entry"))
              shell.putEntry(current.copy(metadata = change(current.metadata), updatedAt = 2))
              (selectionBefore, viewportBefore)
            .flatMap: (selectionBefore, viewportBefore) =>
              library
                .refresh()
                .flatMap(_ => afterMicrotasks(()))
                .flatMap(_ => verify(shell, state, selectionBefore))
                .map(_ => same(viewport.now(), viewportBefore, "the adoption moved the viewport"))
      .transform: result =>
        owner.killSubscriptions()
        Library.restoreDefault()
        shell.uninstall()
        result

  /** Wait, and require that the shell got no write. */
  private def noWriteFollows(shell: FakeDesktopShell): Future[Unit] =
    after(100).map(_ => same(shell.callsOf("library_write").size, 0, "the adoption wrote the entry"))

  test("a hide from gx is adopted, and the adoption writes nothing"):
    whenGxWrites(_.copy(hiddenElements = Set("node:a"))): (shell, state, selectionBefore) =>
      same(state.hiddenElements.now().nodeIds, Set(NodeId("a")), "the hide was not adopted")
      same(idsOf(state.selection.now()), idsOf(selectionBefore), "the adoption reset the selection")
      noWriteFollows(shell)

  test("a fold and a tag from gx are adopted, and the adoption writes nothing"):
    whenGxWrites(_.copy(collapsedGroups = Set("group:g"), tags = List("infra"))): (shell, state, selectionBefore) =>
      same(state.metadata.now().collapsedGroups, Set("group:g"), "the fold was not adopted")
      same(state.metadata.now().tags, List("infra"), "the tag was not adopted")
      same(idsOf(state.selection.now()), idsOf(selectionBefore), "the adoption reset the selection")
      noWriteFollows(shell)

  test("control: an edit after the adoption does write, and keeps the adopted change"):
    // Proof that the tests above do not pass because nothing can write.
    whenGxWrites(_.copy(hiddenElements = Set("node:a"))): (shell, state, _) =>
      state.hiddenElements.add(Set(NodeId("b")))
      afterMicrotasks:
        same(shell.callsOf("library_write").size, 1, "the edit was not written")
        same(shell.entryOf(path).map(_.metadata.hiddenElements), Some(Set("node:a", "node:b")), "the edit lost the adopted hide")
