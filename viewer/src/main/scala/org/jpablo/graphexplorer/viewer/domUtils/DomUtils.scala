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
  /** Calculates a scale factor to ensure SVG elements appear at a consistent size regardless of the viewbox/zoom level
    *
    * @param topLevelSvg
    *   The SVG element that contains the transformation
    * @param targetScreenSize
    *   Desired size in screen pixels
    * @param minSvgSize
    *   Minimum SVG units size
    * @param maxSvgSize
    *   Maximum SVG units size
    * @return
    *   The calculated size in SVG units that will appear approximately as targetScreenSize
    */
  def calculateSvgSizeForConstantScreenSize(
      topLevelSvg:      dom.svg.SVG,
      targetScreenSize: Double,
      minSvgSize:       Double = 0.5,
      maxSvgSize:       Double = 100.0 // Much higher maximum for extremely large viewBoxes
  ): Double =
    // Try to get reliable scaling information from the SVG
    // First attempt: Get the SVG's viewBox if available - this is more reliable for SVG scaling
    val viewBoxScale = getViewBoxScale(topLevelSvg)

    // Second attempt: Get scaling from the transformation matrix
    val ctmScale = getCtmScale(topLevelSvg)


    // Use the smaller scale factor (larger values in SVG units) to ensure visibility
    // This is because smaller scale means we're more zoomed out and need larger elements
    val effectiveScale = math.min(
      viewBoxScale.getOrElse(Double.MaxValue),
      ctmScale
    )

    // Calculate required size in SVG units (inverse of scale)
    // Apply an additional scaling factor for extremely small scales
    val baseSize = targetScreenSize / effectiveScale

    // Apply aggressive scaling for very zoomed out views
    // The smaller the effective scale, the larger the size adjustment
    val adjustedSize =
      if (effectiveScale < 0.01) baseSize * 3.0     // Extremely zoomed out
      else if (effectiveScale < 0.1) baseSize * 2.0 // Very zoomed out
      else if (effectiveScale < 0.5) baseSize * 1.5 // Moderately zoomed out
      else baseSize                                 // Normal zoom level

    // Apply reasonable bounds
    math.min(math.max(adjustedSize, minSvgSize), maxSvgSize)

  def calculateSimpleScale(
      ref:              dom.svg.Locatable,
      svgSize:          Double,
      targetScreenSize: Double
  ): Double =
    targetScreenSize / svgSize / getCtmScale(ref)

  /** Attempts to calculate scale factor from SVG viewBox
    */
  private def getViewBoxScale(svgElement: dom.svg.SVG): Option[Double] =
    try
      // Get viewBox dimensions if available
      val viewBox = svgElement.viewBox.baseVal
      if (viewBox != null && viewBox.width > 0)
        // Compare client width to viewBox width to get scale factor
        Some(svgElement.clientWidth / viewBox.width)
      else None
    catch
      case _: Exception => None

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
