package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.SourceCommand
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{SaveResult, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A save from the desktop keeps the line endings and the end of the file
  * (V-04).
  *
  * A canvas edit prints the whole graph again, with LF and with no final
  * newline. Before, a Save wrote that text as it was. Thus one change to one
  * attribute changed each line of a CRLF file, and removed the final newline:
  * a diff, and a tool that watches the file, saw changes that nobody made.
  * `gx` follows the rule through `Documents.write`. The desktop now follows
  * the same rule (`LineEnding`, shared), in `FilePersistence.saveNow`.
  */
class SaveLineEndingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "SaveLineEndingSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private val path = "/w/arch.dot"

  private def colorApi(state: ViewerState, color: String): Unit =
    state.runSourceCommand(SourceCommand.SetAttribute(Set[ElementId](NodeId("api")), "color", color))

  /** Open `text` as the file at `path`, make a canvas edit, and Save. */
  private def editAndSave(text: String)(body: (FakeDesktopShell, ViewerState) => Future[Unit]): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.install()
    shell.files(path) = text
    val open = DesktopDocumentRegistry.record(path, shell.hash(text), text)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      afterMicrotasks:
        check(
          state.phases.fullGraphV.now().nodeIds.contains(NodeId("api")),
          s"precondition: the file parsed and has node:api, but has ${state.phases.fullGraphV.now().nodeIds}"
        )
        colorApi(state, "red")
        check(state.sourceText.now().contains("red"), s"precondition: the canvas edit changed the text: ${state.sourceText.now()}")
      .flatMap(_ => state.save())
      .flatMap: result =>
        same(result, SaveResult.Saved, "the save did not succeed")
        body(shell, state)
      .transform: outcome =>
        shell.uninstall()
        outcome

  private def written(shell: FakeDesktopShell): String = shell.files.getOrElse(path, "")

  private def lineEndings(text: String): (Int, Int) =
    val crlf = "\r\n".r.findAllMatchIn(text).size
    (crlf, text.count(_ == '\n') - crlf)

  test("a CRLF file stays CRLF after a canvas edit and a Save"):
    editAndSave("digraph G {\r\n  api\r\n  db\r\n  api -> db\r\n}\r\n"): (shell, _) =>
      val text = written(shell)
      check(text.contains("red"), s"the save did not write the edit: $text")
      val (crlf, lf) = lineEndings(text)
      check(crlf > 0 && lf == 0, s"the save changed the line endings: $crlf CRLF and $lf LF in ${js.JSON.stringify(text)}")
      check(text.endsWith("\r\n"), s"the save removed the final CRLF: ${js.JSON.stringify(text)}")
      Future.unit

  test("a file with a final newline keeps it"):
    editAndSave("digraph G {\n  api\n}\n"): (shell, _) =>
      val text = written(shell)
      check(text.endsWith("}\n") && !text.endsWith("\n\n"), s"the save changed the final newline: ${js.JSON.stringify(text)}")
      same(lineEndings(text)._1, 0, "the save put CRLF in an LF file")
      Future.unit

  test("a file with no final newline stays without one"):
    editAndSave("digraph G {\n  api\n}"): (shell, _) =>
      val text = written(shell)
      check(!text.endsWith("\n"), s"the save added a final newline: ${js.JSON.stringify(text)}")
      Future.unit

  test("after a save of a CRLF file, the view is clean, and a second edit and Save keep CRLF"):
    editAndSave("digraph G {\r\n  api\r\n}\r\n"): (shell, state) =>
      same(state.documentIsDirty, false, "the view is dirty after a save, only because of the line endings")
      same(
        DesktopDocumentRegistry.find(path).flatMap(_.conflict),
        None,
        "the save of the view made a conflict with the file that it wrote"
      )
      same(
        DesktopDocumentRegistry.find(path).map(_.sourceText),
        Some(written(shell)),
        "the session does not hold the text that is in the file"
      )
      colorApi(state, "blue")
      state.save().map: result =>
        same(result, SaveResult.Saved, "the second save did not succeed")
        val text       = written(shell)
        val (crlf, lf) = lineEndings(text)
        check(text.contains("blue"), s"the second save did not write the edit: $text")
        check(crlf > 0 && lf == 0 && text.endsWith("\r\n"), s"the second save changed the line endings: ${js.JSON.stringify(text)}")
