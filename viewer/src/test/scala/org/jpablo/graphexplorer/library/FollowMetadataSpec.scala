package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{DiagramId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** An open viewer follows the METADATA of its diagram, not only the source.
  *
  * A different writer, for example `gx run <diagram> hide`, can change the
  * metadata while the diagram is open. Before this spec, the viewer adopted only
  * the source. The change did not show, and the viewer kept its old hidden set.
  * The next edit in the viewer then wrote that old set back, and the change from
  * `gx` was lost with no message.
  */
class FollowMetadataSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  /** The shell commands that a viewer of a library diagram uses, over a map of files. */
  private class FakeShell:
    val files: mutable.Map[String, String] = mutable.Map.empty

    def install(): Unit =
      val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
        val a = args.asInstanceOf[js.Dynamic]
        command match
          case "library_list" =>
            js.Promise.resolve[js.Any](
              js.Array(files.toVector.sortBy(_._1).map((name, json) => js.Dynamic.literal(name = name, json = json))*)
            )
          case "library_write" =>
            files(a.selectDynamic("name").asInstanceOf[String]) = a.selectDynamic("json").asInstanceOf[String]
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
    if !condition then println(s"[FollowMetadataSpec] $clue")
    assert(condition, clue)

  private val text = "digraph G {\n  a -> b\n}"

  private def stored(metadata: DiagramMetadata, source: String = text) =
    upickle.default.write(
      Diagram(DiagramId("a"), "DOT", source, binding = None, metadata, createdAt = 1, updatedAt = 1)
    )

  /** Open diagram `a`, let `gx` change its metadata to `changed`, and run `verify`. */
  private def whenGxChangesMetadata(changed: DiagramMetadata)(
      verify: (FakeShell, DesktopLibrary, ViewerState) => Future[Unit]
  ): Future[Unit] =
    val shell = FakeShell()
    shell.install()
    shell.files("a.json") = stored(DiagramMetadata(name = "Alpha"))
    DesktopLibrary
      .load()
      .flatMap: loaded =>
        val library = DesktopLibrary(loaded)
        Library.install(library)
        withGraphvizAsync: graphviz =>
          val state = ViewerState(ViewTarget.diagram("a"), graphviz)
          afterMicrotasks:
            check(state.sourceText.now() == text, s"precondition: the viewer shows the source: ${state.sourceText.now()}")
            check(state.hiddenElements.now().isEmpty, s"precondition: nothing is hidden: ${state.hiddenElements.now()}")
            // `gx run a hide --params '{"targets":["node:a"]}'`, from a different process.
            shell.files("a.json") = stored(changed)
          .flatMap(_ => library.refresh())
            .flatMap(_ => afterMicrotasks(()))
            .flatMap(_ => verify(shell, library, state))
      .transform: result =>
        Library.restoreDefault()
        shell.uninstall()
        result

  test("a hide from gx shows in an open viewer"):
    whenGxChangesMetadata(DiagramMetadata(name = "Alpha", hiddenElements = Set("node:a"))): (_, _, state) =>
      Future:
        check(
          state.hiddenElements.now().nodeIds == Set(NodeId("a")),
          s"the viewer does not show the hide from gx: ${state.hiddenElements.now()}"
        )

  test("a hide from gx survives the next edit in the viewer"):
    whenGxChangesMetadata(DiagramMetadata(name = "Alpha", hiddenElements = Set("node:a"))): (shell, library, state) =>
      state.replaceSourceDetectingFormat("digraph G {\n  a -> b -> c\n}")
      afterMicrotasks:
        library.flush()
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        check(saved.text.contains("-> c"), s"precondition: the edit in the viewer was saved: ${saved.text}")
        check(
          saved.metadata.hiddenElements == Set("node:a"),
          s"the edit in the viewer reverted the hide from gx: ${saved.metadata.hiddenElements}"
        )

  test("a rename from gx shows in an open viewer and survives the next edit"):
    whenGxChangesMetadata(DiagramMetadata(name = "Renamed by gx")): (shell, library, state) =>
      state.replaceSourceDetectingFormat("digraph G {\n  a -> b -> c\n}")
      afterMicrotasks:
        library.flush()
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        check(saved.text.contains("-> c"), s"precondition: the edit in the viewer was saved: ${saved.text}")
        check(saved.metadata.name == "Renamed by gx", s"the edit in the viewer reverted the rename: ${saved.metadata.name}")

  test("control: an edit in the viewer with no change from gx keeps the metadata"):
    // Proof that the tests above do not pass because the viewer writes nothing.
    whenGxChangesMetadata(DiagramMetadata(name = "Alpha")): (shell, library, state) =>
      state.hiddenElements.add(Set(NodeId("b")))
      afterMicrotasks:
        library.flush()
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        check(saved.metadata.hiddenElements == Set("node:b"), s"the viewer's own hide was not saved: ${saved.metadata.hiddenElements}")
        check(saved.metadata.name == "Alpha", s"the name changed: ${saved.metadata.name}")
