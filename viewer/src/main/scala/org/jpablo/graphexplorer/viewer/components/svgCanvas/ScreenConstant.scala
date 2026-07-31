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
