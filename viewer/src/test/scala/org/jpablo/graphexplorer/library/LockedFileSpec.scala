package org.jpablo.graphexplorer.library

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.{DiagramId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{InternalPhases, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The library entry of a file holds the lock and the selected extractor
  * (ADR 0002).
  *
  * A locked file gets no write from the app: not from the canvas, and not
  * from a save. The lock and the extractor go to the entry at once, as each
  * other field of the metadata does, and a change from `gx` shows in an open
  * view.
  *
  * `FileSyncSpec` has the case of a locked file that a different tool writes.
  */
class LockedFileSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LockedFileSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private val path = "/w/a.dot"
  private val text = "digraph G {\n  a -> b\n}"

  private def waitFor(description: => String, timeoutMs: Int = 10000)(condition: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms. Expected: $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** One open view of `path`, with the messages that it gives. */
  private final class Open(val shell: FakeDesktopShell, val library: DesktopLibrary, val state: ViewerState):
    val errors = mutable.Buffer.empty[String]
    state.errorBus.events.foreach(errors += _)(using state.owner)

    def entry: Option[LibraryEntry] = shell.entryOf(path)

    def parsed: Future[Unit] = waitFor("the parse of the file")(state.fullGraphNow().nodeIds.nonEmpty)

    /** `gx` changes the entry of `path`, from a different process. */
    def gxChanges(change: DiagramMetadata => DiagramMetadata): Future[Unit] =
      val current = entry.getOrElse(fail("precondition: the file has an entry"))
      shell.putEntry(current.copy(metadata = change(current.metadata), updatedAt = 2))
      library.refresh().flatMap(_ => afterMicrotasks(()))

  /** Open `path` with an entry of `metadata`, and run `body` on the view. The
    * view has its own owner, and the end of the test kills it.
    */
  private def onOpenFile(metadata: DiagramMetadata = DiagramMetadata())(body: Open => Future[Unit]): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.install()
    shell.files(path) = text
    shell.putEntry(LibraryEntry(path, metadata, createdAt = 1, updatedAt = 1))
    val owner = ManualOwner()
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        library
          .open(DiagramId(shell.keyOf(path)))
          .flatMap: _ =>
            val session = DesktopDocumentRegistry.find(path).getOrElse(fail("no session"))
            withGraphvizAsync: graphviz =>
              val state = ViewerState(ViewTarget.File(session.id), graphviz)(using owner)
              afterMicrotasks(check(state.sourceText.now() == text, s"precondition: the view shows the file: ${state.sourceText.now()}"))
                .flatMap(_ => body(Open(shell, library, state)))
      .transform: result =>
        owner.killSubscriptions()
        Library.restoreDefault()
        shell.uninstall()
        result

  // --------------------------------------------------------------- the lock

  test("the lock of an entry loads with the file, and a canvas edit changes nothing"):
    onOpenFile(DiagramMetadata(locked = true)): open =>
      open.parsed.flatMap: _ =>
        open.state.selection.set2(NodeId("b"))
        open.state.addNodeWithSmartConnection()
        afterMicrotasks:
          same(open.state.sourceText.now(), text, "the canvas edit changed the text of a locked file")
          same(open.errors.toList, List(InternalPhases.lockedCanvasEdit))
          same(open.state.documentIsDirty, false, "a refused edit made the document dirty")

  test("a lock in the view goes to the entry at once"):
    onOpenFile(): open =>
      open.state.metadata.locked.set(true)
      afterMicrotasks(same(open.entry.map(_.metadata.locked), Some(true), "the entry does not hold the lock"))
        .flatMap: _ =>
          open.state.metadata.locked.set(false)
          afterMicrotasks(same(open.entry.map(_.metadata.locked), Some(false), "the entry still holds the lock"))

  test("a lock from gx shows in an open view, and the view then refuses a canvas edit"):
    onOpenFile(): open =>
      open.parsed
        .flatMap(_ => open.gxChanges(_.copy(locked = true)))
        .flatMap: _ =>
          same(open.state.metadata.now().locked, true, "the view does not show the lock from gx")
          open.state.selection.set2(NodeId("b"))
          open.state.addNodeWithSmartConnection()
          afterMicrotasks:
            same(open.state.sourceText.now(), text)
            same(open.errors.toList, List(InternalPhases.lockedCanvasEdit))

  test("a save of a locked file writes nothing, and the person sees why"):
    onOpenFile(): open =>
      val edited = "digraph G {\n  a -> c\n}"
      // The edit comes first. Then `gx lock` locks the file.
      open.state.replaceSourceDetectingFormat(edited)
      open
        .gxChanges(_.copy(locked = true))
        .flatMap(_ => open.state.save())
        .flatMap: result =>
          check(
            result match
              case SaveResult.Failed(message) => message.contains("locked")
              case _                          => false
            ,
            s"the save did not fail with a message about the lock: $result"
          )
          same(open.shell.files.get(path), Some(text), "the save wrote a locked file")
          same(open.shell.callsOf("save_document").size, 0, "the save went to the shell")
          // After the unlock, the same save writes the edit.
          open.state.metadata.locked.set(false)
          open.state.save()
        .map: result =>
          same(result, SaveResult.Saved)
          same(open.shell.files.get(path), Some(edited))

  test("a hide on a locked file goes to the entry"):
    onOpenFile(DiagramMetadata(locked = true)): open =>
      open.state.hiddenElements.add(Set(NodeId("a")))
      afterMicrotasks:
        same(open.entry.map(_.metadata.hiddenElements), Some(Set("node:a")))
        same(open.entry.map(_.metadata.locked), Some(true), "the hide removed the lock")
        same(open.shell.files.get(path), Some(text))

  // ---------------------------------------------------------- the extractor

  test("a language that the person selects goes to the entry as the extractor"):
    onOpenFile(): open =>
      open.state.setDiagramFormat(DiagramFormat.Mermaid)
      afterMicrotasks:
        same(open.entry.map(_.metadata.extractor), Some(Some("mermaid")))
        same(open.entry.map(_.metadata.autoDetectFormat), Some(Some(false)))
        same(open.shell.files.get(path), Some(text), "the selection of an extractor wrote the file")

  // Before, the entry had no extractor. A `.dot` file that the person set to
  // Mermaid opened as DOT again, with auto-detect off.
  test("a file opens with the extractor of its entry, not with the extractor of its extension"):
    onOpenFile(DiagramMetadata(autoDetectFormat = Some(false), extractor = Some("mermaid"))): open =>
      afterMicrotasks:
        same(open.state.formatSelection.now(), DiagramFormat.Mermaid)
        same(open.state.autoDetectFormat.now(), false)
        same(open.shell.callsOf("library_write").size, 0, "an open wrote the entry")

  test("an extractor that this build does not have counts as no selection, and it stays in the entry"):
    onOpenFile(DiagramMetadata(autoDetectFormat = Some(false), extractor = Some("an-extractor-of-a-later-build"))): open =>
      open.state.hiddenElements.add(Set(NodeId("a")))
      afterMicrotasks:
        same(open.state.formatSelection.now(), DiagramFormat.DOT)
        same(open.entry.map(_.metadata.extractor), Some(Some("an-extractor-of-a-later-build")), "a write of the view removed the extractor")

  test("an extractor that a different writer selects shows in an open view"):
    onOpenFile(): open =>
      open
        .gxChanges(_.copy(autoDetectFormat = Some(false), extractor = Some("mermaid")))
        .map: _ =>
          same(open.state.formatSelection.now(), DiagramFormat.Mermaid)

  test("Auto removes the extractor from the entry"):
    onOpenFile(DiagramMetadata(autoDetectFormat = Some(false), extractor = Some("dot"))): open =>
      open.state.formatOption.select(open.state.formatOption.auto)
      afterMicrotasks:
        same(open.entry.map(_.metadata.extractor), Some(None))
        same(open.entry.map(_.metadata.autoDetectFormat), Some(Some(true)))
