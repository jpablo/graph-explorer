package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.laminar.api.L.EventBus
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.{GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A refused canvas edit must not do the view follow-up of an applied edit.
  *
  * `runDocumentCommand` calls `onApplied` inside the update, before the setter
  * of `fullGraphV` decides. For a file-backed document with comments, the
  * setter refuses the edit. Then `combine` must not select a record node that
  * the graph does not have.
  */
class RefusedEditFollowUpSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    OriginReconciler.reset()

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    OriginReconciler.reset()
    js.Dynamic.global.window.__TAURI__ = null

  /** The shell calls a viewer makes while it shows a loose file. Each one
    * succeeds and changes nothing.
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

  /** `assert`, with a message that reaches the log. In the Playwright runner a
    * failure arrives with an empty message. The console does reach the log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[RefusedEditFollowUpSpec] $clue")
    assert(condition, clue)

  private def collect(bus: EventBus[String]): () => List[String] =
    var messages = List.empty[String]
    bus.events.foreach(message => messages = message :: messages)(using com.raquo.laminar.api.L.unsafeWindowOwner)
    () => messages

  private val api = NodeId("api")
  private val db  = NodeId("db")

  /** Open `text` as a loose file, select `api` and `db`, and combine them. */
  private def combineApiAndDb(text: String)(verify: (ViewerState, List[String]) => Unit): Future[Unit] =
    installShell()
    withGraphvizAsync: graphviz =>
      val open     = DesktopDocumentRegistry.record("/tmp/refused-follow-up.dot", "rev-1", text)
      val errors   = EventBus[String]()
      val messages = collect(errors)
      val state    = ViewerState(ViewTarget.LooseFile(open.id), graphviz, errorBus = errors)
      afterMicrotasks:
        check(state.sourceText.now() == text, s"precondition: the viewer shows the document: ${state.sourceText.now()}")
        val nodes = state.phases.fullGraphV.now().nodeIds
        check(Set(api, db).subsetOf(nodes), s"precondition: the graph has node:api and node:db, but has $nodes")
        state.selection.set2(api, db)
        state.selection.combineIntoRecord()
      .flatMap: _ =>
        afterMicrotasks:
          verify(state, messages())

  test("a refused combine does not select a record node that the graph does not have"):
    val withComments = "digraph Deps {\n  // reads\n  api -> db\n  web -> api\n}"
    combineApiAndDb(withComments): (state, messages) =>
      check(state.sourceText.now() == withComments, s"the refused combine replaced the text: ${state.sourceText.now()}")
      check(
        state.selection.now().nodeIds == Set(api, db),
        s"the refused combine changed the selection: ${state.selection.now().nodeIds}"
      )
      check(messages.size == 1, s"one refused edit must send one message: $messages")

  test("control: a combine that applies selects the new record node"):
    // Proof that the test above does not pass because combine does nothing here.
    val plain = "digraph Deps {\n  api -> db\n  web -> api\n}"
    combineApiAndDb(plain): (state, messages) =>
      val selected = state.selection.now().nodeIds
      check(state.sourceText.now() != plain, "the combine did not apply")
      check(selected.size == 1 && !selected.exists(Set(api, db)), s"the new record node is not selected: $selected")
      check(state.phases.fullGraphV.now().nodeIds.contains(selected.head), s"the selection names no node: $selected")
      check(messages.isEmpty, s"an edit that applies sends no message: $messages")

  // ------------------------------------------ the other canvas actions

  private val commentedCluster =
    "digraph G {\n  // note\n  subgraph cluster_s { label=\"S\"; api; db }\n  web -> api\n}"
  private val plainCluster =
    "digraph G {\n  subgraph cluster_s { label=\"S\"; api; db }\n  web -> api\n}"
  private val group = GroupId("s")

  /** Open `text` as a loose file, run `act` after the parse, and then `verify`.
    * `verify` receives the viewer, the error messages and the info messages.
    */
  private def onLooseFile(text: String)(act: ViewerState => Unit)(
      verify: (ViewerState, List[String], List[String]) => Unit
  ): Future[Unit] =
    installShell()
    withGraphvizAsync: graphviz =>
      val open   = DesktopDocumentRegistry.record("/tmp/refused-follow-up.dot", "rev-1", text)
      val errors = EventBus[String]()
      val infos  = EventBus[String]()
      val errorMessages = collect(errors)
      val infoMessages  = collect(infos)
      val state  = ViewerState(ViewTarget.LooseFile(open.id), graphviz, errorBus = errors, infoBus = infos)
      afterMicrotasks:
        check(state.sourceText.now() == text, s"precondition: the viewer shows the document: ${state.sourceText.now()}")
        val nodes = state.phases.fullGraphV.now().nodeIds
        check(Set(api, db).subsetOf(nodes), s"precondition: the graph has node:api and node:db, but has $nodes")
        act(state)
      .flatMap: _ =>
        afterMicrotasks:
          verify(state, errorMessages(), infoMessages())

  /** `window.confirm` answers yes while `body` runs. */
  private def confirmingYes[A](body: => A): A =
    val window   = js.Dynamic.global.window
    val original = window.confirm
    window.confirm = ((_: js.Any) => true): js.Function1[js.Any, Boolean]
    try body
    finally window.confirm = original

  private def foldAndDelete(state: ViewerState): Unit =
    val groups = state.phases.fullGraphV.now().groupIds
    check(groups.contains(group), s"precondition: the graph has $group, but has $groups")
    state.project.collapsedGroups.set(Set(group))
    state.selection.set2(group)
    state.selection.deleteSelection()

  test("a refused delete keeps the selected groups folded"):
    // The fold is metadata of the record. A delete that did not happen must
    // not unfold a group that is still there.
    onLooseFile(commentedCluster)(foldAndDelete): (state, errors, _) =>
      check(state.sourceText.now() == commentedCluster, s"the refused delete replaced the text: ${state.sourceText.now()}")
      check(
        state.project.collapsedGroups.now() == Set(group),
        s"the refused delete unfolded the group: ${state.project.collapsedGroups.now()}"
      )
      check(errors.size == 1, s"one refused edit must send one message: $errors")

  test("control: a delete that applies removes the fold of the deleted group"):
    onLooseFile(plainCluster)(foldAndDelete): (state, errors, _) =>
      check(state.sourceText.now() != plainCluster, "the delete did not apply")
      check(
        state.project.collapsedGroups.now().isEmpty,
        s"the deleted group is still folded: ${state.project.collapsedGroups.now()}"
      )
      check(errors.isEmpty, s"an edit that applies sends no message: $errors")

  private def hideDbAndDeleteHidden(state: ViewerState): Unit =
    state.hiddenElements.add(Set(db))
    confirmingYes(state.deleteHiddenElements())

  test("a refused delete of the hidden elements keeps them hidden and does not report a deletion"):
    val withComments = "digraph G {\n  // note\n  api -> db\n  web -> api\n}"
    onLooseFile(withComments)(hideDbAndDeleteHidden): (state, errors, infos) =>
      check(state.sourceText.now() == withComments, s"the refused delete replaced the text: ${state.sourceText.now()}")
      check(
        state.hiddenElements.now().nodeIds.contains(db),
        s"the refused delete showed the hidden elements again: ${state.hiddenElements.now()}"
      )
      check(!infos.contains("Hidden elements deleted"), s"the viewer reported a deletion that did not happen: $infos")
      check(errors.size == 1, s"one refused edit must send one message: $errors")

  test("control: a delete of the hidden elements that applies clears them and reports it"):
    val plain = "digraph G {\n  api -> db\n  web -> api\n}"
    onLooseFile(plain)(hideDbAndDeleteHidden): (state, errors, infos) =>
      check(!state.phases.fullGraphV.now().nodeIds.contains(db), "the delete did not apply")
      check(state.hiddenElements.now().isEmpty, s"the hidden set is not empty: ${state.hiddenElements.now()}")
      check(infos.contains("Hidden elements deleted"), s"the deletion was not reported: $infos")
      check(errors.isEmpty, s"an edit that applies sends no message: $errors")

  private val commentedPair = "digraph G {\n  // note\n  api -> db\n}"

  private def selectApiAndDuplicate(state: ViewerState): Unit =
    state.selection.set2(api)
    state.selection.duplicateSelection()

  private def selectApiAndAddNode(state: ViewerState): Unit =
    state.selection.set2(api)
    state.addNodeWithSmartConnection()

  test("a refused duplicate keeps the selection"):
    onLooseFile(commentedPair)(selectApiAndDuplicate): (state, errors, _) =>
      check(state.selection.now().nodeIds == Set(api), s"the refused duplicate changed the selection: ${state.selection.now()}")
      check(errors.size == 1, s"one refused edit must send one message: $errors")

  test("a refused new node keeps the selection"):
    onLooseFile(commentedPair)(selectApiAndAddNode): (state, errors, _) =>
      check(state.selection.now().nodeIds == Set(api), s"the refused new node changed the selection: ${state.selection.now()}")
      check(errors.size == 1, s"one refused edit must send one message: $errors")
