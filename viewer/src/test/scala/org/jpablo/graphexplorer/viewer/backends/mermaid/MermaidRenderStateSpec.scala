package org.jpablo.graphexplorer.viewer.backends.mermaid

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{Val, Var}
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.{DiagramRenderInputs, RenderState}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.utils.TimerHelpers

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The Mermaid renderer tells the state of its render ([[RenderState]]).
  *
  * A view must know a render that waits from a render that failed. All the
  * Mermaid parses and renders wait in one queue, so a render can wait for a
  * long time. These tests use the real Mermaid in the browser.
  */
class MermaidRenderStateSpec extends FunSuite with TimerHelpers:

  private val flowchart = "flowchart LR\n  A[Start] --> B[End]\n"
  private val other     = "flowchart TD\n  C[One] --> D[Two]\n"

  // Mermaid cannot read this text, so its render fails.
  private val broken = "flowchart LR\n  A -->\n"

  /** A render of the source text in `source`, and a record of each state that it gives. */
  private final class Recorded(val source: Var[String]):
    private val owner = ManualOwner()
    val states        = mutable.Buffer.empty[RenderState]
    MermaidRenderer()
      .render(DiagramRenderInputs(Val(ViewerGraph.minimal), source.signal, Val(false)))
      .foreach(states += _)(using owner)

    def last: RenderState = states.last
    def kill(): Unit      = owner.killSubscriptions()

  private def withRender(text: String)(body: Recorded => Future[Unit]): Future[Unit] =
    val recorded = Recorded(Var(text))
    body(recorded).transform: outcome =>
      recorded.kill()
      outcome

  private def isDrawn(state: RenderState): Boolean = state match
    case RenderState.Drawn(_) => true
    case _                    => false

  private def isPending(state: RenderState): Boolean = state match
    case RenderState.Pending(_) => true
    case _                      => false

  test("a render that waits in the queue is pending, and then gives its drawing"):
    val release = Promise[Unit]()
    MermaidQueue.holdUntil(release.future)
    withRender(flowchart): recorded =>
      val pendingAtStart = recorded.states.toList
      js.timers.setTimeout(300)(release.trySuccess(()))
      waitFor(s"a drawing: ${recorded.states.toList}")(isDrawn(recorded.last))
        .transform: outcome =>
          release.trySuccess(())
          outcome
        .map: _ =>
          assertEquals(pendingAtStart, List(RenderState.Pending(None)))
          val noDrawing = recorded.states.filterNot(state => isPending(state) || isDrawn(state))
          assertEquals(noDrawing.toList, Nil, "the render gave no drawing before its drawing")

  test("a render that fails gives no drawing, with the cause of Mermaid"):
    withRender(broken): recorded =>
      waitFor(s"the end of the render: ${recorded.states.toList}")(!isPending(recorded.last)).map: _ =>
        recorded.last match
          case RenderState.NoDrawing(Some(cause)) => assert(cause.trim.nonEmpty, "the cause is empty")
          case other                              => fail(s"the failed render gave $other")

  test("an empty source text gives no drawing and no cause, at once"):
    withRender("  \n"): recorded =>
      Future(assertEquals(recorded.states.toList, List(RenderState.NoDrawing(None))))

  test("a new render keeps the earlier drawing while it is pending"):
    withRender(flowchart): recorded =>
      waitFor(s"a drawing: ${recorded.states.toList}")(isDrawn(recorded.last))
        .flatMap: _ =>
          val first = recorded.last match
            case RenderState.Drawn(drawn) => drawn
            case other                    => fail(s"no drawing: $other")
          recorded.source.set(other)
          assertEquals(recorded.last, RenderState.Pending(Some(first)), "the new render does not keep the earlier drawing")
          waitFor(s"the new drawing: ${recorded.states.toList}")(isDrawn(recorded.last)).map: _ =>
            recorded.last match
              case RenderState.Drawn(drawn) => assert(drawn ne first, "the new render gave the earlier drawing")
              case other                    => fail(s"no drawing: $other")
