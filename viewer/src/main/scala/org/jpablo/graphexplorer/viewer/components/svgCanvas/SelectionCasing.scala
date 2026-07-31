package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.scalajs.dom

import scala.scalajs.js

/** Selection decorations sized from the OBJECT they mark, not from a constant.
  *
  * A marker stated in screen px has no fixed relationship to a diagram drawn in
  * user units: the flat 2px ring and 3px edge stroke were thicker than a default
  * border at 1x and thinner from 2-3x up, so zooming in quietly turned emphasis
  * into de-emphasis — and a hand-set `penwidth` inverted it at any zoom. Width
  * here is `own + delta` with a floor, recomputed whenever the canvas transform
  * moves.
  *
  * An edge is marked with a CASING — a translucent band UNDER the spline —
  * rather than by restyling the spline, so a selected edge keeps its own colour
  * and penwidth and you can still see the attributes you are editing. The band
  * is the hit halo every edge already carries: an invisible, screen-constant,
  * perfectly aligned clone of the path, held transparent until it is wanted.
  *
  * A node's ring moves just OUTSIDE its own border for the same reason — a
  * marker painted on top of a thick coloured border competes with it, while one
  * drawn on the background beside it reads at any width.
  */
object SelectionCasing:

  /** Floors: below these a marker stops reading as one, however thin its subject
    * is. The FLOOR is what a dense overview sees — zoomed out, an edge shrinks
    * toward nothing while the casing holds its screen width, so a floor set for
    * the hit halo's convenience (14px, chosen to make hairlines clickable) drew
    * a band an order of magnitude wider than the edge inside it and turned a
    * large selection into a wash of blue.
    *
    * Consequence to know about: the casing IS the halo, so this is also the
    * click target of a SELECTED edge — 7px rather than 14. Acquiring a thin
    * edge, which is what the halo exists for, is unaffected; the halo is only
    * narrowed once the edge is already selected, and [[clear]] restores it.
    */
  private val MinRingPx   = 2.0
  private val MinCasingPx = 7.0

  /** Restored to a deselected edge — the width hit-testing wants. */
  private val HitHaloPx = 14.0

  /** The ring's ceiling. It sits OUTSIDE the border rather than on it, so it
    * only has to be seen BESIDE it — matching a 9pt border stroke for stroke
    * put a wall of solid accent around the node and made the marker the loudest
    * object on the canvas. The casing needs no such cap: it is translucent, and
    * it has to span the spline to read as a band around it.
    */
  private val MaxRingPx = 5.0

  /** How far the marker outgrows its subject. The ring only has to be seen
    * beside the border; the casing has to be seen AROUND the spline, from both
    * sides, so it needs more.
    */
  private val RingDeltaPx   = 1.0
  private val CasingDeltaPx = 5.0

  /** Read by the stylesheet for anything the width reaches by inheritance —
    * today the solid arrowhead's halo, a descendant of the selected `g.edge`.
    *
    * The head's halo is NOT the casing's width. A band around a spline shows
    * its full width; a stroke around a filled shape shows only the outer half
    * (paint-order hides the rest under the fill), so the same number would put
    * a 10px bulge on the head beside a 3px band on the shaft. `halo` is stated
    * as twice the shaft's overhang, which makes the two extend equally.
    */
  private val casingVar     = "--gx-casing-w"
  private val haloVar       = "--gx-casing-halo"
  private val ringVar       = "--gx-ring-w"
  private val nodeCasingVar = "--gx-node-casing-w"

  /** Marks the scale the decorations were last sized at, so a pan can be
    * recognised as the no-op it is. */
  private val scaleAttr = "data-casing-scale"

  /** Re-derive every selected element's decorations. Cheap per element, and
    * bounded by the SELECTION rather than by the graph — but a computed-style
    * read per element per frame is not free with hundreds selected, and these
    * widths follow the ZOOM only. A pan leaves the scale alone and is skipped;
    * a selection change passes `force`, since then it is the SET that moved.
    */
  // NOTE: `root` must be the group the canvas transform is ON (mainGroup), not
  // the svg element — the zoom lives in that transform, so an svg-rooted
  // reading of the scale never changes and the skip below would swallow every
  // zoom.
  def refit(root: dom.Element, force: Boolean = false): Unit =
    ScreenConstant.userPerPx(root).foreach: upp =>
      val key = upp.toString
      if force || root.getAttribute(scaleAttr) != key then
        root.setAttribute(scaleAttr, key)
        root.querySelectorAllT[dom.Element](".selected").foreach(size)

  def size(el: dom.Element): Unit =
    ScreenConstant.userPerPx(el).foreach: upp =>
      val ownPx    = ownStrokePx(subjectOf(el), upp)
      val casingPx = math.max(MinCasingPx, ownPx + CasingDeltaPx)
      setStyle(el, casingVar, s"${casingPx}px")
      setStyle(el, haloVar, s"${casingPx - ownPx}px")
      // The width goes on the halo ITSELF rather than through the property: in
      // Mermaid the halo is a sibling of its link, not a descendant, so nothing
      // would inherit it there.
      casingsOf(el).foreach(setStyle(_, "stroke-width", s"${casingPx}px"))

      val ringPx = math.min(MaxRingPx, math.max(MinRingPx, ownPx + RingDeltaPx))
      setStyle(el, ringVar, s"${ringPx}px")
      setStyle(el, nodeCasingVar, s"${casingPx}px")
      // ONE centre line for both rects: the band and the ring share it, so the
      // solid line runs down the middle of the band instead of beside it, and
      // the whole marker costs only the band's width. That line sits clear of
      // the object's OUTER edge — half its own stroke past the measured box,
      // since getBBox reports geometry and ignores stroke (a ring offset by its
      // own half-width alone landed INSIDE a 9pt border) — plus half the band.
      val out = (ownPx / 2 + casingPx / 2) * upp
      el.querySelectorAllT[dom.Element](
        s"rect.${SelectableElement.selectionRectClass}, rect.${SelectableElement.selectionCasingClass}"
      ).foreach: r =>
        for
          bx <- num(r, SelectableElement.baseBoxAttr.x)
          by <- num(r, SelectableElement.baseBoxAttr.y)
          bw <- num(r, SelectableElement.baseBoxAttr.w)
          bh <- num(r, SelectableElement.baseBoxAttr.h)
        do
          r.setAttribute("x", (bx - out).toString)
          r.setAttribute("y", (by - out).toString)
          r.setAttribute("width", (bw + 2 * out).toString)
          r.setAttribute("height", (bh + 2 * out).toString)

  /** Give a deselected element its halo back at the hit-testing width. The
    * casing leaves it either narrower (a thin edge, at the floor) or wider (a
    * heavy one), and neither should outlive the selection: one makes the edge
    * hard to pick up again, the other leaves an oversized invisible target
    * lying over its neighbours. */
  def clear(el: dom.Element): Unit =
    casingsOf(el).foreach(setStyle(_, "stroke-width", s"${HitHaloPx}px"))

  /** What the marker is standing for. A Mermaid halo carries the selected class
    * in its own right and is a SIBLING of the link it clones (inserted directly
    * before it), so it must be sized from that link, not from its own 14px.
    */
  private def subjectOf(el: dom.Element): dom.Element =
    if el.classList.contains(SelectableElement.hitAreaClass) then Option(el.nextElementSibling).getOrElse(el)
    else el

  private def casingsOf(el: dom.Element): Seq[dom.Element] =
    if el.classList.contains(SelectableElement.hitAreaClass) then Seq(el)
    else el.querySelectorAllT[dom.Element](s"path.${SelectableElement.hitAreaClass}")

  /** The screen width of the object's OWN outline — the shape the user drew,
    * never one of our decorations. Falls back to a hairline, which the floors
    * above absorb.
    */
  private def ownStrokePx(el: dom.Element, upp: Double): Double =
    val shapeSelector =
      s"${SelectableElement.splineSelector}, polygon, ellipse, " +
        s"rect:not(.${SelectableElement.selectionRectClass}), line, polyline"
    // The selected element may BE the shape (a Mermaid link is a bare path) or
    // contain it (a graphviz `g.edge`/`g.node`).
    val shape =
      if el.matches(shapeSelector) then Some(el) else Option(el.querySelector(shapeSelector))
    shape.fold(1.0): s =>
      val cs = dom.window.getComputedStyle(s)
      val w  = cs.strokeWidth.replace("px", "").toDoubleOption.getOrElse(1.0)
      // Computed stroke-width reads in USER units (with a px suffix) — unless
      // the shape opted out of the canvas scale, where it is already screen px.
      if cs.getPropertyValue("vector-effect") == "non-scaling-stroke" then w else w / upp

  /** SVGElement carries `style` at runtime; scala-js-dom does not type it. */
  private def setStyle(el: dom.Element, prop: String, value: String): Unit =
    el.asInstanceOf[js.Dynamic].style.setProperty(prop, value)

  private def num(el: dom.Element, name: String): Option[Double] =
    Option(el.getAttribute(name)).flatMap(_.toDoubleOption)
