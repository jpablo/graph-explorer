package org.jpablo.graphexplorer.viewer.domUtils

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.{BooleanAsAttrPresenceCodec, StringAsIsCodec}
import org.jpablo.graphexplorer.viewer.utils.SvgPoint
import org.scalajs.dom.HTMLDialogElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

val details = htmlTag("details")
val summary = htmlTag("summary")
val dialog = htmlTag[HTMLDialogElement]("dialog")

val open = htmlAttr("open", BooleanAsAttrPresenceCodec)
val dataTip = htmlAttr("data-tip", StringAsIsCodec)
val dataTabId = htmlAttr("data-tab-id", StringAsIsCodec)
val name = htmlAttr("name", StringAsIsCodec)
val ariaLabel = htmlAttr("aria-label", StringAsIsCodec)

val autocomplete = htmlProp("autocomplete", StringAsIsCodec)

val gridColumn = styleProp("grid-column")

extension (doc: dom.HTMLDocument)
  def elementsFromPoint(x: Double, y: Double): js.Array[dom.Element] =
    doc.asInstanceOf[js.Dynamic]
      .elementsFromPoint(x, y)
      .asInstanceOf[js.Array[dom.Element]]

@js.native
@JSGlobal
class DOMPoint(val x: Double = 0, val y: Double = 0, val z: Double = 0, val w: Double = 0) extends js.Object {
  def matrixTransform(matrix: dom.SVGMatrix): DOMPoint = js.native
}

object DOMPoint:
  extension (p:    DOMPoint)
    def ===(other: DOMPoint): Boolean = p.x == other.x && p.y == other.y

/** Utilities for SVG coordinate and size conversions
  */
object SvgUtils:

  /** Converts a point from screen coordinates to SVG coordinates
    *
    * Screen pixels → SVG units: Multiply by the scale factor
    *
    * SVG units → Screen pixels: Divide by the scale factor
    */
  def calculateSimpleScale(
      ref:        dom.svg.Locatable,
      svgSize:    Double,
      clientSize: Double // pixels
  ): Double =
    clientSize / svgSize / getCtmScale(ref)

  /** Calculates scale factor from the Current Transformation Matrix
    */
  def getCtmScale(svgElement: dom.svg.Locatable): Double =
    try
      val ctm = svgElement.getScreenCTM()
      if (ctm != null)
        math.abs(ctm.a) // `a` is the horizontal scale for the CTM
      else 1
    catch
      case _: Exception => 1

  /** Gets the x,y translation values from an SVG group element's transform, or (0,0) if none exists
    */
  def getTranslate(g: dom.svg.G): SvgPoint =
    if js.isUndefined(g.transform) then SvgPoint.origin
    else
      val transformList = g.transform.baseVal
      val transformPoints =
        for
          i <- 0 until transformList.numberOfItems
          transform = transformList.getItem(i)
          // https://developer.mozilla.org/en-US/docs/Web/API/SVGTransform/matrix
          if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
        yield SvgPoint(transform.matrix.e, transform.matrix.f)

      transformPoints.headOption.getOrElse(SvgPoint.origin)
