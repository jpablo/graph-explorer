package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint, SvgPoint}

trait SvgTransformOps:
  this: ViewerState =>

  /** What the canvas DRAWS. Written once per animation frame while a gesture is
    * in flight, and directly by the exact-positioning callers. */
  private val translateXY = Var(SvgPoint.origin)

  /** The pan input of the gesture in flight. [[stepPan]] draws it, one time
    * in each frame. When no gesture is in flight, its total is the drawn pan.
    *
    * The drawn pan does not follow the wheel events directly, because a
    * trackpad and a display do not tick together. At 60 Hz, about 6% of the
    * frames got no wheel event. A transform that the events set directly
    * froze in those frames. A first-order filter that chased a pan target
    * then moved in each frame. At 120 Hz, the events arrive 1 ms or 8 ms after
    * their trackpad times, in turn. The filter then moved by a small step and
    * a large step, in turn. The resampler draws the input at its trackpad
    * times, a fixed delay ago (see [[PanResampler]]). Thus each frame moves by
    * the motion of the fingers over one frame period.
    */
  private var panInput = PanResampler.empty(SvgPoint.origin)

  private var panRafId = Option.empty[Int]

  // A diagram opens with the frame of the Fit button (see [[resetView]]).
  // An embedded view (an item of a board) keeps a zoom of 1.
  private val zoomValue = Var(if embedded then 1.0 else SvgTransformOps.FitZoom)
  private val minZoom   = 0.05

  // On when a diagram opens. Then the whole diagram is visible, and the zoom
  // toolbar does not hide a part of it. In 2D, Auto fits again after each
  // layout (anchoring stops). In 3D, Auto fits the camera in each frame. In 2D
  // and in 3D, a zoom or a pan of the user sets Auto to off (see
  // [[userMoved]]). A move of the app (Fit, the anchor after a layout, the
  // keyboard scroll to the selection) keeps Auto as it is. An embedded view
  // (an item of a board) starts with Auto off, because the board shows its
  // drawing directly.
  val autoFit = Var(!embedded)

  /** Fit, Fit width and Fit height. The 2D canvas and the 3D scene do the fit, because they know their size. */
  val fitDiagram = EventBus[FitMode]()

  /** The measure of the fit of the drawing. The 2D canvas sets it when it mounts (see [[measureFitWith]]). It is
    * empty when no canvas is mounted, for example in an embedded view (an item of a board). The measure itself gives
    * no transform when the canvas has no SVG: before the first render, and in 3D.
    */
  private var fitMeasure = Option.empty[FitMode => Option[CanvasTransform]]

  /** The canvas gives the measure of a fit (see `CanvasFit`). [[resetView]] needs it, because the state has no DOM.
    *
    * @return
    *   the function that removes the measure. It does nothing when a different canvas set a newer measure.
    */
  def measureFitWith(measure: FitMode => Option[CanvasTransform]): () => Unit =
    fitMeasure = Some(measure)
    () => if fitMeasure.exists(_ eq measure) then fitMeasure = None

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

  /** True while a pan moves the drawing. It is true from the first input of a
    * pan until the pan loop stops. The loop stops when the pan settles, or when
    * an exact move (Fit, a zoom, an anchor) cancels it. A zoom is not a pan.
    *
    * The canvas uses it to turn off the pointer events of the drawing. Else the
    * browser hit-tests the moving drawing in each frame, and the hover handlers
    * run for each element that passes under the still pointer.
    */
  private val panningVar = Var(false)

  val panning: Signal[Boolean] = panningVar.signal

  /** Set [[panningVar]] only on a change, so that each frame does not write it. */
  private def setPanning(on: Boolean): Unit =
    if panningVar.now() != on then panningVar.set(on)

  /** The transform of the drawing group, with its zoom in a typed form. The
    * canvas uses the zoom to compute the screen scale of the drawing with no
    * layout read (see `ScreenConstant.CanvasScale`). */
  val canvasTransform: Signal[CanvasTransform] =
    zoomValue.signal
      .combineWithFn(translateXY.signal)(CanvasTransform(_, _))

  /** The value of the `transform` attribute of the drawing group. */
  val transform: Signal[String] = canvasTransform.map(_.css)

  // ---- the pan loop ------------------------------------------------------

  /** Add a pan input. The drawn pan follows it over the next frames.
    *
    * @param delta
    *   the scroll of the view, in user units. The pan moves by the opposite.
    * @param stampMs
    *   the trackpad time of the input (the `timeStamp` of the wheel event).
    * @param arrivalMs
    *   the time the page got the input (`performance.now()`).
    */
  private def panBy(delta: SvgPoint, stampMs: Double, arrivalMs: Double): Unit =
    panInput = panInput.add(PanSample(stampMs, arrivalMs, SvgPoint.origin - delta))
    if panRafId.isEmpty then
      setPanning(true)
      requestPanFrame()

  /** Put the pan EXACTLY here, now. The input of a gesture in flight that is
    * not drawn yet is dropped. */
  private def snapPan(p: SvgPoint): Unit =
    setPanNow(p)
    translateXY.set(p)

  /** Stop the pan loop and start a new pan input at `p`. The caller sets
    * `translateXY` to `p`. */
  private def setPanNow(p: SvgPoint): Unit =
    cancelPanLoop()
    panInput = PanResampler.empty(p)

  private def requestPanFrame(): Unit =
    panRafId = Some(dom.window.requestAnimationFrame(stepPan(_)))

  private def cancelPanLoop(): Unit =
    panRafId.foreach(dom.window.cancelAnimationFrame)
    panRafId = None
    setPanning(false)

  private def stepPan(nowMs: Double): Unit =
    val cur  = translateXY.now()
    val next = panInput.at(nowMs)
    if panInput.settledAt(nowMs) then
      // All the input is drawn. `next` is exactly the total of the input.
      panRafId = None
      panInput = PanResampler.empty(next)
      setPanning(false)
    else requestPanFrame()
    if next.toTuple != cur.toTuple then translateXY.set(next)

  // ---- entry points ------------------------------------------------------

  /** Fit width or Fit height: put the drawing at `target` (see [[CanvasTransform.fit]]).
    *
    * Auto goes off. Else the next layout fits all of the drawing again.
    */
  def fitAxis(target: CanvasTransform): Unit =
    userMoved()
    setPanNow(target.pan)
    Var.set(
      zoomValue   -> target.zoom,
      translateXY -> target.pan
    )

  /** Fit: all of the drawing, with a margin of [[CanvasTransform.FitMarginPx]] (see [[CanvasTransform.fit]]).
    *
    * The canvas measures the drawing (see [[measureFitWith]]). Without a measure (no canvas, a canvas with no size,
    * the 3D view), the fit is [[CanvasTransform.fallback]]. Auto stays as it is.
    */
  def resetView(): Unit =
    resetView(fitMeasure.flatMap(_(FitMode.Page)))

  /** Fit to `target`, a fit that the caller measured. An empty `target` gives [[CanvasTransform.fallback]].
    *
    * The fit after a layout gives the target of the NEW svg. Then the measure does not depend on the order in which
    * the new svg and the old svg go in and out of the canvas.
    */
  def resetView(target: Option[CanvasTransform]): Unit =
    val fit = target.getOrElse(CanvasTransform.fallback)
    // A fit is a DESTINATION, not a gesture. A glide to it would move the
    // whole drawing across the canvas on every re-layout under auto-fit.
    setPanNow(fit.pan)
    Var.set(
      zoomValue   -> fit.zoom,
      translateXY -> fit.pan
    )

  /** Cancel a CLIENT-space delta (px) EXACTLY: content that moved right by
    * `dx` on screen moves back left by the same amount. `scaleX`/`scaleY` are
    * client px per user unit — getScreenCTM().a/.d of an element whose parent
    * chain ends at the transformed main group (a node's `<g>`), so they
    * already include both the viewBox mapping and the zoom. Contrast
    * [[panByClient]], whose window-based scale is a wheel-feel approximation,
    * not an exact compensation.
    *
    * Snaps, and drops the input of a pan in flight that is not drawn yet:
    * the caller measured this delta from what is ON SCREEN right now, so the
    * correction belongs to the DRAWN offset. Easing it would put the anchor
    * visibly adrift for the length of the ease — the one thing an anchor may
    * never do.
    */
  def panCompensateClient(dx: Double, dy: Double, scaleX: Double, scaleY: Double): Unit =
    if scaleX > 0 && scaleY > 0 then
      snapPan(translateXY.now() - SvgPoint(dx / scaleX, dy / scaleY))

  /** The zoom of the drawing group now. */
  def zoomNow: Double = zoomValue.now()

  /** Keep the screen scale and the screen place of an anchor after a new layout of a free layout (ADR 0004).
    *
    * A new layout gives a new viewBox. The svg shows its viewBox as large as its box allows, so a larger drawing
    * gets a smaller screen scale, and each node moves on the screen. In a free layout, the person put the nodes at
    * their places on the screen, so the screen scale must stay.
    *
    * The anchor is now at the client point `now`, at `scaleNow` client px for one user unit. Before the new layout,
    * it was at `before`, at `scaleBefore`. The zoom changes by `scaleBefore / scaleNow` around the anchor, and the pan
    * then moves the anchor back to `before`. The two values change in one write, with no layout read after it.
    *
    * @param mainGroup the drawing group of the new svg
    */
  def keepScaleAndPlace(mainGroup: dom.svg.G, now: ClientPoint, before: ClientPoint, scaleNow: Double, scaleBefore: Double): Unit =
    if scaleNow > 0 && scaleBefore > 0 then
      Option(mainGroup.getScreenCTM()).foreach: ctm =>
        val oldZoom = zoomValue.now()
        val newZoom = (oldZoom * scaleBefore / scaleNow) max minZoom
        val anchor  = now.toSvgPoint(ctm)
        val origin  = SvgTransformOps.transformOrigin(mainGroup)
        val zoomed  = SvgTransformOps.anchoredPan(translateXY.now(), anchor, origin, oldZoom, newZoom)
        // The client px for one user unit at the new zoom.
        val scale = scaleNow * newZoom / oldZoom
        val pan   = zoomed - SvgPoint((now.x - before.x) / scale, (now.y - before.y) / scale)
        setPanNow(pan)
        Var.set(
          zoomValue   -> newZoom,
          translateXY -> pan
        )

  /** Pan the canvas by a CLIENT-space delta (px), with wheel semantics: a
    * positive `dx` scrolls the view right (content moves left). Same
    * client→user-space conversion as [[handleWheel]]'s pan branch.
    */
  def panByClient(dx: Double, dy: Double, viewBox: dom.SVGRect): Unit =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1
    val z            = zoomValue.now()
    val scale        = viewBox.width / clientWidth max viewBox.height / clientHeight
    // This input has no trackpad time. Its time is the time of the call.
    val nowMs = dom.window.performance.now()
    panBy(SvgPoint(dx * scale / z, dy * scale / z), nowMs, nowMs)

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
        // The zoom starts from the DRAWN pan. The input of a pan in flight that
        // is not drawn yet is dropped: its deltas are in the units of the old
        // zoom, and it is at most about DelayMs of motion.
        val pan = SvgTransformOps.anchoredPan(translateXY.now(), anchor, origin, oldZoom, newZoom)
        setPanNow(pan)
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
      // Each event adds a sample to the pan input. The pan loop draws the input
      // one time in each frame. Thus several events in one frame cost one
      // transform write, and the chrome refit on each write runs once a frame.
      panBy(SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z), wEv.timeStamp, dom.window.performance.now())

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

