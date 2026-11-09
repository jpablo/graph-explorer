package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.{DiagramId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The metadata of a file on the screen: it loads from the entry of the file,
  * each change goes to the entry, and an outside change to the entry shows
  * (ADR 0001, plan §5 phase 3a).
  *
  * A different writer, for example `gx hide <file> a`, can change the entry
  * while the file is open. Before, the viewer adopted only the source. The
  * change did not show, and the viewer kept its old hidden set. The next edit
  * in the viewer then wrote that old set back, and the change from `gx` was
  * lost with no message.
  *
  * The source is different: it saves only with an explicit Save.
  */
class FollowMetadataSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "FollowMetadataSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private val path = "/w/a.dot"
  private val text = "digraph G {\n  a -> b\n}"

  /** Open `path` with an entry of `metadata`, and run `body` on the viewer. */
  private def onOpenFile(metadata: DiagramMetadata = DiagramMetadata(name = "a"), inLibrary: Boolean = true)(
      body: (FakeDesktopShell, DesktopLibrary, ViewerState) => Future[Unit]
  ): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.install()
    shell.files(path) = text
    if inLibrary then shell.putEntry(LibraryEntry(path, metadata, createdAt = 1, updatedAt = 1))
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        val opened =
          if inLibrary then library.open(DiagramId(shell.keyOf(path))).map(_ => ())
          else Future.successful(DesktopDocumentRegistry.record(path, shell.hash(text), text)).map(_ => ())
        opened.flatMap: _ =>
          val session = DesktopDocumentRegistry.find(path).getOrElse(fail("no session"))
          withGraphvizAsync: graphviz =>
            val state = ViewerState(ViewTarget.File(session.id), graphviz)
            afterMicrotasks(check(state.sourceText.now() == text, s"precondition: the viewer shows the file: ${state.sourceText.now()}"))
              .flatMap(_ => body(shell, library, state))
      .transform: result =>
        Library.restoreDefault()
        shell.uninstall()
        result

  /** `gx` changes the entry of `path`, from a different process. */
  private def gxChanges(shell: FakeDesktopShell, library: DesktopLibrary)(change: DiagramMetadata => DiagramMetadata): Future[Unit] =
    val current = shell.entryOf(path).getOrElse(fail("precondition: the file has an entry"))
    shell.putEntry(current.copy(metadata = change(current.metadata), updatedAt = 2))
    library.refresh().flatMap(_ => afterMicrotasks(()))

  private def writesOf(shell: FakeDesktopShell) = shell.callsOf("library_write").size

  // ------------------------------------------------------------- load

  test("the metadata of a file loads from its entry"):
    onOpenFile(DiagramMetadata(name = "a", hiddenElements = Set("node:a"), collapsedGroups = Set("group:g"))): (shell, _, state) =>
      Future:
        same(state.hiddenElements.now().nodeIds, Set(NodeId("a")), "the hidden elements of the entry")
        same(state.metadata.now().collapsedGroups, Set("group:g"), "the collapsed groups of the entry")
        same(writesOf(shell), 0, "an open must write nothing")

  // ----------------------------------------------------------- follow

  test("a hide from gx shows in an open viewer of the file"):
    onOpenFile(): (shell, library, state) =>
      gxChanges(shell, library)(_.copy(hiddenElements = Set("node:a"))).map: _ =>
        same(state.hiddenElements.now().nodeIds, Set(NodeId("a")), "the viewer does not show the hide from gx")

  test("a hide from gx survives the next edit of the source in the viewer"):
    onOpenFile(): (shell, library, state) =>
      gxChanges(shell, library)(_.copy(hiddenElements = Set("node:a"))).flatMap: _ =>
        state.replaceSourceDetectingFormat("digraph G {\n  a -> b -> c\n}")
        afterMicrotasks:
          same(shell.entryOf(path).map(_.metadata.hiddenElements), Some(Set("node:a")), "the edit in the viewer reverted the hide from gx")
          same(shell.files.get(path), Some(text), "an edit of the source must not reach the file before a Save")

  test("a tag from gx survives the next hide in the viewer"):
    onOpenFile(): (shell, library, state) =>
      gxChanges(shell, library)(_.copy(tags = List("infra"))).flatMap: _ =>
        state.hiddenElements.add(Set(NodeId("b")))
        afterMicrotasks:
          val saved = shell.entryOf(path).getOrElse(fail("no entry"))
          same(saved.metadata.hiddenElements, Set("node:b"), "the hide of the viewer was not saved")
          same(saved.metadata.tags, List("infra"), "the hide of the viewer erased the tag from gx")

  test("control: a hide in the viewer with no change from gx writes the entry"):
    // Proof that the tests above do not pass because the viewer writes nothing.
    onOpenFile(): (shell, _, state) =>
      state.hiddenElements.add(Set(NodeId("b")))
      afterMicrotasks:
        same(writesOf(shell), 1, "one hide gives one write")
        same(shell.entryOf(path).map(_.metadata.hiddenElements), Some(Set("node:b")))

  // ------------------------------------------------- a file with no entry

  test("a file with no entry gets one on its first change, and an open alone writes nothing"):
    onOpenFile(inLibrary = false): (shell, library, state) =>
      afterMicrotasks(same(writesOf(shell), 0, "an open of a file must not put it in the library"))
        .flatMap: _ =>
          state.hiddenElements.add(Set(NodeId("b")))
          // The key of the path comes from the shell, so the write is async.
          afterMicrotasks(()).flatMap(_ => afterMicrotasks(()))
        .map: _ =>
          same(shell.entryOf(path).map(_.metadata.hiddenElements), Some(Set("node:b")), "the first change did not make the entry")
          check(library.diagramExists(DiagramId(shell.keyOf(path))), "the file is not in the library after its first change")

  // ------------------------------------------------------------- rename

  test("a rename in the viewer moves the file and its metadata, and the next hide goes to the new entry"):
    onOpenFile(DiagramMetadata(name = "a", hiddenElements = Set("node:a"))): (shell, _, state) =>
      val title = state.displayTitle.observe(using state.owner)
      state
        .renameFile("b")
        .flatMap(_ => afterMicrotasks(()))
        .map: _ =>
          same(title.now(), "b", "the title does not follow the new file name")
          same(state.hiddenElements.now().nodeIds, Set(NodeId("a")), "the rename lost the metadata on the screen")
          state.hiddenElements.add(Set(NodeId("b")))
        .flatMap(_ => afterMicrotasks(()))
        .map: _ =>
          same(shell.entryOf("/w/b.dot").map(_.metadata.hiddenElements), Some(Set("node:a", "node:b")))
          same(shell.entryOf(path), None, "the old entry is still there")

  test("a rename in the viewer to a name that exists is refused, and the person sees why"):
    onOpenFile(): (shell, _, state) =>
      shell.files("/w/b.dot") = "digraph { b }"
      val errors = scala.collection.mutable.Buffer.empty[String]
      state.errorBus.events.foreach(errors += _)(using state.owner)
      state.renameFile("b").map: _ =>
        check(errors.exists(_.contains("exists")), s"no refusal for the person: $errors")
        same(shell.files.get(path), Some(text))
        same(shell.files.get("/w/b.dot"), Some("digraph { b }"))

  // ------------------------------------------------------------- source

  test("the source of a file saves only with an explicit Save"):
    onOpenFile(): (shell, _, state) =>
      state.replaceSourceDetectingFormat("digraph G {\n  a -> c\n}")
      afterMicrotasks(())
        .flatMap: _ =>
          same(shell.callsOf("save_document").size, 0, "an edit wrote the file")
          state.save()
        .map: result =>
          same(result, SaveResult.Saved)
          same(shell.files.get(path), Some("digraph G {\n  a -> c\n}"), "Save did not write the file")
