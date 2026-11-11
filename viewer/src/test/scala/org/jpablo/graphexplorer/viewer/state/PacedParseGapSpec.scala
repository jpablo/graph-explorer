package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Val
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The gap between a keystroke and its paced parse.
  *
  * A keystroke sets the text at once, but the parse of that text waits for
  * `EditorPacing` (60-900 ms). These tests hold the paced parse with
  * [[HeldPace]], so they can act in the gap with no real timing.
  */
class PacedParseGapSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  given Owner = unsafeWindowOwner

  /** A pace that holds the paced parse until the test releases it.
    *
    * `EditorPacing.pace` is a debounce: a new event replaces the event that
    * waits. This pace does the same, but it waits for `release` and not for a
    * timer.
    */
  private final class HeldPace:
    private var waiting: Option[Unit] = None
    private val released              = EventBus[Unit]()

    def apply(in: EventStream[Unit]): EventStream[Unit] =
      in.foreach(event => waiting = Some(event))
      released.events

    /** The paced event that waits now, if one waits. */
    def held: Option[Unit] = waiting

    /** Send the paced event that waits, if one waits. */
    def release(): Unit =
      val event = waiting
      waiting = None
      event.foreach(released.emit)

  /** `assert`, with a message that reaches the log. The Playwright runner
    * can give a failure with an empty message, but the console reaches the
    * log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[PacedParseGapSpec] $clue")
    assert(condition, clue)

  /** `assertEquals`, with the same log message as [[check]]. */
  private def same[A](obtained: A, expected: A, clue: String)(using CanEqual[A, A]): Unit =
    check(obtained == expected, s"$clue\n  obtained: $obtained\n  expected: $expected")

  /** Wait until `condition` is true, or until the time ends. The future does
    * not fail at the end of the time. The assertions that come after it tell
    * what is wrong.
    */
  private def settle(condition: => Boolean, timeoutMs: Int = 3000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition || js.Date.now() - start >= timeoutMs then p.trySuccess(())
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  private val dot = "digraph G {\n  a -> b\n}"

  private def phasesWith(heldPace: HeldPace, languages: DefaultDiagramLanguages) =
    new InternalPhases(languages, initialSource = Some(dot), hiddenNodes = Val(ElementIds()), pace = heldPace(_))

  // ------------------------------------------------- a canvas edit in the gap

  test("a canvas edit before the paced parse lands keeps the typed text"):
    // Finding 3, part 1. The keystroke sets the new text, but it keeps
    // `graphInSync = true` and the graph of the old text. The canvas edit then
    // prints the old graph over the typed text. The guard of the parse result
    // then drops the paced parse, so the typed text is lost.
    val typed = "digraph G {\n  a -> b\n  typedNode\n}"
    withGraphvizAsync: graphviz =>
      val pace   = HeldPace()
      val phases = phasesWith(pace, DefaultDiagramLanguages(graphviz))
      val graph  = phases.fullGraph.observe
      settle(phases.loadStatus.now() == DiagramLoadStatus.Ready)
        .map: _ =>
          same(graph.now().nodeIds, Set(NodeId("a"), NodeId("b")), "precondition: the first parse did not land")
          phases.sourceText.set(typed)
          check(pace.held.isDefined, "precondition: the keystroke did not ask for a paced parse")
          // A canvas edit while the paced parse waits.
          phases.fullGraphV.update(_.addNode()._1)
          pace.release()
        .flatMap(_ => settle(phases.loadStatus.now() == DiagramLoadStatus.Ready))
        .map: _ =>
          check(
            phases.sourceText.now().contains("typedNode"),
            s"the canvas edit replaced the typed text:\n${phases.sourceText.now()}"
          )
          check(
            graph.now().nodeIds.contains(NodeId("typedNode")),
            s"the graph does not have the typed node: ${graph.now().nodeIds}"
          )

  // ------------------------------------- a paste that changes the language

  private val pasted = "flowchart LR\n  p --> q"

  test("a pasted Mermaid diagram ends Ready when the paced DOT parse lands after the Mermaid parse"):
    // Finding 3, part 2. The paced event keeps the format of the time of the
    // keystroke (DOT). Auto-detect then switches to Mermaid, and the Mermaid
    // parse lands. The paced DOT event then sets `loadStatus` to Loading, and
    // the format guard drops its result. Thus `loadStatus` stays Loading.
    withGraphvizAsync: graphviz =>
      val pace   = HeldPace()
      val phases = phasesWith(pace, DefaultDiagramLanguages(graphviz))
      val graph  = phases.fullGraph.observe
      settle(phases.loadStatus.now() == DiagramLoadStatus.Ready)
        .map: _ =>
          same(graph.now().nodeIds, Set(NodeId("a"), NodeId("b")), "precondition: the first parse did not land")
          phases.sourceText.set(pasted)
          same(phases.formatSelection.now(), DiagramFormat.Mermaid, "precondition: auto-detect did not switch")
          check(pace.held.isDefined, "precondition: the paste did not ask for a paced parse")
        .flatMap: _ =>
          settle(
            phases.loadStatus.now() == DiagramLoadStatus.Ready &&
              graph.now().nodeIds == Set(NodeId("p"), NodeId("q")),
            timeoutMs = 5000
          )
        .map: _ =>
          same(phases.loadStatus.now(), DiagramLoadStatus.Ready, "precondition: the Mermaid parse did not land")
          pace.release()
        .flatMap(_ => settle(phases.loadStatus.now() == DiagramLoadStatus.Ready))
        .map: _ =>
          same(phases.loadStatus.now(), DiagramLoadStatus.Ready, "the stale paced DOT parse left loadStatus in Loading")
          same(graph.now().nodeIds, Set(NodeId("p"), NodeId("q")), "the graph does not match the pasted text")
          same(phases.formatSelection.now(), DiagramFormat.Mermaid, "the format is not the pasted language")

  test("a pasted Mermaid diagram lands when the paced DOT parse comes before the Mermaid parse lands"):
    // Finding 3, part 2. The same paste, but the paced DOT event comes while
    // the Mermaid parse waits. The DOT event replaces the Mermaid parse in
    // `flatMapSwitch`, and the format guard drops the DOT result. Thus the
    // Mermaid graph does not land, and `loadStatus` stays Loading.
    withGraphvizAsync: graphviz =>
      val pace   = HeldPace()
      val phases = phasesWith(pace, DefaultDiagramLanguages(graphviz))
      val graph  = phases.fullGraph.observe
      settle(phases.loadStatus.now() == DiagramLoadStatus.Ready)
        .map: _ =>
          same(graph.now().nodeIds, Set(NodeId("a"), NodeId("b")), "precondition: the first parse did not land")
          phases.sourceText.set(pasted)
          same(phases.formatSelection.now(), DiagramFormat.Mermaid, "precondition: auto-detect did not switch")
          check(pace.held.isDefined, "precondition: the paste did not ask for a paced parse")
          // The Mermaid parse is asynchronous, so it has not landed yet.
          pace.release()
        .flatMap: _ =>
          settle(
            phases.loadStatus.now() == DiagramLoadStatus.Ready &&
              graph.now().nodeIds == Set(NodeId("p"), NodeId("q")),
            timeoutMs = 5000
          )
        .map: _ =>
          same(phases.loadStatus.now(), DiagramLoadStatus.Ready, "the stale paced DOT parse left loadStatus in Loading")
          same(graph.now().nodeIds, Set(NodeId("p"), NodeId("q")), "the Mermaid graph did not land")
          same(phases.formatSelection.now(), DiagramFormat.Mermaid, "the format is not the pasted language")
