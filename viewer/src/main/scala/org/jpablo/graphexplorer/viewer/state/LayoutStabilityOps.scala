package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Transaction
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{CanvasFit, CanvasRoot, LayoutTransition}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.ClientPoint
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
  /** The focal node, its client center, and its client px for one user unit, before the swap. */
  private var pendingAnchor:     Option[(NodeId, Double, Double, Double)] = None
  private var pendingTransition: Option[LayoutTransition.Snapshot] = None
  private var cancelTransition:  Option[() => Unit]               = None

  /** The offset of the layout of the drawing on the canvas, when that drawing is a free layout (see
    * `ManualLayoutOps.layoutOffsetNow`). It gives the map from a place of the layout to the screen.
    */
  private var drawnFreeOffset: Option[(Double, Double)] = None

  /** The map from the layout to the screen of the OLD drawing: the screen matrix of its drawing group, and the offset
    * of its layout. Only a free layout has it.
    */
  private var pendingFreeMap: Option[(dom.SVGMatrix, (Double, Double))] = None

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
    // The old drawing was a free layout, and the state now gives a free layout for the new drawing.
    val freeToFree = drawnFreeOffset.isDefined && drawsFreeLayoutNow
    pendingAnchor = None
    pendingTransition = None
    pendingFreeMap =
      for
        old    <- lastRenderedSvg.filter(_.isConnected)
        offset <- drawnFreeOffset
        ctm    <- Option(CanvasRoot.mainGroup(old).getScreenCTM()).filter(_.a > 0)
      yield (ctm, offset)
    lastRenderedSvg.filter(_.isConnected).foreach { old =>
      // Snapshot for the transition FIRST, mid-flight if one is running — the
      // measurements are visual (client space), so an interrupted animation
      // continues from wherever it was instead of snapping.
      // A hidden document gets no animation frames, so it gets no transition (see LayoutTransition.animate).
      // A swap from a free layout to a free layout gets no transition: in the manual mode the person puts each node,
      // and the new drawing shows at once.
      if animateLayoutChanges.now() && !LayoutTransition.reducedMotion && !LayoutTransition.documentHidden &&
        !freeToFree
      then
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
        pendingAnchor = focal.map((id, c) => (id, c._1, c._2, screenScaleOf(old, id, strategy)))
    }
    // All measurements are done — a still-running previous animation can stop.
    cancelTransition.foreach(_())
    cancelTransition = None

  /** The client px for one user unit of the element `e`: the `a` of its screen matrix, or 0 with no matrix. */
  private def ctmScale(e: dom.Element): Double =
    val ctm = e.asInstanceOf[js.Dynamic].getScreenCTM()
    if ctm == null || js.isUndefined(ctm) then 0.0 else ctm.a.asInstanceOf[Double]

  /** The screen scale of the node `id` in the drawing `svg`, or 0 when the drawing does not show it. */
  private def screenScaleOf(svg: dom.svg.SVG, id: NodeId, strategy: SelectableElementStrategy): Double =
    SelectableElement.query(svg, ElementIds.from(id), strategy).headOption.fold(0.0)(se => ctmScale(se.ref))

  /** True when the swap keeps the screen scale and the screen place of the focal node `id` exactly: the new drawing
    * is a free layout, and `id` has a stored position. Its place in the layout then did not change in the new layout,
    * or the person moved it there on the screen (a drag holds the node at its drop place until the swap).
    */
  private def keepsScreenScale(id: NodeId, oldScale: Double, newScale: Double): Boolean =
    oldScale > 0 && newScale > 0 && hasFreeLayoutNow && layout.now().point(id.value).isDefined

  /** From a free layout to a free layout: the new drawing keeps the map from the layout to the screen. Then each node
    * that did not move in the layout stays on the screen, and a moved node moves on the screen by its move. The
    * anchor is not a node: a nudge moves the selected node, and an anchor on it kept it still on the screen, so the
    * rest of the drawing moved. A drag holds the node at the drop place until the swap, and the map puts it there too.
    *
    * @return false when the old drawing or the new drawing is not a free layout
    */
  private def keepFreeLayoutMap(newSvg: dom.svg.SVG): Boolean =
    val kept =
      for
        (oldCtm, oldOffset) <- pendingFreeMap
        if drawsFreeLayoutNow
        group   = CanvasRoot.mainGroup(newSvg)
        newCtm <- Option(group.getScreenCTM()).filter(_.a > 0)
      yield
        val newOffset = layoutOffsetNow()
        // A place of the layout. Any place works. A pinned node is near the drawing, so the numbers stay small.
        val p = layout.now().nodes.headOption.fold((0.0, 0.0))((_, place) => (place.x, place.y))
        def client(ctm: dom.SVGMatrix, offset: (Double, Double)): ClientPoint =
          val (x, y) = (p._1 - offset._1, -(p._2 - offset._2))
          ClientPoint(ctm.a * x + ctm.c * y + ctm.e, ctm.b * x + ctm.d * y + ctm.f)
        keepScaleAndPlace(group, client(newCtm, newOffset), client(oldCtm, oldOffset), newCtm.a, oldCtm.a)
    kept.isDefined

  /** When the NEW svg has just mounted: pan so the focal node keeps its screen
    * position. A focal that vanished in the new layout (hidden, collapsed
    * away) simply means no anchoring this round.
    */
  def afterLayoutSwap(newSvg: dom.svg.SVG, strategy: SelectableElementStrategy): Unit =
    lastRenderedSvg = Some(newSvg)
    // Under auto-fit, FIT is the framing policy: re-frame the new drawing now
    // (synchronously, pre-paint) and let the transition glide elements from
    // their old screen positions into the refit frame. Anchoring stands aside.
    // The fit measures `newSvg` itself. The transition has not started yet,
    // so each element is at its new place.
    if autoFit.now() then resetView(CanvasFit.transform(newSvg, FitMode.Page))
    else if keepFreeLayoutMap(newSvg) then ()
    else
      pendingAnchor.foreach { (id, oldX, oldY, oldScale) =>
        SelectableElement
          .query(newSvg, ElementIds.from(id), strategy)
          .headOption
          .foreach { se =>
            clientCenter(se.ref).foreach { (newX, newY) =>
              val newScale = ctmScale(se.ref)
              if keepsScreenScale(id, oldScale, newScale) then
                // A free layout with a pinned focal: the drawing keeps its screen scale, and the focal keeps its
                // screen place. A pinned node does not move in the layout, so there is no limit to the correction.
                keepScaleAndPlace(CanvasRoot.mainGroup(newSvg), ClientPoint(newX, newY), ClientPoint(oldX, oldY), newScale, oldScale)
              else
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
    pendingFreeMap = None
    drawnFreeOffset = Option.when(drawsFreeLayoutNow)(layoutOffsetNow())
    // The new view first, the transition second. The transition measures the new drawing in client space. If it
    // measures before the drawing group has the new zoom and pan, frame 0 starts from a wrong frame, and each element
    // jumps. The view writes above are `Var.set` calls. Inside a transaction, a `Var.set` runs in a later
    // transaction, and the drawing group gets the new transform there (the `transform` binder of `SvgCanvas`). Thus
    // the transition starts in a transaction after those writes. Airstream runs it in the same task, before the
    // browser paints. Outside a transaction, the writes and the transition run at once, in this order.
    val snapshot = pendingTransition
    pendingTransition = None
    snapshot.foreach: snap =>
      Transaction: _ =>
        // A newer render can come before this transaction. Then this svg is not the drawing any more, and the newer
        // swap has its own transition.
        if lastRenderedSvg.exists(_ eq newSvg) && newSvg.isConnected then
          cancelTransition = LayoutTransition.animate(newSvg, strategy, snap)
