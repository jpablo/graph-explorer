package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.SvgDotDiagram.{BBox, selfContainedSvg}
import org.jpablo.graphexplorer.viewer.components.selectable.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models
import org.scalajs.dom
import org.scalajs.dom.{SVGRect, SVGSVGElement}

import scala.scalajs.js

trait MathOps[A]:
  extension (a: A)
    def -(b: A): A
    def *(z: A): A

type Point2d[A] = (x: A, y: A)

extension [A](a: Point2d[A])(using MathOps[A])
  def -(b: Point2d[A]): Point2d[A] = (x = a.x - b.x, y = a.y - b.y)
  def *(b: A): Point2d[A] = (a.x * b, a.y * b)

case class SvgUnit(value: Double) extends AnyVal:
  override def toString: String = value.toString

object SvgUnit:
  val origin: Point2d[SvgUnit] = (SvgUnit(0.0), SvgUnit(0.0))

  given MathOps[SvgUnit] with
    extension (a: SvgUnit)
      def -(b: SvgUnit): SvgUnit = SvgUnit(a.value - b.value)
      def *(z: SvgUnit): SvgUnit = SvgUnit(a.value * z.value)

class SvgDotDiagram(svgElement: ReactiveSvgElement[dom.SVGSVGElement]):

  val ref = svgElement.ref
  def size = (ref.width.baseVal.value, ref.height.baseVal.value)

  ref.setAttribute("class", "graphviz")
  // graphviz adds a polygon as diagram background
  val n = ref.querySelector("g > polygon[fill='white']")
  if n != null then n.parentNode.removeChild(n)
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
      val s = selfContainedSvg(bbox, svgs.map(foreignSvgElement).toSeq*)
      s.ref.outerHTML

object SvgDotDiagram:
  val empty = SvgDotDiagram(svg.svg(svg.width := "0px", svg.height := "0px", svg.g()))

  case class BBox(x: Double, y: Double, width: Double, height: Double)

  def svgWithTransform(transform: Signal[String])(svgElement: dom.SVGSVGElement)
      : ReactiveSvgElement[dom.SVGSVGElement] =
    val firstGroup: dom.svg.G =
      val g0 = svgElement.querySelector("g")
      (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

    val g: dom.svg.G = firstGroup
    val elem = foreignSvgElement(g).amend(svg.transform <-- transform)
    val (gX, gY) = getTranslate(g)
    val viewBox: SVGRect = svgElement.viewBox.baseVal
    selfContainedSvg(BBox(viewBox.x - gX.value, viewBox.y - gY.value, viewBox.width, viewBox.height), elem)

  private def selfContainedSvg(viewBox: BBox, elems: ReactiveSvgElement[dom.svg.Element]*) =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
      svg.cls        := "graphviz",
      elems
    )

  private def getTranslate(g: dom.svg.G): Point2d[SvgUnit] =
    if js.isUndefined(g.transform) then SvgUnit.origin
    else
      val transformList = g.transform.baseVal
      (for {
        i <- 0 until transformList.numberOfItems
        transform = transformList.getItem(i)
        if transform.`type` == dom.svg.Transform.SVG_TRANSFORM_TRANSLATE
      } yield (SvgUnit(transform.matrix.e), SvgUnit(transform.matrix.f))).headOption
        .getOrElse(SvgUnit.origin)