object CanvasTransform:

  /** The margin between the drawing and each edge of the SVG box after a fit, in CSS px. */
  val FitMarginPx = 8.0

  /** The fit when the canvas cannot measure the drawing: the viewBox at 90%, at the center. */
  val fallback: CanvasTransform = CanvasTransform(SvgTransformOps.FitZoom, SvgPoint.origin)

  /** The transform of a fit of the drawing.
    *
    * The SVG shows its viewBox as large as its box allows ("meet"), and a zoom of 1 shows all of the viewBox. The
    * zoom scales around the center c of the viewBox (see `CanvasRoot.centerScaleOrigin`). Thus the transform draws a
    * user point p at (box center) + meet · zoom · (p + pan − c), in px.
    *
    * The fit uses the bounds of the drawing, not the viewBox. The viewBox can have padding around the drawing.
    *
    *   - [[FitMode.Page]]: the Fit button. All of the drawing, as large as the box minus the margins allows.
    *   - [[FitMode.Width]]: the width of the drawing is the width of the box minus the two margins.
    *   - [[FitMode.Height]]: the height of the drawing is the height of the box minus the two margins.
    *
    * On each axis, a drawing that fits between the margins is at the center. A drawing that is larger than the box
    * shows its start at the margin: its top after Fit width, its left side after Fit height.
    *
    * @param boxW    the width of the SVG box, in px
    * @param boxH    the height of the SVG box, in px
    * @param viewBox the viewBox, in user units
    * @param drawing the bounds of the drawing, in user units (the space inside the drawing group)
    * @param margin  the margin at each edge, in px
    */
  def fit(
      mode:    FitMode,
      boxW:    Double,
      boxH:    Double,
      viewBox: BBox,
      drawing: BBox,
      margin:  Double = FitMarginPx
  ): CanvasTransform =
    if boxW <= 0 || boxH <= 0 || viewBox.width <= 0 || viewBox.height <= 0 then fallback
    else
      // A drawing with no size (no marks, or one point) fits as its viewBox.
      val d = if drawing.width > 0 || drawing.height > 0 then drawing else viewBox
      // px for each user unit at a zoom of 1.
      val meet   = math.min(boxW / viewBox.width, boxH / viewBox.height)
      val availW = (boxW - 2 * margin) max 1
      val availH = (boxH - 2 * margin) max 1
      // px for each user unit that fits the width, and the height. A line has no size on one axis.
      val kx   = if d.width > 0 then availW / d.width else Double.PositiveInfinity
      val ky   = if d.height > 0 then availH / d.height else Double.PositiveInfinity
      val page = kx min ky
      val k = mode match
        case FitMode.Page   => page
        case FitMode.Width  => if kx.isInfinite then page else kx
        case FitMode.Height => if ky.isInfinite then page else ky
      // The pan on one axis. A drawing that fits between the margins goes to the center. A larger one puts its start
      // at the margin.
      def axisPan(boxLen: Double, avail: Double, start: Double, len: Double, center: Double): Double =
        if k * len > avail * (1 + 1e-9) then (margin - boxLen / 2) / k - start + center
        else center - (start + len / 2)
      CanvasTransform(
        k / meet,
        SvgPoint(
          axisPan(boxW, availW, d.x, d.width, viewBox.x + viewBox.width / 2),
          axisPan(boxH, availH, d.y, d.height, viewBox.y + viewBox.height / 2)
        )
      )

/** The kinds of fit: all of the drawing (the Fit button), its width, or its height. */
enum FitMode derives CanEqual:
  case Page, Width, Height

private[state] object SvgTransformOps:
  /** The zoom of a fit with no measure of the drawing (see [[CanvasTransform.fallback]]). It is also the zoom of a
    * diagram before its first render.
    */
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
