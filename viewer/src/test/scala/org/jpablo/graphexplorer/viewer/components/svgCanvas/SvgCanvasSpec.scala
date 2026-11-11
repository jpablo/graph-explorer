package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.Try

/** The canvas of a DOT diagram, as the app shows it: `CanvasContainer` in the
  * document, with the svg of `ViewerState.finalSVG` in it.
  */
class SvgCanvasSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "SvgCanvasSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** The canvas in the document, and the viewer that it shows. */
  private final class Canvas(val state: ViewerState, val box: dom.html.Element):

    /** The svg that the canvas shows now. */
    def svg: dom.svg.SVG = box.querySelector("svg").asInstanceOf[dom.svg.SVG]

    /** The group of the node `id`. The search compares the `id` property, and
      * thus it does not use a CSS selector.
      */
    def nodeGroup(id: NodeId): Option[dom.svg.G] =
      box.querySelectorAll("g.node").toSeq.collectFirst:
        case g: dom.svg.G if g.id == id.toSvg => g

  private def waitFor(condition: => Boolean, description: => String, timeoutMs: Int = 10000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if Try(condition).getOrElse(false) then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        val message = s"Timed out after ${timeoutMs}ms: $description"
        println(s"[$checkPrefix] $message")
        p.tryFailure(new RuntimeException(message))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** Show `dot` in a canvas in the document, and run `body` when the canvas
    * shows the node `firstNode`. The end of the test removes the canvas, and
    * puts the URL back.
    */
  private def withCanvas(dot: String, firstNode: NodeId)(body: Canvas => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram("svg-canvas-spec"), graphviz, initialSource = Some(dot))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root   = render(box, CanvasContainer(state, commands))
      val canvas = Canvas(state, box)
      waitFor(canvas.nodeGroup(firstNode).isDefined, s"the canvas shows the node $firstNode")
        .flatMap(_ => body(canvas))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  // ---------------------------------------------------------------------------
  // Bug 1: an element id is not escaped in a CSS selector.
  // ---------------------------------------------------------------------------

  // Two node names, as the DOT text writes them. The svg id of each node is
  // `node:` and the name. A selector without an escape fails for each name
  // (`SelectableElement.idSelector` escapes the id):
  //  - `it's`: the quote ends the CSS string of `[id='node:it's']`, and
  //    `querySelectorAll` throws a SyntaxError.
  //  - `a\\b` (two backslashes): CSS reads `\\` as one backslash, so
  //    `[id='node:a\\b']` finds the id `node:a\b`, and not this node.
  private val specialIds = Seq(
    "a quote"     -> NodeId("it's"),
    "a backslash" -> NodeId("""a\\b""")
  )

  for (what, id) <- specialIds do

    // The canvas finds the selected elements with the selector of
    // `GraphvizSelectionStrategy.idSelectorFor` (`SelectableElement.query`),
    // for each selection change.
    // Failure: the node is selected, but its group has no "selected" class.
    test(s"a selected node whose id holds $what gets the selection highlight"):
      withCanvas(s"""digraph { "${id.value}" -> b }""", id): canvas =>
        canvas.state.selection.set2(id)
        afterMicrotasks:
          check(canvas.state.selection.now().contains(id), s"precondition: the node $id is not in the selection")
          val group = canvas.nodeGroup(id)
          check(
            group.exists(_.classList.contains("selected")),
            s"the node $id is selected, but its group has no highlight: class=${group.map(_.getAttribute("class"))}"
          )

    // A click on a selected record node finds the cell under the pointer.
    // `RecordCellOps.recordCells.nodeGroupInDom` uses the same selector to
    // find the group of the node.
    // Failure: the lookup throws a SyntaxError (quote), or finds no cell
    // (backslash).
    test(s"the cell lookup finds the cell of a record node whose id holds $what"):
      withCanvas(s"""digraph { "${id.value}" [shape=record, label="<p> left|right"] }""", id): canvas =>
        afterMicrotasks:
          val group = canvas.nodeGroup(id)
          check(group.isDefined, s"precondition: the canvas has no group for the record node $id")
          check(canvas.state.recordCells.isCellEditable(id), s"precondition: the record node $id has no cells to edit")
          val box  = group.get.getBoundingClientRect()
          val cell = Try(canvas.state.recordCells.cellPathAtClientPoint(id, box.left + 2, box.top + box.height / 2))
          check(cell.isSuccess, s"the cell lookup threw: ${cell.failed.map(_.toString).getOrElse("")}")
          same(cell.toOption.flatten, Some(List(0)), s"the cell lookup did not find the left cell of the record $id")

  // ---------------------------------------------------------------------------
  // Bug 4: a remount adds a second "layout settled" listener.
  // ---------------------------------------------------------------------------

  /** Send one "layout settled" event to the svg, and count the new-arrow
    * controls that the canvas adds for it. Each rebuild of the overlay
    * controls removes the controls and adds them again
    * (`AddNewArrowOps.handleNewArrowControls`).
    */
  private def controlsAddedBySettle(canvas: Canvas): Int =
    val mainGroup = CanvasRoot.mainGroup(canvas.svg)
    val observer  = new dom.MutationObserver((_, _) => ())
    observer.observe(mainGroup, new dom.MutationObserverInit { childList = true })
    canvas.svg.dispatchEvent(new dom.Event(LayoutTransition.transitionEndEvent))
    val records = observer.takeRecords()
    observer.disconnect()
    records.toSeq
      .flatMap(_.addedNodes.toSeq)
      .count:
        case e: dom.Element => e.classList.contains("new-arrow-control")
        case _              => false

  private def foldBadges(canvas: Canvas): Int =
    canvas.svg.querySelectorAll(s"g.${CountBadges.foldClass}").length

  // `CanvasContainer` shows the svg with `flatMapSwitch` on `view3DActive`.
  // The 3D view removes the svg from the document. The 2D view then gets the
  // same svg element from `state.finalSVG`, and mounts it again. Each listener
  // for `LayoutTransition.transitionEndEvent` emits `layoutSettled` once. A
  // listener that `SvgCanvas` adds on each mount gives a second listener after
  // the remount. `SvgCanvas` must add it one time for the svg element.
  // Failure: after the remount, one event rebuilds the overlay controls two
  // times.
  test("after the 3D view and back, one layout-settled event rebuilds the overlay controls once"):
    val a = NodeId("a")
    // The test page does not load the stylesheet of the app. Without a fixed
    // size, the 3D container grows with its canvas, and its ResizeObserver
    // gives the window error "ResizeObserver loop completed with undelivered
    // notifications". That error stops the test run. This style gives the
    // container the fixed size that the app gives it.
    val sizes = dom.document.createElement("style")
    sizes.textContent =
      "#canvas-container { width: 800px; height: 600px; } " +
        "#scene3d-container { position: relative; width: 100%; height: 100%; overflow: hidden; }"
    dom.document.head.appendChild(sizes)
    withCanvas("digraph { subgraph cluster_x { a } a -> b }", a): canvas =>
      val state = canvas.state
      state.selection.set2(a)
      afterMicrotasks:
        val before = controlsAddedBySettle(canvas)
        check(before > 0, "precondition: a layout-settled event added no new-arrow controls for the selected node")
        val badges = foldBadges(canvas)
        check(badges > 0, "precondition: the cluster has no fold badge")
        (canvas.svg, before, badges)
      .flatMap: (svg0, before, badges) =>
        state.enable3D.set(true)
        state.view3D.set(true)
        afterMicrotasks:
          check(!svg0.isConnected, "precondition: the 3D view did not remove the svg from the document")
          state.view3D.set(false)
        .flatMap: _ =>
          afterMicrotasks:
            check(canvas.svg eq svg0, "precondition: after the 3D view, the canvas does not show the same svg element")
            same(foldBadges(canvas), badges, "after the remount, the cluster has a different number of fold badges")
            val after = controlsAddedBySettle(canvas)
            check(
              after == before,
              s"after the remount, one layout-settled event rebuilt the overlay controls ${after.toDouble / before} times " +
                s"(it added $after new-arrow controls; before the 3D view it added $before)"
            )
    .transform: outcome =>
      sizes.parentNode.removeChild(sizes)
      outcome
