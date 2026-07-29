package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.LayoutTransition
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.scalajs.dom

import scala.scalajs.js

/** Viewport anchoring across re-layouts.
  *
  * dot is a global optimizer: a small graph edit can produce a very different
  * layout, and with the pan/zoom transform unchanged the whole drawing appears
  * to teleport. Anchoring compensates: pick a FOCAL node (the selection, else
  * whatever sits nearest the viewport center), remember where it was on
  * SCREEN just before the swap, and after the new SVG mounts pan so that node
  * lands back on the same screen point. The layout still rearranges around it,
  * but the user's point of attention holds still.
  *
  * Both halves run synchronously around the same render (capture in the
  * signal's map while the old SVG is still mounted; apply in the new SVG's
  * mount callback, before paint), so there is no visible double-jump.
  */
trait LayoutStabilityOps:
  this: ViewerState =>

  /** User toggle for the layout-change animation (anchoring is always on —
    * it's a correction, not an effect). */
  val animateLayoutChanges: com.raquo.airstream.state.Var[Boolean] =
    com.raquo.airstream.state.Var(true)

  private var lastRenderedSvg:   Option[dom.svg.SVG]              = None
  private var pendingAnchor:     Option[(NodeId, Double, Double)] = None
  private var pendingTransition: Option[LayoutTransition.Snapshot] = None
  private var cancelTransition:  Option[() => Unit]               = None

  /** None when the element has no rendered geometry (a hidden tab/pane makes
    * getBoundingClientRect return zeros) — anchoring on a degenerate
    * measurement computes a garbage delta and throws the viewport off-screen.
    */
  private def clientCenter(e: dom.Element): Option[(Double, Double)] =
    val r = e.getBoundingClientRect()
    if r.width == 0 && r.height == 0 then None
    else Some((r.left + r.width / 2, r.top + r.height / 2))

  /** While the OLD svg is still mounted: choose the focal node and remember its
    * screen position.
    */
  def beforeLayoutSwap(strategy: SelectableElementStrategy): Unit =
    pendingAnchor = None
    pendingTransition = None
    lastRenderedSvg.filter(_.isConnected).foreach { old =>
      // Snapshot for the transition FIRST, mid-flight if one is running — the
      // measurements are visual (client space), so an interrupted animation
      // continues from wherever it was instead of snapping.
      if animateLayoutChanges.now() && !LayoutTransition.reducedMotion then
        pendingTransition = Some(LayoutTransition.capture(old, strategy))
      val nodes =
        SelectableElement
          .findAll(old, strategy)
          .flatMap(se => se.nodeId.flatMap(id => clientCenter(se.ref).map(c => (id, c))))
      if nodes.nonEmpty then
        val selected = selection.now().nodeIds
        val focal = nodes
          .find((id, _) => selected.contains(id))
          .orElse {
            val cx = dom.window.innerWidth / 2.0
            val cy = dom.window.innerHeight / 2.0
            nodes.minByOption { (_, c) =>
              val dx = c._1 - cx
              val dy = c._2 - cy
              dx * dx + dy * dy
            }
          }
        pendingAnchor = focal.map((id, c) => (id, c._1, c._2))
    }
    // All measurements are done — a still-running previous animation can stop.
    cancelTransition.foreach(_())
    cancelTransition = None

  /** When the NEW svg has just mounted: pan so the focal node keeps its screen
    * position. A focal that vanished in the new layout (hidden, collapsed
    * away) simply means no anchoring this round.
    */
  def afterLayoutSwap(newSvg: dom.svg.SVG, strategy: SelectableElementStrategy): Unit =
    lastRenderedSvg = Some(newSvg)
    // Under auto-fit, FIT is the framing policy: re-frame the new drawing now
    // (synchronously, pre-paint) and let the transition glide elements from
    // their old screen positions into the refit frame. Anchoring stands aside.
    if autoFit.now() then resetView()
    else
      pendingAnchor.foreach { (id, oldX, oldY) =>
        SelectableElement
          .query(newSvg, ElementIds.from(id), strategy)
          .headOption
          .foreach { se =>
            clientCenter(se.ref).foreach { (newX, newY) =>
              val dx = newX - oldX
              val dy = newY - oldY
              // Anchoring exists to damp SMALL jumps. When the focal moved a
              // large fraction of the viewport, the layout reorganized
              // globally (e.g. expanding a corner-dwelling box back into a
              // big drawing) — chasing the focal would drag the rest of the
              // diagram off-canvas. Let the default framing stand and leave
              // continuity to the animated transition.
              val limit = (dom.window.innerWidth min dom.window.innerHeight) * 0.4
              if (dx.abs max dy.abs) <= limit && (dx.abs > 0.5 || dy.abs > 0.5) then
                // The node's <g> carries no transform of its own, so its screen
                // CTM is exactly (viewBox mapping ∘ zoom): client px per layout
                // unit, the precise factor the compensation needs.
                val ctm = se.ref.asInstanceOf[js.Dynamic].getScreenCTM()
                if ctm != null then
                  panCompensateClient(dx, dy, ctm.a.asInstanceOf[Double], ctm.d.asInstanceOf[Double])
            }
          }
      }
    pendingAnchor = None
    // Anchor first, animate second: the tween's "new" measurements must see
    // the compensated pan, or every element would glide by the anchor delta.
    cancelTransition = pendingTransition.flatMap(LayoutTransition.animate(newSvg, strategy, _))
    pendingTransition = None
