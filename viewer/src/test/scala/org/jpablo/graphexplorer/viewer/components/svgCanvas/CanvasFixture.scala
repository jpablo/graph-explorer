package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The canvas of a DOT diagram in the document, and the viewer that it shows.
  *
  * The canvas is as the app shows it: `CanvasContainer`, with the svg of `ViewerState.finalSVG` in it.
  */
final class TestCanvas(val state: ViewerState, val box: dom.html.Element):

  /** The svg that the canvas shows now. */
  def svg: dom.svg.SVG = box.querySelector("svg").asInstanceOf[dom.svg.SVG]

  /** The group of the node `id`. The search compares the `id` property, and thus it does not use a CSS selector. */
  def nodeGroup(id: NodeId): Option[dom.svg.G] =
    box.querySelectorAll("g.node").toSeq.collectFirst:
      case g: dom.svg.G if g.id == id.toSvg => g

/** A fixture for the specs that show a DOT diagram in a canvas in the document. */
trait CanvasFixture:
  self: FunSuite & TestHelpers & LoggedChecks =>

  /** The id of the diagram that the viewer of the canvas shows. */
  def canvasDiagramId: String

  /** Show `dot` in a canvas in the document, and run `body` when the canvas shows the node `firstNode`. The end of the
    * test stops a mouse action, removes the canvas, and puts the URL back.
    */
  def withCanvas(dot: String, firstNode: NodeId)(body: TestCanvas => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram(canvasDiagramId), graphviz, initialSource = Some(dot))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root   = render(box, CanvasContainer(state, commands))
      val canvas = TestCanvas(state, box)
      Waits
        .settle(canvas.nodeGroup(firstNode).isDefined, timeoutMs = 10000)
        .flatMap: _ =>
          check(canvas.nodeGroup(firstNode).isDefined, s"precondition: the canvas does not show the node $firstNode")
          body(canvas)
        .transform: outcome =>
          state.mouseAction.inactive()
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome
