package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, SvgPoint}

trait SvgTransformOps:
  this: ViewerState =>

  /** What the canvas DRAWS. Written once per animation frame while a gesture is
    * in flight, and directly by the exact-positioning callers. */
  private val translateXY = Var(SvgPoint.origin)

  /** Where the input has asked to be. Chased by [[stepPan]]; never drawn.
    *
    * The split exists because a trackpad and a display do not tick together.
    * Measured on a 60Hz machine: wheel events arrive at 55-67/s, so
    * `eventsPerFrame` lands at 0.91-1.00 — about 6% of frames receive NO delta
    * and, when the transform was written straight from the event, rendered a
    * pixel-identical duplicate. That is roughly five frozen frames per second
    * against a perfect 60fps counter and zero dropped frames: the stutter was
    * never a performance problem, it was a resampling one. A rendered position
    * that chases a target advances on every frame, including the empty ones,
    * because it moves on ELAPSED TIME rather than on input arriving.
    */
  private var targetXY = SvgPoint.origin

  private var panRafId   = Option.empty[Int]
  private var lastStepMs = 0.0

  private val zoomValue = Var(1.0)
  private val minZoom   = 0.05

  /** Time constant of the pan filter, in milliseconds.
    *
    * The whole trade lives here. The rendered offset closes
    * `1 - exp(-dt/tau)` of its remaining gap each frame, so during a steady
    * drag it trails the target by about `tau` worth of travel — real, added
    * latency on top of the ~18ms the JS pan path already costs (native
    * compositor scrolling on the same machine measures ~9ms; that gap is
    * structural and not what this fixes). Too small and the empty frames come
    * back; too large and the drawing swims behind the fingers.
    *
    * 20ms puts roughly 57% of the gap into the next 60Hz frame and settles a
    * gesture in about a dozen. It is deliberately a fraction of the ~17ms
    * frame: the filter exists to bridge single missing frames, not to animate.
    */
  private val PanTauMs = 20.0

  /** Gap below which the chase is finished, in USER units. Small enough to be
    * invisible at any zoom this canvas allows. */
  private val PanEpsilon = 0.01

  // Off by default: Auto is a framing POLICY the user opts into. In 2D it
  // re-frames on every re-layout (overriding anchoring), and in 3D it is a
  // continuous per-frame fit — both take framing away from the user, so
  // neither should be the out-of-the-box behavior. Loads still frame: 2D's
  // initial transform and 3D's setGraph fit are unconditional.
  val autoFit = Var(false)

  val fitDiagram = EventBus[Unit]()

  def autoFitToggle() =
    autoFit.update(!_)

  def zoomOut() =
    zoomValue.update(_ * .9 max minZoom)

  def zoomIn() =
    zoomValue.update(_ * 1.1)

  val transform =
    zoomValue.signal
      .combineWithFn(translateXY.signal): (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  // ---- the pan filter ----------------------------------------------------

  /** Move the pan TARGET. The drawn offset follows over the next few frames. */
  private def panBy(delta: SvgPoint): Unit =
    targetXY = targetXY - delta
    if panRafId.isEmpty then
      // Not `lastStepMs = now`: the first step has no previous frame to measure
      // against, and seeding it with a real timestamp would make dt the age of
      // the last gesture — an alpha of 1, i.e. a jump.
      lastStepMs = 0.0
      requestPanFrame()

  /** Put the pan EXACTLY here, now, abandoning any chase in flight. */
  private def snapPan(p: SvgPoint): Unit =
    cancelPanChase()
    targetXY = p
    translateXY.set(p)

  private def requestPanFrame(): Unit =
    panRafId = Some(dom.window.requestAnimationFrame(stepPan(_)))

  private def cancelPanChase(): Unit =
    panRafId.foreach(dom.window.cancelAnimationFrame)
    panRafId = None

  private def stepPan(nowMs: Double): Unit =
    val dtMs = if lastStepMs == 0.0 then 16.7 else nowMs - lastStepMs
    lastStepMs = nowMs
    val cur = translateXY.now()
    val dx  = targetXY.x - cur.x
    val dy  = targetXY.y - cur.y
    if math.abs(dx) < PanEpsilon && math.abs(dy) < PanEpsilon then
      panRafId = None
      // Land exactly on the target: the decay is asymptotic, and leaving the
      // residue behind would let successive gestures accumulate drift.
      if dx != 0.0 || dy != 0.0 then translateXY.set(targetXY)
    else
      // Frame-rate independent by construction. A fixed per-frame fraction
      // would pan at a different SPEED on a 120Hz display than on a 60Hz one,
      // and would lurch whenever a frame ran long.
      val alpha = 1.0 - math.exp(-dtMs / PanTauMs)
      translateXY.set(SvgPoint(cur.x + dx * alpha, cur.y + dy * alpha))
      requestPanFrame()

  // ---- entry points ------------------------------------------------------

  def resetView(): Unit =
    // A fit is a DESTINATION, not a gesture. Chasing it would glide the whole
    // drawing across the canvas on every re-layout under auto-fit.
    cancelPanChase()
    targetXY = SvgPoint.origin
    Var.set(
      zoomValue   -> 0.90,
      translateXY -> SvgPoint.origin
    )

  /** Cancel a CLIENT-space delta (px) EXACTLY: content that moved right by
    * `dx` on screen moves back left by the same amount. `scaleX`/`scaleY` are
    * client px per user unit — getScreenCTM().a/.d of an element whose parent
    * chain ends at the transformed main group (a node's `<g>`), so they
    * already include both the viewBox mapping and the zoom. Contrast
    * [[panByClient]], whose window-based scale is a wheel-feel approximation,
    * not an exact compensation.
    *
    * Snaps rather than chases, and deliberately discards a chase in flight:
    * the caller measured this delta from what is ON SCREEN right now, so the
    * correction belongs to the DRAWN offset. Easing it would put the anchor
    * visibly adrift for the length of the ease — the one thing an anchor may
    * never do.
    */
  def panCompensateClient(dx: Double, dy: Double, scaleX: Double, scaleY: Double): Unit =
    if scaleX > 0 && scaleY > 0 then
      snapPan(translateXY.now() - SvgPoint(dx / scaleX, dy / scaleY))

  /** Pan the canvas by a CLIENT-space delta (px), with wheel semantics: a
    * positive `dx` scrolls the view right (content moves left). Same
    * client→user-space conversion as [[handleWheel]]'s pan branch.
    */
  def panByClient(dx: Double, dy: Double, viewBox: dom.SVGRect): Unit =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1
    val z            = zoomValue.now()
    val scale        = viewBox.width / clientWidth max viewBox.height / clientHeight
    panBy(SvgPoint(dx * scale / z, dy * scale / z))

  def handleWheel(wEv: dom.WheelEvent, viewBox: dom.SVGRect, mainGroup: dom.svg.G) =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1

    if wEv.metaKey && wEv.deltaY != 0 then
      val oldZoom = zoomValue.now()
      val newZoom = oldZoom - wEv.deltaY / clientHeight max minZoom
      if newZoom != oldZoom then
        Option(mainGroup.getScreenCTM()).foreach: ctm =>
          val anchor = ClientPoint(wEv.clientX, wEv.clientY).toSvgPoint(ctm)
          val origin = SvgTransformOps.transformOrigin(mainGroup)
          cancelPanChase()
          val pan = SvgTransformOps.anchoredPan(translateXY.now(), anchor, origin, oldZoom, newZoom)
          targetXY = pan
          Var.set(
            zoomValue   -> newZoom,
            translateXY -> pan
          )
    else
      val z     = zoomValue.now()
      val scale = viewBox.width / clientWidth max viewBox.height / clientHeight
      // Deltas ACCUMULATE into the target, so several events landing inside one
      // frame cost one transform write between them instead of one apiece —
      // and the refitChrome that rides on every write likewise runs per frame.
      panBy(SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z))

private[state] object SvgTransformOps:
  def anchoredPan(currentPan: SvgPoint, anchor: SvgPoint, origin: SvgPoint, oldZoom: Double, newZoom: Double): SvgPoint =
    val ratio = oldZoom / newZoom
    SvgPoint(
      (anchor.x + currentPan.x - origin.x) * ratio - anchor.x + origin.x,
      (anchor.y + currentPan.y - origin.y) * ratio - anchor.y + origin.y
    )

  def transformOrigin(group: dom.svg.G): SvgPoint =
    val values = dom.window.getComputedStyle(group).transformOrigin.split(" ").flatMap(_.stripSuffix("px").toDoubleOption)
    SvgPoint(values.headOption.getOrElse(0.0), values.drop(1).headOption.getOrElse(0.0))
