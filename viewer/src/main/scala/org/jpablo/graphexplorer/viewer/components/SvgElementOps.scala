package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.ViewerState.toSVGCoords
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.domUtils.DOMPoint
import org.jpablo.graphexplorer.viewer.utils.{MathOps, Point2d}



enum Action:
  case Area(rect: UserActionRect)
  case Line(rect: UserActionRect, start: SelectableElement)

case class UserActionRect(
  startX: Double, // in client space
  startY: Double, // in client space
    endX: Double, // in client space
    endY: Double, // in client space
    shift: Boolean
):
  def asSVGPair(screenCtm: dom.SVGMatrix): (DOMPoint, DOMPoint) =
    val p0 = toSVGCoords(startX, startY, screenCtm)
    val p1 = toSVGCoords(endX, endY, screenCtm)
    (p0, p1)

  def isEmpty: Boolean = startX == endX && startY == endY

case class SvgUnit(value: Double) extends AnyVal:
  override def toString: String = value.toString

object SvgUnit:
  val origin: Point2d[SvgUnit] = (SvgUnit(0.0), SvgUnit(0.0))

  given MathOps[SvgUnit]:
    extension (a: SvgUnit)
      def -(b: SvgUnit): SvgUnit = SvgUnit(a.value - b.value)
      def *(z: SvgUnit): SvgUnit = SvgUnit(a.value * z.value)


case class BBox(x: Double, y: Double, width: Double, height: Double)

class SvgElementOps(val ref: dom.SVGSVGElement):

  def size = (ref.width.baseVal.value, ref.height.baseVal.value)

  ref.setAttribute("class", "graphviz")
  ref.removeAttribute("style")

  // ------------------

  private def selectableElements =
    SelectableElement.findAll(ref)

  def select(ids: Set[models.NodeId]): Unit =
    for elem <- selectableElements if elem.nodeId in ids do elem.select()

  private def buildSvgElement(elem: SelectableElement): (dom.svg.Element, BBox) =
    // Clone the element to avoid modifying the original
    val e = DomApi.unsafeParseSvgString(elem.get.outerHTML)
    // Remove the selected border from the cloned element
    val selectedBorders = e.querySelectorAll(".selected-border")
    for (node <- selectedBorders) do
      node.parentNode.removeChild(node)
    val bbox = elem.get.getBBox()
    (e, BBox(bbox.x, bbox.y, bbox.width, bbox.height))

  def toSVGTextWithIds(ids: Set[models.NodeId]): String =
    if (ids.isEmpty) ""
    else
      val (svgs, boxes) = SelectableElement.findAll(ref).filter(_.nodeId in ids).map(buildSvgElement).unzip
      val bbox = boxes.reduce((a, b) =>
        val x = a.x min b.x
        val y = a.y min b.y
        val width = a.width max (b.x + b.width - x)
        val height = ((a.y + a.height) max (b.y + b.height)) - y
        BBox(x, y, width, height)
      )
      val s = SvgCanvas.selfContainedSvg(bbox).amend(svgs.map(foreignSvgElement)*)
      s.ref.outerHTML

object SvgElementOps:
  def empty = SvgElementOps(svg.svg(svg.width := "0px", svg.height := "0px", svg.g()).ref)

