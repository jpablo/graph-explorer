package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.scalajs.dom

import scala.scalajs.js

/** Canvas CONTROLS hold a constant size on SCREEN. They are chrome sitting on
  * the drawing, not part of it: zoom must not inflate them into dinner plates,
  * nor shrink them out of reach — and a control drawn beside a fixed-size one
  * must not drift away from it (the new-arrow circle grew past the count badge
  * it stands next to, because only the badge was ever re-fitted).
  *
  * A control's transform is three things: an ANCHOR in user units — a point on
  * the drawing it belongs to, which does NOT move with zoom — an OFFSET from
  * that anchor in screen px, and its box size in screen px. All three travel on
  * the element, so [[refit]] can recompute the transform whenever the canvas
  * transform changes underneath. Computing the geometry once was the bug: it is
  * right only at the zoom it was built at.
  */
object ScreenConstant:

  /** What a control's transform is computed from.
    *
    * @param designBox
    *   the control's size in its OWN drawing units — `sizePx / designBox` is
    *   the scale that maps the drawn shape onto its screen size.
    */
  case class Anchored(
      ax:        Double,
      ay:        Double,
      oxPx:      Double,
      oyPx:      Double,
      sizePx:    Double,
      designBox: Double
  )

  private val axAttr   = svg.svgAttr("data-ax", StringAsIsCodec, None)
  private val ayAttr   = svg.svgAttr("data-ay", StringAsIsCodec, None)
  private val oxAttr   = svg.svgAttr("data-ox", StringAsIsCodec, None)
  private val oyAttr   = svg.svgAttr("data-oy", StringAsIsCodec, None)
  private val sizeAttr = svg.svgAttr("data-size-px", StringAsIsCodec, None)
  private val boxAttr  = svg.svgAttr("data-design-box", StringAsIsCodec, None)

  /** User units per CLIENT pixel in `reference`'s frame. None when there is no
    * usable CTM (a hidden or pre-layout svg): writing a scale from a fallback
    * of 1 there blows the control up to diagram-units size until the next
    * pan/zoom, so callers skip and keep the last good geometry instead.
    */
  def userPerPx(reference: dom.Element): Option[Double] =
    Option(reference.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
      .map(m => math.abs(m.a))
      .filter(_ > 0)
      .map(1.0 / _)

  // ---- non-scaling-stroke correction -------------------------------------
  //
  // `vector-effect: non-scaling-stroke` is the primitive the whole
  // screen-constant story rests on: it holds a stroke at a fixed CLIENT width
  // however the canvas is zoomed, for free, without a per-frame DOM write.
  //
  // Chrome does not honour it in client px on a HiDPI display. It resolves the
  // width in DEVICE px, so at devicePixelRatio 2 a stroke asked to be 20px
  // renders 10 — measured, in an isolated svg with no viewBox, no transform and
  // no zoom, against a plain stroke of the same width that rendered 20. Every
  // decoration built on the primitive therefore came out at HALF its intended
  // size, and only on HiDPI: the selection casing rendered a 2.5px band as
  // 1.2px of fringe and read as absent, while every DOM probe reported the
  // width as correctly specified. A dPR-1 test surface cannot see any of it.
  //
  // The correction is MEASURED rather than assumed to be devicePixelRatio.
  // This is a browser bug, not the spec — the spec makes the effect
  // resolution-independent — so the day it is fixed the probe returns 1.0 and
  // every caller keeps working untouched. That is the whole reason not to
  // hard-code `* devicePixelRatio`: it would silently DOUBLE every decoration
  // the moment the bug went away, on a path nobody would be watching.

  /** Stroke width used by [[measuredFactor]]. Wide enough that a half-pixel of
    * hit-test granularity is noise against it. */
  private val ProbePx = 32.0

  /** (devicePixelRatio, factor) — re-measured when the ratio changes, which is
    * what moving the window to a different monitor does. */
  private var cachedFactor: Option[(Double, Double)] = None

  /** What to MULTIPLY an intended client-px stroke width by so that a
    * non-scaling-stroke element actually renders it. 1.0 on a correct browser.
    */
  def nonScalingStrokeFactor: Double =
    val dpr = dom.window.devicePixelRatio
    cachedFactor match
      case Some((d, f)) if d == dpr => f
      case _                        =>
        val f = measuredFactor()
        // Only cache a real measurement. Before <body> exists there is nothing
        // to hit-test against, and caching the 1.0 fallback would pin the app
        // to "no correction" for the rest of the session.
        if f > 0 then cachedFactor = Some((dpr, f))
        math.max(f, 1.0)

  /** Width to write on a non-scaling-stroke element to render `clientPx`. */
  def strokeWidthFor(clientPx: Double): Double = clientPx * nonScalingStrokeFactor

  /** Inverse: what a width already specified on such an element renders as. */
  def renderedStrokePx(specifiedPx: Double): Double = specifiedPx / nonScalingStrokeFactor

  /** Draw one non-scaling-stroke line of a known width in a throwaway svg and
    * binary-search outwards for the last offset its stroke still hit-tests at.
    * `pointer-events: stroke` makes the hit region the stroke itself, so this
    * reads the RENDERED width — the thing getComputedStyle cannot tell us.
    */
  private def measuredFactor(): Double =
    val ns   = "http://www.w3.org/2000/svg"
    val body = dom.document.body
    if body == null then 0.0
    else
      val host = dom.document.createElement("div")
      host.asInstanceOf[js.Dynamic].style.cssText =
        "position:fixed;left:0;top:0;width:200px;height:200px;opacity:0;pointer-events:auto;z-index:2147483647"
      val svgEl = dom.document.createElementNS(ns, "svg")
      svgEl.setAttribute("width", "200")
      svgEl.setAttribute("height", "200")
      val path = dom.document.createElementNS(ns, "path")
      path.setAttribute("d", "M10,100 L190,100")
      path.setAttribute("fill", "none")
      path.setAttribute("stroke", "black")
      path.setAttribute("stroke-width", ProbePx.toString)
      path.asInstanceOf[js.Dynamic].style.pointerEvents = "stroke"
      path.asInstanceOf[js.Dynamic].style.vectorEffect = "non-scaling-stroke"
      svgEl.appendChild(path)
      host.appendChild(svgEl)
      body.appendChild(host)
      try
        // The rendered half-width is somewhere in (0, ProbePx]; a correct
        // browser lands at ProbePx/2, Chrome-on-HiDPI at ProbePx/(2*dPR).
        var lo = 0.0
        var hi = ProbePx
        var i  = 0
        while i < 20 do
          val mid = (lo + hi) / 2
          val hit = dom.document.elementFromPoint(100, 100 + mid)
          if hit != null && (hit eq path) then lo = mid else hi = mid
          i += 1
        if lo > 0 then ProbePx / (lo * 2) else 0.0
      finally
        val parent = host.parentNode
        if parent != null then parent.removeChild(host)

  def transformOf(a: Anchored, upp: Double): String =
    val x = a.ax + a.oxPx * upp
    val y = a.ay + a.oyPx * upp
    s"translate($x, $y) scale(${a.sizePx / a.designBox * upp})"

  /** The transform, plus the record [[refit]] recomputes it from. */
  def mods(a: Anchored, upp: Double): Seq[SvgMods] =
    Seq(
      svg.transform := transformOf(a, upp),
      axAttr        := a.ax.toString,
      ayAttr        := a.ay.toString,
      oxAttr        := a.oxPx.toString,
      oyAttr        := a.oyPx.toString,
      sizeAttr      := a.sizePx.toString,
      boxAttr       := a.designBox.toString
    )

  /** Imperative twin of [[mods]], for controls built with raw DOM calls. */
  def place(g: dom.Element, a: Anchored, upp: Double): Unit =
    g.setAttribute("data-ax", a.ax.toString)
    g.setAttribute("data-ay", a.ay.toString)
    g.setAttribute("data-ox", a.oxPx.toString)
    g.setAttribute("data-oy", a.oyPx.toString)
    g.setAttribute("data-size-px", a.sizePx.toString)
    g.setAttribute("data-design-box", a.designBox.toString)
    g.setAttribute("transform", transformOf(a, upp))

  /** Every screen-constant control on the canvas. SvgCanvas re-fits this set
    * whenever the canvas transform changes; a control NOT listed here quietly
    * grows and shrinks with the zoom, which is how the new-arrow circles came
    * to dwarf the count badges they stand beside.
    */
  def refitAll(svg: dom.svg.SVG): Unit =
    refit(svg, s"g.${CountBadges.badgeClass}")
    refit(svg, "g.new-arrow-control")
    refit(svg, "g.edge-endpoint-disk")

  /** Re-fit every control matching `selector` from its own stored record.
    * Position and size come back exactly as if the control had been built at
    * the current zoom — no rebuild, so listeners and drag state survive.
    */
  def refit(root: dom.Element, selector: String): Unit =
    root.querySelectorAllT[dom.Element](selector).foreach: g =>
      for
        parent <- Option(g.parentNode.asInstanceOf[dom.Element])
        upp    <- userPerPx(parent)
        a      <- anchoredOf(g)
      do g.setAttribute("transform", transformOf(a, upp))

  private def anchoredOf(g: dom.Element): Option[Anchored] =
    for
      ax   <- num(g, "data-ax")
      ay   <- num(g, "data-ay")
      ox   <- num(g, "data-ox")
      oy   <- num(g, "data-oy")
      size <- num(g, "data-size-px")
      box  <- num(g, "data-design-box").filter(_ > 0)
    yield Anchored(ax, ay, ox, oy, size, box)

  private def num(g: dom.Element, name: String): Option[Double] =
    Option(g.getAttribute(name)).map(_.toDoubleOption).flatten
