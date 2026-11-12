package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.library.{BrowserLibrary, LoggedChecks}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.read

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The text of a share link (`?dot=`) and the text of a stored diagram.
  *
  * A share link has the text of the diagram at the time of the share. The
  * route keeps that text, but `buildPath` drops it from the URL. Thus the
  * browser history keeps the share URL as the previous entry. When the owner
  * presses Back, the route has the old text again, and a new viewer starts on
  * the stored diagram. The stored text must stay, and the sync must not write
  * the old text over it.
  *
  * A link to a diagram that is not in the library yet is different. The
  * library has no text for it, so the text of the link is the start text.
  */
class ShareLinkSourceSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ShareLinkSourceSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val storedText = "digraph G {\n  a -> b -> c\n}"
  private val linkText   = "digraph G {\n  a -> b\n}"

  /** The `localStorage` key of the payload of a diagram (see `PersistenceSpec`). */
  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storedSource(id: DiagramId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id))).map(read[DiagramSnapshot](_).source)

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

  /** Open a viewer on `id` with the text of a link, as the route gives it.
    * After the parse, rename the diagram. A rename does not change the text,
    * but the sync then writes the whole snapshot, with the text.
    */
  private def openWithLink(id: DiagramId)(verify: (ViewerState, () => Unit) => Unit): Future[Unit] =
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Diagram(id), graphviz, initialSource = Some(linkText))(using owner)
      settle(state.loadStatus.now() != DiagramLoadStatus.Loading).map: _ =>
        verify(state, () => state.storedName.set("Renamed"))
    .transform: result =>
      owner.killSubscriptions()
      result

  test("a share link does not replace the text of a stored diagram"):
    // Finding 6. The owner opens the own link /diagrams/<id>?dot=OLD, edits
    // the diagram, and presses Back. The route has the text of the link
    // again. Before the fix, `ViewerState` put `initialSource` over the
    // stored text. Thus the viewer showed OLD, and the next change wrote OLD
    // over the stored text. Then this test failed at the first check.
    val id = BrowserLibrary.createDiagram("Mine", storedText)
    openWithLink(id): (state, change) =>
      same(state.sourceText.now(), storedText, "the viewer shows the text of the link, not the stored text")
      change()
      same(storedSource(id), Some(storedText), "the sync wrote the text of the link over the stored text")

  test("a share link to a diagram with no payload starts the diagram with the text of the link"):
    // Finding 6, the behavior to keep. For a link to a diagram that is not in
    // the library, `startApp` makes an index entry with no payload. It then
    // goes to /diagrams/<new id> with the text of the link. The library has
    // no text for that diagram, so the viewer starts with the text of the
    // link, and the first change stores that text. This test also passed
    // before the fix.
    val id = BrowserLibrary.createEmptyDiagram(DiagramSnapshot.defaultDiagramName)
    check(storedSource(id).isEmpty, "precondition: the new diagram has a payload")
    openWithLink(id): (state, change) =>
      same(state.sourceText.now(), linkText, "the new diagram does not start with the text of the link")
      change()
      same(storedSource(id), Some(linkText), "the first change did not store the text of the link")
