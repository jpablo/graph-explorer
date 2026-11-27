package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** A layout change in a hidden window.
  *
  * A browser stops `requestAnimationFrame` while the document is hidden: a window behind other windows, a minimized
  * window, or a background tab. The desktop app (WebKit) does this for a window that the person does not see. The
  * layout transition (`LayoutTransition`) moves each element on animation frames. With no frame, the transition stays
  * at its first frame: each arrow keeps the route of the OLD layout, and its hit area has the route of the NEW layout.
  * The drawing then shows the old routes until the window shows again.
  *
  * Found in the desktop app on 2026-11-27: after Freeze in a window in the background, the arrow `a -> c` kept its
  * old route.
  */
class HiddenLayoutTransitionSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "HiddenLayoutTransitionSpec"
  def canvasDiagramId = "hidden-layout-transition"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val dot = "digraph G {\n  a -> b\n  b -> c\n  a -> c\n}"

  /** The route of each arrow: its id, the `d` of its drawn path, and the `d` of its hit area. */
  private def routes(canvas: TestCanvas): Seq[(String, String, String)] =
    canvas.box
      .querySelectorAll("g.edge")
      .toSeq
      .collect { case g: dom.Element => g }
      .map: g =>
        val drawn = Option(g.querySelector("path:not(.edge-hit-area)")).map(_.getAttribute("d")).getOrElse("")
        val hit   = Option(g.querySelector("path.edge-hit-area")).map(_.getAttribute("d")).getOrElse("")
        (g.id, drawn, hit)

  /** Make the document hidden, as a browser does for a window that the person does not see: `visibilityState` is
    * "hidden", and `requestAnimationFrame` does not call its callback. The returned function puts everything back.
    */
  private def hideDocument(): () => Unit =
    val doc     = dom.document.asInstanceOf[js.Dynamic]
    val win     = dom.window.asInstanceOf[js.Dynamic]
    val raf     = win.requestAnimationFrame
    val objectD = js.Dynamic.global.Object
    objectD.defineProperty(doc, "visibilityState", js.Dynamic.literal(get = (() => "hidden"): js.Function0[String], configurable = true))
    objectD.defineProperty(doc, "hidden", js.Dynamic.literal(get = (() => true): js.Function0[Boolean], configurable = true))
    win.requestAnimationFrame = ((_: js.Function1[Double, Unit]) => 0): js.Function1[js.Function1[Double, Unit], Int]
    dom.document.dispatchEvent(new dom.Event("visibilitychange"))
    () =>
      js.special.delete(doc, "visibilityState")
      js.special.delete(doc, "hidden")
      win.requestAnimationFrame = raf
      dom.document.dispatchEvent(new dom.Event("visibilitychange"))

  test("a layout change in a hidden window ends with the new route of each arrow"):
    withCanvas(dot, NodeId("a")): canvas =>
      val state = canvas.state
      Waits
        .settle(state.nodePlacesNow().nonEmpty, timeoutMs = 10000)
        .flatMap: _ =>
          check(state.nodePlacesNow().nonEmpty, "precondition: the diagram has no drawing")
          check(!LayoutTransition.reducedMotion, "precondition: the test browser asks for reduced motion")
          check(state.animateLayoutChanges.now(), "precondition: the layout animation is off")
          val before = routes(canvas).map((id, _, hit) => id -> hit).toMap
          check(before.contains("arrow:a->c/1"), s"precondition: no arrow a -> c in ${before.keys.mkString(", ")}")
          val show = hideDocument()
          state.history.markGesture()
          state.freezeLayout()
          // Wait for the drawing of the free layout: the hit area of `a -> c` gets the new route at once.
          Waits
            .settle(routes(canvas).exists((id, _, hit) => id == "arrow:a->c/1" && hit != before("arrow:a->c/1")), timeoutMs = 10000)
            // The transition lasts 300 ms. A hidden page runs a timer at most once a second.
            .flatMap(_ => Waits.after(1500))
            .map: _ =>
              val now = routes(canvas)
              check(
                now.exists((id, _, hit) => id == "arrow:a->c/1" && hit != before("arrow:a->c/1")),
                "precondition: Freeze did not change the route of a -> c"
              )
              val transitioning = canvas.box.querySelectorAll(s"svg.${LayoutTransition.transitioningClass}").length
              val stale         = now.filter((_, drawn, hit) => drawn != hit)
              check(
                transitioning == 0 && stale.isEmpty,
                s"the layout transition did not end in the hidden window: $transitioning svg still has the class " +
                  s"${LayoutTransition.transitioningClass}, and these arrows keep a route that is not the route of " +
                  s"their hit area: ${stale.map((id, drawn, hit) => s"$id drawn=${drawn.take(40)} hit=${hit.take(40)}").mkString("; ")}"
              )
            .transform: outcome =>
              show()
              outcome
