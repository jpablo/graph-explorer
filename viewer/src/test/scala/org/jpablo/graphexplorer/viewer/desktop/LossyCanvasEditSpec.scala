package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.laminar.api.L.EventBus
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.DocumentCommand
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.projects.{DesktopLibrary, Library}
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A canvas edit must not drop content from a file-backed document.
  *
  * A canvas edit (a toolbar attribute, group, combine, delete) prints the WHOLE
  * graph again, and the new text replaces the old one. The graph does not model
  * comments, the DOT `strict` keyword, or Mermaid directives and `click` lines,
  * so the new text does not have them. For a bound record, `gx sync` then pushes
  * that text over the correct file, because the record is `Ahead` and `Ahead`
  * looks settled. For a loose file, the next Save writes it.
  *
  * The decision: for a FILE-BACKED document (a bound library record or a loose
  * file), refuse the edit, keep the text, and tell the person what the edit
  * would drop. A record with no file behind it keeps the old behaviour.
  *
  * Every test first asserts that the graph parsed. Without that, a refused edit
  * and an edit that found no `node:api` look the same, and the test would pass
  * for the wrong reason.
  */
class LossyCanvasEditSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    OriginReconciler.reset()

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    OriginReconciler.reset()
    js.Dynamic.global.window.__TAURI__ = null

  /** The shell calls a viewer makes while it shows a record or a file. Each one
    * succeeds and changes nothing, because the tests read the library and the
    * viewer, not the shell.
    */
  private def installShell(): Unit =
    val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
      val a = args.asInstanceOf[js.Dynamic]
      command match
        case "hash_text" =>
          val text = a.selectDynamic("text").asInstanceOf[String]
          js.Promise.resolve[js.Any](f"${text.hashCode & 0xffffffffL}%08x")
        case "open_document" =>
          js.Promise.resolve[js.Any](js.Dynamic.literal(path = a.selectDynamic("path"), revision = "seed", text = ""))
        case "library_write" | "library_list" => js.Promise.resolve[js.Any](js.Array())
        case other                            => js.Promise.reject(js.JavaScriptException(s"unexpected command $other"))
    js.Dynamic.global.window.__TAURI__ = js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

  private val originPath = "/tmp/lossy-origin.dot"

  private def record(id: String, text: String, bound: Boolean): Diagram =
    Diagram(
      id = DiagramId(id),
      name = id,
      folder = FolderPath.root,
      format = "DOT",
      text = text,
      binding =
        Option.when(bound)(
          Binding(
            OriginUri.parse(s"file://$originPath").fold(e => fail(e), identity),
            SyncMode.Sync,
            ContentHash.fromHex("00"),
            lastSyncAt = 0L
          )
        ),
      metadata = DiagramMetadata(),
      createdAt = 1,
      updatedAt = 1
    )

  private def withLibrary(records: Diagram*)(f: DesktopLibrary => Future[Unit]): Future[Unit] =
    installShell()
    val library = DesktopLibrary(records.toVector)
    Library.install(library)
    f(library)

  /** What the error alert would show, newest first. */
  private def collect(bus: EventBus[String]): () => List[String] =
    var messages = List.empty[String]
    bus.events.foreach(message => messages = message :: messages)(using com.raquo.laminar.api.L.unsafeWindowOwner)
    () => messages

  private def colorApiRed(state: ViewerState): Unit =
    state.runDocumentCommand(DocumentCommand.SetAttribute(Set[ElementId](NodeId("api")), "color", "red"))

  /** `assert`, with a message that reaches the log. In the Playwright runner a
    * failure arrives with an empty message, so a failure does not say which
    * check failed. The console does reach the log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[LossyCanvasEditSpec] $clue")
    assert(condition, clue)

  /** `assertEquals`, with the same log message as [[check]]. */
  private def same[A](obtained: A, expected: A, clue: String = "the values differ")(using CanEqual[A, A]): Unit =
    check(obtained == expected, s"$clue\n  obtained: $obtained\n  expected: $expected")

  private def assertParsed(state: ViewerState, text: String): Unit =
    same(state.sourceText.now(), text, "precondition: the viewer shows the document")
    check(
      state.phases.fullGraphV.now().nodeIds.contains(NodeId("api")),
      s"precondition: the graph parsed and has node:api, but has ${state.phases.fullGraphV.now().nodeIds}"
    )

  private def waitFor(condition: => Boolean, description: => String, timeoutMs: Int = 5000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        val message = s"Timed out after ${timeoutMs}ms waiting for $description"
        println(s"[LossyCanvasEditSpec] $message")
        p.tryFailure(new RuntimeException(message))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  private val withComments =
    """// generated by hand
      |digraph Deps {
      |  /* the API layer */
      |  api -> db  // reads
      |  web -> api
      |}""".stripMargin

  // ------------------------------------------------ bound library records

  test("a canvas edit on a bound record keeps its comments"):
    withLibrary(record("deps", withComments, bound = true)): library =>
      withGraphvizAsync: graphviz =>
        val errors   = EventBus[String]()
        val messages = collect(errors)
        val state    = ViewerState(ViewTarget.library("deps"), graphviz, errorBus = errors)
        afterMicrotasks:
          assertParsed(state, withComments)
          colorApiRed(state)
        .flatMap: _ =>
          afterMicrotasks:
            library.flush()
            same(state.sourceText.now(), withComments, "the canvas edit replaced the text and dropped its comments")
            same(library.recordsBoundTo(originPath).head.text, withComments, "the record lost its comments")
            check(
              messages().exists(_.toLowerCase.contains("comment")),
              s"the refusal does not name the comments: ${messages()}"
            )

  test("a canvas edit on a bound record keeps `strict`"):
    val strict = "strict digraph S {\n  api -> db\n  api -> db\n}"
    withLibrary(record("strict", strict, bound = true)): library =>
      withGraphvizAsync: graphviz =>
        val errors   = EventBus[String]()
        val messages = collect(errors)
        val state    = ViewerState(ViewTarget.library("strict"), graphviz, errorBus = errors)
        afterMicrotasks:
          assertParsed(state, strict)
          colorApiRed(state)
        .flatMap: _ =>
          afterMicrotasks:
            library.flush()
            same(state.sourceText.now(), strict, "the canvas edit dropped `strict`")
            same(library.recordsBoundTo(originPath).head.text, strict)
            check(messages().exists(_.toLowerCase.contains("strict")), s"the refusal does not name `strict`: ${messages()}")

  test("a canvas edit on a bound record with nothing to drop still applies"):
    // No false alarm: a document the printer can reproduce edits as before.
    val plain = "digraph Deps {\n  api -> db\n}"
    withLibrary(record("plain", plain, bound = true)): library =>
      withGraphvizAsync: graphviz =>
        val errors   = EventBus[String]()
        val messages = collect(errors)
        val state    = ViewerState(ViewTarget.library("plain"), graphviz, errorBus = errors)
        afterMicrotasks:
          assertParsed(state, plain)
          colorApiRed(state)
        .flatMap: _ =>
          afterMicrotasks:
            library.flush()
            check(state.sourceText.now().contains("color=\"red\""), s"the edit did not apply: ${state.sourceText.now()}")
            check(library.recordsBoundTo(originPath).head.text.contains("color=\"red\""), "the edit did not reach the record")
            same(messages(), Nil)

  // ---------------------------------------------------------- loose files

  test("a canvas edit on a loose file keeps its comments"):
    installShell()
    withGraphvizAsync: graphviz =>
      val open     = DesktopDocumentRegistry.record("/tmp/lossy-loose.dot", "rev-1", withComments)
      val errors   = EventBus[String]()
      val messages = collect(errors)
      val state    = ViewerState(ViewTarget.LooseFile(open.id), graphviz, errorBus = errors)
      afterMicrotasks:
        assertParsed(state, withComments)
        colorApiRed(state)
      .flatMap: _ =>
        afterMicrotasks:
          same(state.sourceText.now(), withComments, "the canvas edit replaced the text and dropped its comments")
          same(state.documentIsDirty, false, "a refused edit left an edit for Save to write")
          check(
            messages().exists(_.toLowerCase.contains("comment")),
            s"the refusal does not name the comments: ${messages()}"
          )

  test("a canvas edit on a Mermaid loose file keeps its directive and its click line"):
    val mermaid =
      """%%{init: {"theme": "dark"}}%%
        |flowchart LR
        |  api[API] --> db[(DB)]
        |  click api "https://example.com" "Open"""".stripMargin
    installShell()
    withGraphvizAsync: graphviz =>
      val open     = DesktopDocumentRegistry.record("/tmp/lossy-loose.mmd", "rev-1", mermaid)
      val errors   = EventBus[String]()
      val messages = collect(errors)
      val state    = ViewerState(ViewTarget.LooseFile(open.id), graphviz, errorBus = errors)
      waitFor(state.loadStatus.now() == DiagramLoadStatus.Ready, "the Mermaid parse")
        .map: _ =>
          same(state.formatSelection.now(), DiagramFormat.Mermaid, "precondition: the document is Mermaid")
          assertParsed(state, mermaid)
          colorApiRed(state)
        .flatMap: _ =>
          afterMicrotasks:
            same(state.sourceText.now(), mermaid, "the canvas edit dropped the directive or the click line")
            val message = messages().mkString(" | ").toLowerCase
            check(message.contains("directive"), s"the refusal does not name the directive: ${messages()}")
            check(message.contains("click"), s"the refusal does not name the click line: ${messages()}")

  // ------------------------------------------------------ out of scope

  test("a canvas edit on a record with no file behind it still rewrites the text"):
    // The scope of the decision: no file sits behind this record, so nothing
    // on disk can take the loss. It keeps the old behaviour.
    withLibrary(record("local", withComments, bound = false)): _ =>
      withGraphvizAsync: graphviz =>
        val errors   = EventBus[String]()
        val messages = collect(errors)
        val state    = ViewerState(ViewTarget.library("local"), graphviz, errorBus = errors)
        afterMicrotasks:
          assertParsed(state, withComments)
          colorApiRed(state)
        .flatMap: _ =>
          afterMicrotasks:
            check(state.sourceText.now().contains("color=\"red\""), s"the edit did not apply: ${state.sourceText.now()}")
            same(messages(), Nil)
