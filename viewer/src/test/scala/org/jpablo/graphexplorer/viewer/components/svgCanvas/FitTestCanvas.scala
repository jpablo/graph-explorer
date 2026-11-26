package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.Waits
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A canvas of 800 x 600 px in the test page, for the specs of Fit, Fit width and Fit height. */
object FitTestCanvas:

  val canvasW = 800.0
  val canvasH = 600.0

  /** The margin between the drawing and each edge of the SVG box after a fit, in px. */
  val margin = 8.0

  /** The rectangle of the drawing on the screen, relative to the SVG box: (left, top, width, height) in px. The
    * drawing is the visible marks without the Mermaid sheet and the Graphviz background polygon.
    */
  final case class Drawn(left: Double, top: Double, width: Double, height: Double, detail: String = ""):
    def right: Double  = canvasW - left - width
    def bottom: Double = canvasH - top - height
    override def toString =
      f"left $left%.1f, top $top%.1f, width $width%.1f, height $height%.1f, right $right%.1f, " +
        f"bottom $bottom%.1f ($detail)"

  /** The marks of a drawing. The test measures them on the screen. */
  private val marks = "path, rect, circle, ellipse, line, polyline, polygon, text, image, use, foreignObject"

  final class Page(val state: ViewerState, val commands: Commands, val box: dom.html.Element):
    def svg: dom.svg.SVG = box.querySelector("#canvas-container > svg").asInstanceOf[dom.svg.SVG]

    def drawn: Drawn =
      val s     = svg
      val boxR  = s.getBoundingClientRect()
      val group = CanvasRoot.mainGroup(s)
      // Graphviz draws the background of the root graph as the first polygon of the root group.
      val background =
        if group.classList.contains("graph") then Option(group.querySelector(":scope > polygon")) else None
      val rects =
        group
          .querySelectorAllT[dom.Element](marks)
          .filterNot(e => e.classList.contains(MermaidBackend.sheetClass) || background.contains(e))
          .filter(e => e.closest("defs, marker, .gx-badge-layer") == null)
          .map(_.getBoundingClientRect())
          .filter(r => r.width > 0 || r.height > 0)
      val l = rects.map(_.left).min
      val t = rects.map(_.top).min
      val r = rects.map(_.right).max
      val b = rects.map(_.bottom).max
      val vb = s.viewBox.baseVal
      val detail =
        f"svg box ${boxR.width}%.0f x ${boxR.height}%.0f, viewBox ${vb.x}%.1f ${vb.y}%.1f ${vb.width}%.1f " +
          f"${vb.height}%.1f, transform ${group.getAttribute("transform")}, ${rects.size} marks"
      Drawn(l - boxR.left, t - boxR.top, r - l, b - t, detail)

    def key(k: String, shift: Boolean = false): Unit =
      val init = js.Dynamic.literal(key = k, shiftKey = shift, bubbles = true, cancelable = true)
      commands.handleKeyDown(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  /** Show `source` in a canvas of [[canvasW]] x [[canvasH]] px. Run `body` when `ready` is true and the layout
    * transition is over.
    */
  def withCanvas(graphviz: Graphviz, id: String, source: String)(ready: dom.html.Element => Boolean)(
      body: Page => Future[Unit]
  ): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    val state     = ViewerState(ViewTarget.diagram(id), graphviz, _ => (), initialSource = Some(source))(using owner)
    val commands  = Commands(state, RouterCommands(Router()))
    val box       = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    box.style.width = s"${canvasW}px"
    box.style.height = s"${canvasH}px"
    val rules = dom.document.createElement("style")
    // In the app, the SVG fills the canvas (the canvas is a flex box). The test page has no app stylesheet, so the
    // SVG gets the size of the box here. The app stylesheet also makes each floating toolbar `absolute`. Without
    // that rule, the "View-only" chip of a sequence diagram is a block below the SVG.
    rules.textContent =
      ".floating-toolbar { position: absolute; } " +
        "#canvas-container { width: 100%; height: 100%; } " +
        "#canvas-container > svg { display: block; width: 100%; height: 100%; }"
    box.appendChild(rules)
    dom.document.body.appendChild(box)
    val root = render(box, CanvasContainer(state, commands))
    def shown = box.querySelector("#canvas-container > svg") != null && ready(box)
    Waits
      .settle(shown, timeoutMs = 10000)
      // A layout transition moves the elements for 300 ms.
      .flatMap(_ => Waits.after(400))
      .flatMap: _ =>
        if !shown then throw AssertionError(s"$id: precondition: the canvas shows no drawing")
        body(Page(state, commands, box))
      .transform: outcome =>
        root.unmount()
        box.parentNode.removeChild(box)
        owner.killSubscriptions()
        dom.window.history.replaceState(null, "", startHref)
        outcome

  /** True when the canvas shows `n` Graphviz nodes. */
  def dotNodes(n: Int)(box: dom.html.Element): Boolean =
    box.querySelectorAll("svg g.node").length == n

  /** True when the canvas shows a Mermaid drawing (it has the sheet). */
  def mermaidShown(box: dom.html.Element): Boolean =
    box.querySelector(s"svg rect.${MermaidBackend.sheetClass}") != null

  def near(a: Double, b: Double): Boolean = (a - b).abs <= 1.0
