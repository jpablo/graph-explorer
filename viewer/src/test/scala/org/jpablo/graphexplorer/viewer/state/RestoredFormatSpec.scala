package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.{read, write}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The restore of a diagram whose stored format is not `DiagramFormat.detect(text)`.
  *
  * The viewer starts with the format that `detect` gives, and with a
  * placeholder graph, because the first parse is asynchronous. The restore then
  * sets the stored format. The text must stay the stored text.
  */
class RestoredFormatSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  given Owner = unsafeWindowOwner

  /** The `localStorage` key of the payload of a diagram (see `PersistenceSpec`). */
  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storePayload(id: DiagramId, snapshot: DiagramSnapshot): Unit =
    dom.window.localStorage.setItem(payloadKey(id), write(snapshot))

  private def storedSource(id: DiagramId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id))).map(read[DiagramSnapshot](_).source)

  /** `assert`, with a message that reaches the log. The Playwright runner
    * can give a failure with an empty message, but the console reaches the
    * log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[RestoredFormatSpec] $clue")
    assert(condition, clue)

  /** `assertEquals`, with the same log message as [[check]]. */
  private def same[A](obtained: A, expected: A, clue: String)(using CanEqual[A, A]): Unit =
    check(obtained == expected, s"$clue\n  obtained: $obtained\n  expected: $expected")

  /** Wait until `condition` is true, or until the time ends. The future does
    * not fail at the end of the time. The assertions that come after it tell
    * what is wrong.
    */
  private def settle(condition: => Boolean, timeoutMs: Int = 5000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition || js.Date.now() - start >= timeoutMs then p.trySuccess(())
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** Open a viewer on a stored diagram, and check the text and the payload.
    *
    * After the parse, the test renames the diagram. A rename does not change
    * the text, but the sync then writes the whole snapshot, text included.
    * Thus the payload shows the text that the next change of the person saves.
    */
  private def restoreKeepsText(id: DiagramId, stored: DiagramSnapshot): Future[Unit] =
    val storedFormat = DiagramFormat.valueOf(stored.format.get)
    check(
      DiagramFormat.detect(stored.source) != storedFormat,
      "precondition: the stored format is the format that detect gives"
    )
    storePayload(id, stored)
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.Diagram(id), graphviz)
      same(state.formatSelection.now(), storedFormat, "precondition: the restore did not set the stored format")
      settle(state.loadStatus.now() != DiagramLoadStatus.Loading)
        .map: _ =>
          same(state.sourceText.now(), stored.source, "the restore replaced the stored text with an empty template")
          state.storedName.set("Renamed")
          same(storedSource(id), Some(stored.source), "the sync wrote a template over the stored payload")

  test("a restore keeps a DOT diagram that the person set to Mermaid by hand"):
    // Finding 2. The person picked Mermaid by hand for a DOT diagram with
    // content, so auto-detect is off. On a reopen, `detect` gives DOT, and the
    // restore sets Mermaid. The format observer sees the placeholder graph
    // with `graphInSync = true`, takes the empty-diagram branch, and replaces
    // the text with the empty Mermaid template.
    restoreKeepsText(
      DiagramId("restore-dot-as-mermaid"),
      DiagramSnapshot(
        source = "digraph G {\n  a -> b\n}",
        format = Some(DiagramFormat.Mermaid.toString),
        metadata = DiagramMetadata(autoDetectFormat = Some(false)),
        name = "Picked by hand"
      )
    )

  test("a restore keeps a Mermaid diagram that has no header line"):
    // Finding 2. The person deleted the header line of a Mermaid diagram.
    // Auto-detect kept Mermaid, because the text declares nothing. On a
    // reload, `detect` falls back to DOT, and the restore sets Mermaid. The
    // format observer then replaces the text with the empty Mermaid template.
    restoreKeepsText(
      DiagramId("restore-mermaid-no-header"),
      DiagramSnapshot(
        source = "  A --> B\n  B --> C",
        format = Some(DiagramFormat.Mermaid.toString),
        metadata = DiagramMetadata(autoDetectFormat = Some(true)),
        name = "No header"
      )
    )
