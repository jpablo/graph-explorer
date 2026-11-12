package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.library.{BrowserLibrary, LoggedChecks}
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.{read, write}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A visit to a browser diagram with no edit writes nothing, and the restore
  * is complete before the sync and auto-detect see the state.
  *
  * `Viewer` builds the `ViewerState` in the map of the route signal
  * (`child <-- router.currentRoute.map`). Thus the build occurs in a running
  * Airstream transaction. This spec builds the view in the same way.
  */
class VisitWritesNothingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "VisitWritesNothingSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit = stopCounting()

  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def lastModified(id: DiagramId): Option[Long] =
    BrowserLibrary.indexNow().diagrams.find(_.id == id).map(_.lastModified)

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** Wait until `condition` is true, or until the time ends. The future does
    * not fail at the end of the time. The assertions after it tell what is
    * wrong.
    */
  private def settle(condition: => Boolean, timeoutMs: Int = 5000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition || js.Date.now() - start >= timeoutMs then p.trySuccess(())
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** The real `setItem` while a count is on. */
  private var realSetItem: Option[js.Dynamic] = None

  /** Count the calls of `localStorage.setItem` for `key`. Each call still
    * goes to the real `setItem`.
    */
  private def countWrites(key: String): () => Int =
    val storage = dom.window.localStorage.asInstanceOf[js.Dynamic]
    val real    = storage.setItem
    realSetItem = Some(real)
    var count = 0
    storage.setItem = ((k: String, v: String) =>
      if k == key then count += 1
      real.call(storage, k, v)
      ()
    ): js.Function2[String, String, Unit]
    () => count

  private def stopCounting(): Unit =
    realSetItem.foreach(real => dom.window.localStorage.asInstanceOf[js.Dynamic].setItem = real)
    realSetItem = None

  /** Build a view of `id` the way `Viewer` does: in the map of a route signal,
    * thus in the transaction of the route change. After the first parse, run
    * `verify` with the view, the count of the payload writes in the visit,
    * and `lastModified` from before the visit.
    */
  private def visitInTransaction(id: DiagramId)(verify: (ViewerState, () => Int, Option[Long]) => Unit): Future[Unit] =
    val viewOwner  = ManualOwner()
    val routeOwner = ManualOwner()
    // A wait, so a write in the visit gives a different `lastModified`.
    after(20)
      .flatMap: _ =>
        val before = lastModified(id)
        check(before.isDefined, "precondition: the index has no entry for the diagram")
        withGraphvizAsync: graphviz =>
          val route = Var(Option.empty[DiagramId])
          val view =
            route.signal
              .map(_.map(target => ViewerState(ViewTarget.Diagram(target), graphviz)(using viewOwner)))
              .observe(using routeOwner)
          val writes = countWrites(payloadKey(id))
          // The map of the route signal builds the view, in the transaction of this set.
          route.set(Some(id))
          check(view.now().isDefined, "precondition: the route signal did not build the view")
          val state = view.now().get
          settle(state.loadStatus.now() != DiagramLoadStatus.Loading)
            .flatMap(_ => after(100))
            .map(_ => verify(state, writes, before))
      .transform: result =>
        routeOwner.killSubscriptions()
        viewOwner.killSubscriptions()
        result

  test("a visit with no edit does not write the payload or move lastModified"):
    // Related 1. The route signal builds the view in a running transaction.
    // There, each `Var.set` of `restorePersistedState` waits until the
    // transaction ends. Before the fix, `setupStateSynchronization` listened
    // by then, and its `.changes` saw each step of the restore as a change.
    // Thus a visit wrote the payload, and `updateIndex` set `lastModified` to
    // now: the "Last modified" date and the sort order changed. Then this
    // test failed at the first check.
    val id = BrowserLibrary.createDiagram("Visited", "digraph G {\n  a -> b\n}")
    visitInTransaction(id): (_, writes, before) =>
      same(writes(), 0, "the visit wrote the payload")
      same(lastModified(id), before, "the visit moved lastModified")

  test("an edit after a visit writes the payload at once"):
    // Related 1, the behavior to keep. The sync starts after the restore, and
    // then it sees each edit. This test fails when the sync does not start.
    val id = BrowserLibrary.createDiagram("Edited", "digraph G {\n  a -> b\n}")
    visitInTransaction(id): (state, writes, before) =>
      same(writes(), 0, "precondition: the visit wrote the payload")
      state.storedName.set("Renamed")
      same(writes(), 1, "the edit did not write the payload at once")
      same(
        Option(dom.window.localStorage.getItem(payloadKey(id))).map(read[DiagramSnapshot](_).name),
        Some("Renamed"),
        "the payload does not have the edit"
      )
      check(lastModified(id).exists(t => before.exists(t > _)), "the edit did not move lastModified")

  test("auto-detect after a restore in a transaction uses the restored format"):
    // Related 1. Before the fix, the restore called `applyAutoDetectNow` at
    // once. In a running transaction, the restored format and flag were not
    // set yet. Auto-detect then saw the format from `detect`, and it changed
    // nothing. After that, the restore set the stored format. This text
    // declares Mermaid, the stored format is DOT, and auto-detect is on, so
    // the view must show Mermaid, as it does outside a transaction. Before
    // the fix, this test failed: the format was DOT.
    val id = BrowserLibrary.createEmptyDiagram("Declares Mermaid")
    dom.window.localStorage.setItem(
      payloadKey(id),
      write(
        DiagramSnapshot(
          source = "flowchart TD\n  A --> B",
          format = Some(DiagramFormat.DOT.toString),
          metadata = DiagramMetadata(autoDetectFormat = Some(true)),
          name = "Declares Mermaid"
        )
      )
    )
    visitInTransaction(id): (state, writes, _) =>
      same(state.formatSelection.now(), DiagramFormat.Mermaid, "auto-detect did not see the restored format")
      same(writes(), 0, "the visit wrote the payload")
