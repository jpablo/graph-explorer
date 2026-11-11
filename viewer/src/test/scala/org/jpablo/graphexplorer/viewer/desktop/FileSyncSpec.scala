package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.library.{DesktopLibrary, FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.components.{DocumentConflictBanner, UnsavedChangesDialog}
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry.{Conflict, Session}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The sync of a desktop file with its view (§7.3: base, local and remote).
  *
  * `base` is the revision and the text of the session. `local` is the text of
  * the editor. `remote` is the file on disk. The shell here is the fake shell:
  * its `save_document` compares `baseRevision` with the hash of the file, as
  * the real shell does, and it refuses a stale base with `DOCUMENT_CONFLICT`.
  * A test sends each document event through `DesktopBridge`, as the real
  * event goes.
  */
class FileSyncSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "FileSyncSpec"

  override def munitFixtures = List(mockStorageFixture())

  private var shell = FakeDesktopShell()

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    shell = FakeDesktopShell()
    shell.install()

  override def afterEach(context: AfterEach): Unit =
    shell.uninstall()
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** Put `text` in the file at `path`, and record the session as the shell
    * does when it opens the file.
    */
  private def openFile(path: String, text: String): Session =
    shell.files(path) = text
    DesktopDocumentRegistry.record(path, shell.hash(text), text)

  /** The event that the shell sends for the file at `path` with `text`. */
  private def documentEvent(path: String, text: String): DesktopBridge.DesktopMessage =
    DesktopBridge.DesktopMessage(text, Some(path), Some(shell.hash(text)))

  /** A different writer changes the file, and the watch loop reports it. */
  private def writeOutside(path: String, text: String): Unit =
    shell.files(path) = text
    DesktopBridge.routeDocumentChange(documentEvent(path, text))

  private def sessionOf(open: Session): Option[Session] = DesktopDocumentRegistry.get(open.id)

  private def conflictOf(open: Session): Option[Conflict] = sessionOf(open).flatMap(_.conflict)

  /** Open `text` as the file at `path` in a view, and run `body` after the
    * view starts. The view has its own owner, and the end of the test kills it.
    */
  private def withFile(path: String, text: String)(body: (Session, ViewerState) => Future[Unit]): Future[Unit] =
    val open  = openFile(path, text)
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      afterMicrotasks(())
        .flatMap(_ => body(open, state))
        .transform: outcome =>
          owner.killSubscriptions()
          outcome

  private val base   = "digraph G {\n  a\n}\n"
  private val mine   = "digraph G {\n  a\n  mine\n}\n"
  private val theirs = "digraph G {\n  a\n  theirs\n}\n"
  private val later  = "digraph G {\n  a\n  later\n}\n"

  // ---------------------------------------------------------------------------
  // Finding 1: `record` moves the base of a session in conflict.
  // ---------------------------------------------------------------------------

  // Finding 1. `record` sets the revision, the text and `conflict = None` on
  // each document event, before the follower marks the conflict. Thus a
  // session in conflict holds the revision of the outside write as its base.
  // Failure: the base revision is the hash of `theirs`, not the hash of `base`.
  test("the base of a session in conflict stays the revision that the edit started from"):
    val path = "/w/base-stays.dot"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      writeOutside(path, theirs)
      same(conflictOf(open).map(_.text), Some(theirs), "precondition: the outside write did not make a conflict")
      same(sessionOf(open).map(_.revision), Some(shell.hash(base)), "the base moved to the revision of the outside write")
      same(sessionOf(open).map(_.sourceText), Some(base), "the base moved to the text of the outside write")
      Future.unit

  // Finding 1. The save sends the base of the session as `baseRevision`. That
  // base is the revision of the outside write, so the check of the shell
  // passes, and the save writes the edit over the outside change.
  // Failure: the save comes back as `Saved`, and the file holds `mine`.
  test("a save in conflict comes back as a conflict, and the file keeps the outside change"):
    val path = "/w/save-in-conflict.dot"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      writeOutside(path, theirs)
      check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
      state.save().map: result =>
        check(result.isInstanceOf[SaveResult.Conflict], s"the save did not come back as a conflict: $result")
        same(shell.files.get(path), Some(theirs), "the save wrote the edit over the outside change")

  // Finding 1. A second document event for a session in conflict goes
  // through `record`, and `record` sets `conflict = None`. The banner goes
  // away until the follower marks the conflict again, and the base moves
  // again. A fix that keeps the base in `record` and still clears the
  // conflict loses the outside change with no message.
  // Failure: the conflict signal shows `None` during the second event.
  test("a second outside write in conflict keeps the conflict, with the newest file, and keeps the base"):
    val path = "/w/second-write.dot"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      writeOutside(path, theirs)
      check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
      val seen    = mutable.Buffer.empty[Option[Conflict]]
      val watcher = ManualOwner()
      state.documentConflict.changes.foreach(seen.append)(using watcher)
      writeOutside(path, later)
      watcher.killSubscriptions()
      check(!seen.contains(None), s"the second document event cleared the conflict: $seen")
      same(conflictOf(open).map(_.text), Some(later), "the conflict does not hold the newest file")
      same(sessionOf(open).map(_.revision), Some(shell.hash(base)), "the second document event moved the base")
      same(state.sourceText.now(), mine, "the second document event replaced the edit")
      Future.unit

  // ---------------------------------------------------------------------------
  // Finding 13: the event of a save of this view arrives before the reply.
  // ---------------------------------------------------------------------------

  // Finding 13. The shell sends the event of a save before the reply. The
  // follower does not know about the save of its own view. A key typed
  // between ⌘S and the event gives "editor moved, file moved, editor does not
  // hold the file", so the follower marks a conflict with the saved text.
  // "Load the file" then deletes the keys typed during the save.
  // Failure: the session has a conflict with the text that the save wrote.
  test("a key typed during a save is not a conflict when the event of the save arrives"):
    val path       = "/w/typed-during-save.dot"
    val saved      = "digraph G {\n  a\n  b\n}\n"
    val typedAfter = "digraph G {\n  a\n  b\n  c\n}\n"
    withFile(path, base): (open, state) =>
      state.sourceText.set(saved)
      val saving = state.save()
      // The person types one more key while the save is in flight.
      state.sourceText.set(typedAfter)
      // The shell wrote the file, and it sends the event of the save BEFORE
      // its reply. The reply comes in a later microtask.
      val written = shell.files(path)
      same(written, saved, "precondition: the shell did not write the saved text")
      DesktopBridge.routeDocumentChange(documentEvent(path, written))
      saving.map: result =>
        same(result, SaveResult.Saved, "precondition: the save did not succeed")
        same(conflictOf(open), None, "the event of the save of this view made a conflict")
        same(sessionOf(open).map(_.revision), Some(shell.hash(written)), "the base is not the revision of the save")
        same(state.sourceText.now(), typedAfter, "the editor lost the key typed during the save")
        same(state.documentIsDirty, true, "the key typed during the save does not show as unsaved")

  // Finding 13, the reply. The event of the save comes before the reply, and
  // a write of a different tool can come between the two. The reply must not
  // move the base back to the save: that undoes the newer file.
  // Failure, if the reply always moves the base: the base is the save again.
  test("the reply of a save does not undo a newer outside write"):
    val path = "/w/reply-after-write.dot"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      val saving = state.save()
      // The event of the save, and then a write of a different tool.
      DesktopBridge.routeDocumentChange(documentEvent(path, shell.files(path)))
      writeOutside(path, theirs)
      saving.map: result =>
        same(result, SaveResult.Saved, "precondition: the save did not succeed")
        same(sessionOf(open).map(_.revision), Some(shell.hash(theirs)), "the reply moved the base back to the save")
        same(state.sourceText.now(), theirs, "the clean editor did not take the newer file")
        same(conflictOf(open), None, "the newer file made a conflict")

  // ---------------------------------------------------------------------------
  // Finding 14: a re-adoption sets the language from `detect`.
  // ---------------------------------------------------------------------------

  /** A Mermaid flowchart with no direction. `detect` does not know this
    * header, and it reads DOT.
    */
  private val noDirection = "graph\n  A --> B\n"
  private val noDirectionLater = "graph\n  A --> B\n  B --> C\n"

  /** Open the flowchart as a file with no known extension, and pick Mermaid
    * by hand. The pick turns auto-detect off.
    */
  private def withPickedMermaid(path: String)(body: (Session, ViewerState) => Future[Unit]): Future[Unit] =
    withFile(path, noDirection): (open, state) =>
      same(state.formatSelection.now(), DiagramFormat.DOT, "precondition: the file did not open as DOT")
      state.setDiagramFormat(DiagramFormat.Mermaid)
      same(state.autoDetectFormat.now(), false, "precondition: the pick did not turn auto-detect off")
      same(state.sourceText.now(), noDirection, "precondition: the pick changed the text")
      same(state.documentIsDirty, false, "precondition: the pick made the editor dirty")
      body(open, state)

  // Finding 14. A clean editor adopts an outside change through
  // `replaceSourceDetectingFormat`. That call uses `DiagramFormat.detect`,
  // which reads DOT here, and it ignores auto-detect off.
  // Failure: the language is DOT after the adoption.
  test("a language picked by hand stays when a clean editor adopts an outside change"):
    val path = "/w/picked-outside.txt"
    withPickedMermaid(path): (_, state) =>
      writeOutside(path, noDirectionLater)
      same(state.sourceText.now(), noDirectionLater, "precondition: the clean editor did not adopt the file")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "the adoption replaced the language that the person picked")
      Future.unit

  // Finding 14. "Load the file" adopts the file through
  // `replaceSourceDetectingFormat`, with the same result.
  // Failure: the language is DOT after "Load the file".
  test("a language picked by hand stays after Load the file"):
    val path = "/w/picked-load.txt"
    withPickedMermaid(path): (open, state) =>
      state.sourceText.set("graph\n  A --> C\n")
      writeOutside(path, noDirectionLater)
      check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "precondition: the edit changed the language")
      state.resolveDocumentConflict(adoptRemoteText = true)
      same(state.sourceText.now(), noDirectionLater, "precondition: Load the file did not load the file")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "Load the file replaced the language that the person picked")
      Future.unit

  // Finding 14. A rename moves the session (`DesktopDocumentRegistry.move`),
  // and the session emits. The clean editor then adopts the same text again
  // through `replaceSourceDetectingFormat`.
  // Failure: the language is DOT after the rename.
  test("a language picked by hand stays after a rename of the file"):
    withPickedMermaid("/w/picked-rename.txt"): (open, state) =>
      DesktopDocumentRegistry.move(open.id, "/w/picked-renamed.txt")
      same(sessionOf(open).map(_.path), Some("/w/picked-renamed.txt"), "precondition: the session did not move")
      same(state.sourceText.now(), noDirection, "precondition: the rename changed the text")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "the rename replaced the language that the person picked")
      Future.unit

  // Finding 14. `FilePersistence.initial` has `format = None`, so the view
  // takes `DiagramFormat.detect`, which falls back to DOT. The extension of
  // the file says Mermaid, and the view does not read it.
  // Failure: the `.mmd` file opens as DOT.
  test("a .mmd file whose text declares no language opens as Mermaid"):
    val text = "  A --> B\n  B --> C\n"
    check(DiagramFormat.declared(text).isEmpty, "precondition: the text declares a language")
    withFile("/w/no-header.mmd", text): (_, state) =>
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "the .mmd file opened as DOT")
      Future.unit

  // Finding 14, with auto-detect on. The new text declares no language, so
  // the language stays the language now. `detect` falls back to DOT.
  // Failure, if the adoption uses `detect`: the language is DOT.
  test("with auto-detect on, an outside change that declares no language keeps the language now"):
    val path = "/w/auto-no-header.mmd"
    withFile(path, "  A --> B\n"): (_, state) =>
      same(state.autoDetectFormat.now(), true, "precondition: auto-detect is off")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "precondition: the .mmd file did not open as Mermaid")
      writeOutside(path, "  A --> B\n  B --> C\n")
      same(state.sourceText.now(), "  A --> B\n  B --> C\n", "precondition: the clean editor did not adopt the file")
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "the adoption fell back to DOT")
      Future.unit

  // ---------------------------------------------------------------------------
  // Finding 15: a 0-byte file opens with a template.
  // ---------------------------------------------------------------------------

  // Finding 15. `ViewerState` turns an empty source into
  // `initialSource = None`, so a 0-byte file opens as `digraph "G" {}` while
  // the session holds "". The view is dirty at once: a leave asks "Save this
  // file?", and Save writes a DOT template into the file.
  // Failure: the editor holds `digraph "G" {}`.
  test("a 0-byte file opens clean, with an empty editor"):
    withFile("/w/empty.dot", ""): (_, state) =>
      same(state.sourceText.now(), "", "the empty file opened with a template in the editor")
      same(state.documentIsDirty, false, "the empty file is dirty as it opens")
      Future.unit

  // Finding 15. The editor holds the template, and the follower reads the
  // template as an edit. Thus the first outside write to the empty file is a
  // conflict, and not an adoption.
  // Failure: the editor keeps the template, and the session has a conflict.
  test("a clean view of a 0-byte file adopts an outside write"):
    val path = "/w/empty-then-written.dot"
    withFile(path, ""): (open, state) =>
      writeOutside(path, base)
      same(state.sourceText.now(), base, "the view of the empty file did not adopt the outside write")
      same(conflictOf(open), None, "the outside write to the empty file made a conflict")
      Future.unit

  // ---------------------------------------------------------------------------
  // Related: `fileMoved` compares the texts exactly.
  // ---------------------------------------------------------------------------

  // Related bug. `editorMoved` and `holds` use `LineEnding.sameOnDisk`, but
  // `fileMoved` uses an exact `!=`. An outside rewrite that changes only the
  // line endings, while the editor holds an edit, makes a conflict for a
  // change that is not a change.
  // Failure: the session has a conflict with the CRLF text.
  test("an outside rewrite that changes only the line endings is not a conflict for a dirty editor"):
    val path = "/w/crlf-rewrite.dot"
    val crlf = "digraph G {\r\n  a\r\n}\r\n"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      writeOutside(path, crlf)
      same(conflictOf(open), None, "a change of the line endings made a conflict")
      same(state.sourceText.now(), mine, "the change of the line endings replaced the edit")
      state.save().map: result =>
        same(result, SaveResult.Saved, "the save after a change of the line endings did not succeed")
        check(shell.files(path).contains("mine"), s"the save did not write the edit: ${shell.files(path)}")

  // Related bug. The same exact `!=`, for a rewrite that adds only the final
  // newline.
  // Failure: the session has a conflict with the text that has the newline.
  test("an outside rewrite that changes only the final newline is not a conflict for a dirty editor"):
    val path      = "/w/final-newline.dot"
    val noNewline = "digraph G {\n  a\n}"
    withFile(path, noNewline): (open, state) =>
      state.sourceText.set("digraph G {\n  a\n  mine\n}")
      writeOutside(path, noNewline + "\n")
      same(conflictOf(open), None, "a change of the final newline made a conflict")
      same(state.sourceText.now(), "digraph G {\n  a\n  mine\n}", "the change of the final newline replaced the edit")
      Future.unit

  // ---------------------------------------------------------------------------
  // Related: Discard in the leave dialog keeps the conflict.
  // ---------------------------------------------------------------------------

  // Related bug. Discard drops the edit and leaves the view, but the session
  // keeps its conflict. A back or a forward to the same session then shows
  // the banner "Your edit is still here", and there is no edit.
  // Failure: the banner of the second view shows "Your edit is still here".
  test("Discard in the leave dialog ends the conflict, so a return to the file shows no banner"):
    val path      = "/w/discard.dot"
    val startHref = dom.window.location.href
    val cleanups  = mutable.Buffer.empty[() => Unit]
    def mount(): dom.Element =
      val box = dom.document.createElement("div")
      dom.document.body.appendChild(box)
      cleanups += (() => box.parentNode.removeChild(box))
      box
    val open = openFile(path, base)
    withGraphvizAsync: graphviz =>
      val firstOwner = ManualOwner()
      cleanups += (() => firstOwner.killSubscriptions())
      val first      = ViewerState(ViewTarget.File(open.id), graphviz)(using firstOwner)
      val dialogBox  = mount()
      val dialogRoot = render(dialogBox, UnsavedChangesDialog(first, Router()))
      cleanups += (() => dialogRoot.unmount())
      afterMicrotasks(())
        .flatMap: _ =>
          first.sourceText.set(mine)
          writeOutside(path, theirs)
          check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
          first.pendingLeave.set(Some(LeaveIntent.Navigate(Route.Home)))
          after(50)
        .flatMap: _ =>
          val discard = dialogBox.querySelectorAll("button").toSeq.find(_.textContent.trim == "Discard")
          check(discard.isDefined, s"the dialog has no Discard button: ${dialogBox.innerHTML}")
          discard.foreach(_.asInstanceOf[dom.html.Button].click())
          same(first.pendingLeave.now(), None, "precondition: Discard did not answer the dialog")
          // Discard itself takes the file. It does not wait for the view to go.
          same(conflictOf(open), None, "Discard left the conflict in the session")
          same(first.sourceText.now(), theirs, "Discard did not put the file in the editor")
          // The first view goes away: an unmount kills its owner.
          firstOwner.killSubscriptions()
          // A back or a forward to the same session makes a new view.
          val secondOwner = ManualOwner()
          cleanups += (() => secondOwner.killSubscriptions())
          val second     = ViewerState(ViewTarget.File(open.id), graphviz)(using secondOwner)
          val bannerBox  = mount()
          val bannerRoot = render(bannerBox, DocumentConflictBanner(second))
          cleanups += (() => bannerRoot.unmount())
          after(50).map: _ =>
            val banner = bannerBox.textContent
            check(!banner.contains("Your edit is still here"), s"the return to the file shows the banner, with no edit: $banner")
            same(conflictOf(open), None, "Discard left the conflict in the session")
            same(second.sourceText.now(), theirs, "the return to the file does not show the file")
            same(second.documentIsDirty, false, "the return to the file shows an edit that Discard dropped")
        .transform: outcome =>
          cleanups.reverseIterator.foreach(cleanup => cleanup())
          dom.window.history.replaceState(null, "", startHref)
          outcome

  // ---------------------------------------------------------------------------
  // A session that no view follows has no false conflict.
  // ---------------------------------------------------------------------------

  // No view holds an edit while the file changes, so the file becomes the
  // base at once. A view that opens after the change shows the file.
  test("a view that opens after an outside change shows the file, with no banner"):
    val path = "/w/no-view.dot"
    val open = openFile(path, base)
    writeOutside(path, theirs)
    same(conflictOf(open), None, "a session with no view has a conflict")
    same(sessionOf(open).map(_.revision), Some(shell.hash(theirs)), "a session with no view did not take the file")
    withFile(path, theirs): (_, state) =>
      same(state.sourceText.now(), theirs, "the view does not show the file")
      same(state.documentIsDirty, false, "the view is dirty as it opens")
      Future.unit

  // The edit was in the view. When the last view of the session goes away
  // with no answer to the banner, the edit goes too, so the conflict is
  // false. Before, the session kept it, and a return to the file showed the
  // banner "Your edit is still here" with no edit.
  // Failure before the fix: the session keeps the conflict.
  test("when the last view of a session in conflict goes away, the file becomes the base"):
    val path = "/w/view-goes.dot"
    val open = openFile(path, base)
    withGraphvizAsync: graphviz =>
      val owner = ManualOwner()
      val state = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      afterMicrotasks:
        state.sourceText.set(mine)
        writeOutside(path, theirs)
        check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
        owner.killSubscriptions()
        same(conflictOf(open), None, "the session kept the conflict of a view that went away")
        same(sessionOf(open).map(_.revision), Some(shell.hash(theirs)), "the file did not become the base")

  // Rule 6: a reopen from the library records the file again
  // (`DesktopLibrary.openPath`). For a view that holds an edit, that is one
  // more version of the file. Before, `record` cleared the conflict and moved
  // the base to the file.
  // Failure before the fix: the base is the revision of the file.
  test("a reopen from the library keeps the conflict of a view that holds an edit, and keeps the base"):
    val path = "/w/reopen.dot"
    withFile(path, base): (open, state) =>
      state.sourceText.set(mine)
      writeOutside(path, theirs)
      check(conflictOf(open).isDefined, "precondition: the outside write did not make a conflict")
      shell.putEntry(LibraryEntry(path, DiagramMetadata(), 1, 1))
      DesktopLibrary
        .load()
        .flatMap(_.open(DiagramId(shell.keyOf(path))))
        .map: result =>
          same(result, Right(Route.File(open.id.value)), "precondition: the reopen did not give the route of the session")
          same(conflictOf(open).map(_.text), Some(theirs), "the reopen dropped the conflict")
          same(sessionOf(open).map(_.revision), Some(shell.hash(base)), "the reopen moved the base")
          same(state.sourceText.now(), mine, "the reopen replaced the edit")
