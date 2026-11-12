package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.{render, unsafeWindowOwner}
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.{ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The canvas SVG must cover the whole canvas, for each diagram kind.
  *
  * The canvas pans and zooms the drawing with a transform on its root group.
  * The SVG element itself stays where it is, and it clips what is outside its
  * box. Thus the box must be the full canvas, or a pan moves the drawing out of
  * a small box and the drawing is cut.
  *
  * Mermaid sets `style="max-width: <drawing width>px"` on the SVG of most
  * diagram kinds (the `useMaxWidth` option). The app turns that option off for
  * flowcharts only. A state diagram thus came with a box as narrow as the
  * drawing (206px for the diagram below), and a pan to the side cut it.
  */
class PanClipSpec extends FunSuite with TestHelpers:

  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  private val CanvasWidth  = 800
  private val CanvasHeight = 600

  private val StateDiagram =
    """stateDiagram-v2
      |  [*] --> Idle
      |  Idle --> Selecting : click
      |  Selecting --> Editing : Enter
      |  Editing --> Selecting : save
      |  Selecting --> Idle : Escape
      |  Selecting --> Selecting : s / shift+S
      |  Editing --> [*] : close
      |""".stripMargin

  private val Flowchart = "flowchart TD\n  A --> B\n  A --> C"

  private def waitFor(condition: => Boolean, description: String, timeoutMs: Int = 10000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms waiting for $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** Mount the canvas SVG of `source` in a canvas of a fixed size, with the
    * rule of style.scss that sizes it (`svg.graphviz { width: 100% }`), and give
    * the mounted SVG to `check`.
    */
  private def mountedCanvas(name: String, source: String)(check: dom.svg.SVG => Unit): Future[Unit] =
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source))
      val style = dom.document.createElement("style")
      style.textContent = s"#$name svg.graphviz { width: 100%; height: 100%; display: block; }"
      val canvas = dom.document.createElement("div").asInstanceOf[dom.html.Div]
      canvas.id = name
      canvas.style.width = s"${CanvasWidth}px"
      canvas.style.height = s"${CanvasHeight}px"
      dom.document.head.appendChild(style)
      dom.document.body.appendChild(canvas)

      val result =
        for
          _ <- waitFor(state.finalSVGNow().isDefined, s"the canvas SVG of $name")
          _ <- Future:
                 val root = render(canvas, state.finalSVGNow().get)
                 try check(state.finalSVGNow().get.ref)
                 finally root.unmount()
        yield ()
      result.transform: outcome =>
        canvas.remove()
        style.remove()
        outcome
    }

  private def assertFillsCanvas(svg: dom.svg.SVG): Unit =
    val box = svg.getBoundingClientRect()
    // An AssertionError, not munit's `assert`/`fail`: through the Scala.js test
    // bridge and the sbt client, a munit FailException arrives with no message.
    if box.width < CanvasWidth - 1 then
      throw AssertionError(
        s"the SVG box is ${box.width}px wide in a ${CanvasWidth}px canvas, so a pan cuts the drawing " +
          s"(style=${svg.getAttribute("style")})"
      )
    if box.height < CanvasHeight - 1 then
      throw AssertionError(s"the SVG box is ${box.height}px high in a ${CanvasHeight}px canvas (style=${svg.getAttribute("style")})")

  test("the SVG of a Mermaid state diagram covers the whole canvas"):
    mountedCanvas("pan-clip-state", StateDiagram)(assertFillsCanvas)

  test("the SVG of a Mermaid flowchart covers the whole canvas"):
    // The control case: flowcharts already turn useMaxWidth off.
    mountedCanvas("pan-clip-flowchart", Flowchart)(assertFillsCanvas)
