package org.jpablo.graphexplorer.library

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{
  DiagramLoadStatus,
  DiagramSnapshot,
  LibraryPersistence,
  SaveResult,
  ViewTarget,
  ViewerState
}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.read

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A write of a payload that `localStorage` refuses (the quota is full).
  *
  * Before the fix, the browser library wrote through laminext `StoredString`.
  * It calls `localStorage.setItem` in an Airstream `scanLeft`, and the state
  * of that fold is a `Try`. After one QuotaExceededError, the fold stayed
  * failed: `currentValue.get` threw first, so no later `set` called
  * `setItem`. That was also true after the person freed space. A reload or a
  * navigation then lost each edit after the first failure.
  */
class StorageQuotaSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "StorageQuotaSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit = restoreSetItem()

  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storedSource(id: DiagramId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id))).map(read[DiagramSnapshot](_).source)

  /** Replace `localStorage.setItem`. For `key`, the replacement throws a
    * QuotaExceededError `times` times. After that, and for each other key, it
    * calls the real `setItem`.
    */
  private val installQuotaStub: js.Function2[String, Int, Unit] =
    js.eval(
      """(function (key, times) {
        |  var storage = window.localStorage;
        |  if (!storage.__realSetItem) storage.__realSetItem = storage.setItem;
        |  var real = storage.__realSetItem;
        |  var left = times;
        |  storage.setItem = function (k, v) {
        |    if (k === key && left > 0) {
        |      left--;
        |      throw new DOMException("The quota has been exceeded.", "QuotaExceededError");
        |    }
        |    return real.call(storage, k, v);
        |  };
        |})""".stripMargin
    ).asInstanceOf[js.Function2[String, Int, Unit]]

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

  private def lastModified(id: DiagramId): Option[Long] =
    BrowserLibrary.indexNow().diagrams.find(_.id == id).map(_.lastModified)

  private def failPayloadWrites(id: DiagramId, times: Int): Unit =
    installQuotaStub(payloadKey(id), times)

  /** Put the real `setItem` back. The mock storage lives for the whole run. */
  private def restoreSetItem(): Unit =
    val storage = dom.window.localStorage.asInstanceOf[js.Dynamic]
    if !js.isUndefined(storage.__realSetItem) then
      storage.setItem = storage.__realSetItem
      storage.__realSetItem = js.undefined

  test("a change after one refused write of the payload reaches storage"):
    // Finding 8. The first change meets a full quota, and `setItem` throws one
    // time. Then the person frees space, and `setItem` works again. Before
    // the fix, the fold of laminext stayed failed, so the second change did
    // not reach storage. Then this test failed at the second check.
    val id          = BrowserLibrary.createDiagram("Quota", "digraph G { a }")
    val persistence = LibraryPersistence(id, None)
    failPayloadWrites(id, times = 1)
    persistence.update(persistence.initial.copy(source = "digraph G { a -> b }"))
    same(storedSource(id), Some("digraph G { a }"), "precondition: the stub did not refuse the first write")
    persistence.update(persistence.initial.copy(source = "digraph G { a -> b -> c }"))
    same(
      storedSource(id),
      Some("digraph G { a -> b -> c }"),
      "the change after the refused write did not reach storage"
    )

  test("a save that storage refuses does not report Saved"):
    // Finding 8. Before the fix, `LibraryPersistence.saveNow` gave `Saved`,
    // and it did not look at the write. When storage refused the payload, the
    // person saw a successful save, and a reload lost the edit. Then this
    // test failed at the check of the result.
    val id          = BrowserLibrary.createDiagram("Quota", "digraph G { a }")
    val persistence = LibraryPersistence(id, None)
    failPayloadWrites(id, times = 1)
    persistence
      .saveNow(persistence.initial.copy(source = "digraph G { a -> b }"))
      .map: result =>
        check(
          result != SaveResult.Saved,
          s"the save reported Saved, but the payload has ${storedSource(id)}"
        )

  test("a save with no new change writes a refused diagram again, and reports Saved when the write succeeds"):
    // Finding 8. After a refused write, the person frees space and presses
    // ⌘S. The state has no new change, so the usual sync writes nothing. The
    // save must write again. Before the fix, this test failed: the payload
    // kept the old text.
    val id          = BrowserLibrary.createDiagram("Quota", "digraph G { a }")
    val persistence = LibraryPersistence(id, None)
    val edited      = persistence.initial.copy(source = "digraph G { a -> b }")
    failPayloadWrites(id, times = 1)
    persistence.update(edited)
    same(storedSource(id), Some("digraph G { a }"), "precondition: the stub did not refuse the write")
    persistence
      .saveNow(edited)
      .map: result =>
        same(result, SaveResult.Saved, "the save after the person freed space did not report Saved")
        same(storedSource(id), Some("digraph G { a -> b }"), "the save did not write the diagram again")

  test("a refused write of the payload does not move lastModified"):
    // Finding 8. Before the fix, `updateIndex` moved `lastModified` also when
    // the storage refused the payload. Then "Last modified" showed a change
    // that the storage did not have, and this test failed.
    val id          = BrowserLibrary.createDiagram("Quota", "digraph G { a }")
    val persistence = LibraryPersistence(id, None)
    val before      = lastModified(id)
    // A wait, so a write gives a different `lastModified`.
    after(20).map: _ =>
      failPayloadWrites(id, times = 1)
      persistence.update(persistence.initial.copy(source = "digraph G { a -> b }"))
      same(lastModified(id), before, "the refused write moved lastModified")

  test("a refused write of the payload goes to the alert of the view"):
    // Finding 8. The person must learn that the storage refused a change. The
    // view gives the message to its `errorBus`, and `ErrorAlert` shows it.
    // Before the fix, this test failed: the view got no message.
    val id       = BrowserLibrary.createDiagram("Quota", "digraph G {\n  a -> b\n}")
    val errors   = EventBus[String]()
    val owner    = ManualOwner()
    val messages = mutable.Buffer.empty[String]
    errors.events.foreach(messages += _)(using owner)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Diagram(id), graphviz, errorBus = errors)(using owner)
      settle(state.loadStatus.now() != DiagramLoadStatus.Loading).map: _ =>
        failPayloadWrites(id, times = 1)
        state.storedName.set("Renamed")
        check(
          messages.exists(_.contains("storage is full")),
          s"the view got no message for the refused write: $messages"
        )
    .transform: result =>
      owner.killSubscriptions()
      result
