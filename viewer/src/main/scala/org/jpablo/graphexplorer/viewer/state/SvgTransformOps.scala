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

  // A diagram opens with the frame of the Fit button (see [[resetView]]).
  // An embedded view (an item of a board) keeps a zoom of 1.
  private val zoomValue = Var(if embedded then 1.0 else SvgTransformOps.FitZoom)
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

  // On when a diagram opens. Then the whole diagram is visible, and the zoom
  // toolbar does not hide a part of it. In 2D, Auto fits again after each
  // layout (anchoring stops). In 3D, Auto fits the camera in each frame. In 2D
  // and in 3D, a zoom or a pan of the user sets Auto to off (see
  // [[userMoved]]). A move of the app (Fit, the anchor after a layout, the
  // keyboard scroll to the selection) keeps Auto as it is. An embedded view
  // (an item of a board) starts with Auto off, because the board shows its
  // drawing directly.
  val autoFit = Var(!embedded)

  val fitDiagram = EventBus[Unit]()

  def autoFitToggle() =
    autoFit.update(!_)

  // The user moved the view. Set Auto to off, so that the next layout keeps
  // the zoom and the pan of the user.
  private def userMoved(): Unit =
    if autoFit.now() then autoFit.set(false)

  def zoomOut() =
    userMoved()
    zoomValue.update(_ * .9 max minZoom)

  def zoomIn() =
    userMoved()
    zoomValue.update(_ * 1.1)

  /** The transform of the drawing group, with its zoom in a typed form. The
    * canvas uses the zoom to compute the screen scale of the drawing with no
    * layout read (see `ScreenConstant.CanvasScale`). */
  val canvasTransform: Signal[CanvasTransform] =
    zoomValue.signal
      .combineWithFn(translateXY.signal)(CanvasTransform(_, _))

  /** The value of the `transform` attribute of the drawing group. */
  val transform: Signal[String] = canvasTransform.map(_.css)

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
      zoomValue   -> SvgTransformOps.FitZoom,
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

  /** The zoom at the start of a WebKit pinch (`gesturestart`). It is empty when
    * no WebKit pinch is active. */
  private var pinchStartZoom = Option.empty[Double]

  /** Set the zoom to `requested` (not less than [[minZoom]]), and keep the
    * drawing point at the client point `at` still.
    *
    * The function reads the anchor (`getScreenCTM`) one time, before the
    * transform write. It reads no layout after the write.
    */
  private def zoomAtClient(at: ClientPoint, requested: Double, mainGroup: dom.svg.G): Unit =
    val oldZoom = zoomValue.now()
    val newZoom = requested max minZoom
    if newZoom != oldZoom then
      Option(mainGroup.getScreenCTM()).foreach: ctm =>
        val anchor = at.toSvgPoint(ctm)
        val origin = SvgTransformOps.transformOrigin(mainGroup)
        cancelPanChase()
        val pan = SvgTransformOps.anchoredPan(translateXY.now(), anchor, origin, oldZoom, newZoom)
        targetXY = pan
        Var.set(
          zoomValue   -> newZoom,
          translateXY -> pan
        )

  def handleWheel(wEv: dom.WheelEvent, viewBox: dom.SVGRect, mainGroup: dom.svg.G) =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1
    // A wheel event is a zoom or a pan of the user.
    userMoved()

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomAtClient(ClientPoint(wEv.clientX, wEv.clientY), zoomValue.now() - wEv.deltaY / clientHeight, mainGroup)
    else if wEv.ctrlKey then
      // Chromium and Firefox send a trackpad pinch as a wheel event with
      // `ctrlKey`. A pinch by the factor s gives a deltaY of about -100 * ln(s).
      // During a WebKit pinch, the gesture events set the zoom. Thus the
      // canvas ignores these wheel events, and the pinch zooms one time only.
      if pinchStartZoom.isEmpty && wEv.deltaY != 0 then
        zoomAtClient(ClientPoint(wEv.clientX, wEv.clientY), zoomValue.now() * math.exp(-wEv.deltaY / 100), mainGroup)
    else
      val z     = zoomValue.now()
      val scale = viewBox.width / clientWidth max viewBox.height / clientHeight
      // Deltas ACCUMULATE into the target, so several events landing inside one
      // frame cost one transform write between them instead of one apiece —
      // and the chrome refit that rides on every write likewise runs per frame.
      panBy(SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z))

  /** A WebKit pinch starts (`gesturestart`). The function keeps the zoom of
    * this time. */
  def pinchStart(): Unit =
    userMoved()
    pinchStartZoom = Some(zoomValue.now())

  /** A WebKit pinch changes (`gesturechange`). `scale` is the factor since
    * [[pinchStart]], and `at` is the client point between the fingers. */
  def pinchChange(scale: Double, at: ClientPoint, mainGroup: dom.svg.G): Unit =
    pinchStartZoom.foreach: startZoom =>
      userMoved()
      zoomAtClient(at, startZoom * scale, mainGroup)

  /** A WebKit pinch ends (`gestureend`). */
  def pinchEnd(): Unit =
    pinchStartZoom = None

/** The pan and zoom of the drawing group of the canvas.
  *
  * @param zoom
  *   the scale of the drawing group. The screen scale of the drawing is the
  *   base scale of the svg viewport multiplied by this value.
  * @param pan
  *   the translation of the drawing group, in user units after the zoom.
  */
final case class CanvasTransform(zoom: Double, pan: SvgPoint) derives CanEqual:
  /** The value of the `transform` attribute of the drawing group. */
  def css: String = s"scale($zoom) translate(${pan.x} ${pan.y})"

private[state] object SvgTransformOps:
  /** The zoom of the Fit button. It is also the zoom when a diagram opens. */
  val FitZoom = 0.90

  def anchoredPan(currentPan: SvgPoint, anchor: SvgPoint, origin: SvgPoint, oldZoom: Double, newZoom: Double): SvgPoint =
    val ratio = oldZoom / newZoom
    SvgPoint(
      (anchor.x + currentPan.x - origin.x) * ratio - anchor.x + origin.x,
      (anchor.y + currentPan.y - origin.y) * ratio - anchor.y + origin.y
    )

  def transformOrigin(group: dom.svg.G): SvgPoint =
    val values = dom.window.getComputedStyle(group).transformOrigin.split(" ").flatMap(_.stripSuffix("px").toDoubleOption)
    SvgPoint(values.headOption.getOrElse(0.0), values.drop(1).headOption.getOrElse(0.0))
