package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.ViewerState.toSVGCoords
import org.scalajs.dom


trait MathOps[A]:
  extension (a: A)
    def -(b: A): A
    def *(z: A): A

type Point2d[A] = (x: A, y: A)

case class SelectionRect(
  startX: Double,
  startY: Double,
    endX: Double,
    endY: Double,
    shift: Boolean,
):
  def asSVGPair(svgElement: dom.SVGSVGElement): (dom.SVGPoint, dom.SVGPoint) =
    val p0 = toSVGCoords(startX, startY, svgElement)
    val p1 = toSVGCoords(endX, endY, svgElement)
    (p0, p1)

extension [A](a: Point2d[A])(using MathOps[A])
  def -(b: Point2d[A]): Point2d[A] = (x = a.x - b.x, y = a.y - b.y)
  def *(b: A): Point2d[A] = (a.x * b, a.y * b)

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

  def toSVGText: String =
    ref.outerHTML

  private def buildSvgElement(elem: SelectableElement): (dom.svg.Element, BBox) =
    val e = DomApi.unsafeParseSvgString(elem.get.outerHTML)
    val bbox = elem.get.getBBox()
    (e, BBox(bbox.x, bbox.y, bbox.width, bbox.height))

  def toSVGTextWithIds(ids: Set[models.NodeId]): String =
    if (ids.isEmpty) ""
    else
      val (svgs, boxes) = SelectableElement.findAll(ref).filter(_.nodeId in ids).map(buildSvgElement).unzip
      val bbox = boxes.reduce((a, b) =>
        val x = math.min(a.x, b.x)
        val y = math.min(a.y, b.y)
        val width = math.max(a.width, (b.x + b.width) - x)
        val height = math.max(a.height, (b.y + b.height) - y)
        BBox(x, y, width, height)
      )
      val s = SvgCanvas.selfContainedSvg(bbox, svgs.map(foreignSvgElement).toSeq*)
      s.ref.outerHTML

object SvgElementOps:
  def empty = SvgElementOps(svg.svg(svg.width := "0px", svg.height := "0px", svg.g()).ref)

