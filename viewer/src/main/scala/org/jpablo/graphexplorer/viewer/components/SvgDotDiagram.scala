package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.SvgDotDiagram.{BBox, selfContainedSvg}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.ViewerState.toSVGCoords
import org.scalajs.dom
import org.scalajs.dom.{SVGGElement, SVGPoint, SVGRectElement, SVGSVGElement}

import scala.scalajs.js
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps

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
  def asSVGPair(svgElement: SVGSVGElement): (SVGPoint, SVGPoint) =
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

class SvgDotDiagram(svgElement: ReactiveSvgElement[dom.SVGSVGElement]):

  val ref = svgElement.ref
  def size = (ref.width.baseVal.value, ref.height.baseVal.value)

  ref.setAttribute("class", "graphviz")
  // graphviz adds a polygon as diagram background
//  val n = ref.querySelector("g > polygon[fill='white']")
//  if n != null then n.parentNode.removeChild(n)
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
  def empty = SvgDotDiagram(svg.svg(svg.width := "0px", svg.height := "0px", svg.g()))

  case class BBox(x: Double, y: Double, width: Double, height: Double)

  def svgWithTransform(
      transform:  Signal[String],
      startNode:  Signal[Option[(models.NodeId, Point2d[Double])]],
      endPos:     Signal[Point2d[Double]],
      isDragging: Signal[Boolean],
      selectionRect: Signal[Option[SelectionRect]],
      diagramSelection: DiagramSelectionOps,
  )(svgElement: dom.SVGSVGElement): ReactiveSvgElement[dom.SVGSVGElement] =
    val firstGroup: dom.svg.G =
      val g0 = svgElement.querySelector("g")
      (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

    val (gX, gY) = getTranslate(firstGroup)
    val translatedGroup = foreignSvgElement(firstGroup).amend(svg.transform <-- transform)
    val viewBox = svgElement.viewBox.baseVal
    val box = BBox(viewBox.x - gX.value, viewBox.y - gY.value, viewBox.width, viewBox.height)
    selfContainedSvg(
      box,
      translatedGroup,
      onMountCallback { ctx =>
        import ctx.owner
        // change style of elements intersecting selectionRec
        selectionRect.foreach: (maybeRect: Option[SelectionRect]) =>
          maybeRect.foreach: rect =>
            val nodesInRect = SelectableElement.findAll(ctx.thisNode.ref)
              .filter(elem => isNodeInRect(elem, rect))
              .map(_.nodeId)
              .toSet
            
            if nodesInRect.nonEmpty then
              if rect.shift then
                diagramSelection.add(nodesInRect)
              else
                diagramSelection.set(nodesInRect)
            else if !rect.shift then
              diagramSelection.clear()
      },
      inContext { thisNode =>
        val ref = thisNode.ref
        val startPosClient = startNode.map(_.map((nodeId, p) => (nodeId, toSVGCoords(p.x, p.y, ref))))
        val endPosClient = endPos.map(p => toSVGCoords(p.x, p.y, ref))

        // child(DraggingArrow(startPosClient, endPosClient)) <-- isDragging,
        child.maybe <-- DrawSelectionRect(selectionRect, ref)
      },

    inContext: thisNode =>
      // change the style of selected elements
      diagramSelection.signal --> { selectedNodes =>
        for elem <- SelectableElement.findAll(thisNode.ref) do
          if elem.nodeId in selectedNodes then
            elem.select()
          else
            elem.unselect()
      }

    )

  private def isNodeInRect(elem: SelectableElement, rect: SelectionRect): Boolean =
    val bbox = elem.get.getBoundingClientRect()
    val normalizedRect = (
      x = rect.startX.min(rect.endX),
      y = rect.startY.min(rect.endY),
      width = math.abs(rect.endX - rect.startX),
      height = math.abs(rect.endY - rect.startY)
    )
    !(bbox.right < normalizedRect.x ||
      bbox.left > normalizedRect.x + normalizedRect.width ||
      bbox.bottom < normalizedRect.y ||
      bbox.top > normalizedRect.y + normalizedRect.height)

  private def selfContainedSvg(
      viewBox: BBox,
      elems:   Modifier[ReactiveSvgElement[dom.SVGSVGElement]]*
  ): ReactiveSvgElement[SVGSVGElement] =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
//      svg.cls        := "graphviz no-text-select", // what happens with this class? (it is ignored)
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

  private def DrawSelectionRect(rect: Signal[Option[SelectionRect]], svgElement: SVGSVGElement): Signal[Option[ReactiveSvgElement[SVGRectElement]]] =
    rect.map:
      _.map:
        selectionRect =>
          val (p0, p1) = selectionRect.asSVGPair(svgElement)
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x := p0.x.min(p1.x).toString,
            svg.y := p0.y.min(p1.y).toString,
            svg.width := math.abs(p1.x - p0.x).toString,
            svg.height := math.abs(p1.y - p0.y).toString,
          )


  private def DraggingArrow(
      startNode: Signal[Option[(models.NodeId, SVGPoint)]],
      endPos:    Signal[SVGPoint]
  ): ReactiveSvgElement[SVGGElement] =
    // Define start and end position signals
    val startX = startNode.map {
      case Some((_, start)) => start.x.toString
      case None             => 0.0.toString
    }
    val startY = startNode.map {
      case Some((_, start)) => start.y.toString
      case None             => 0.0.toString
    }
    val endX = endPos.map(_.x.toString)
    val endY = endPos.map(_.y.toString)
    svg.g(
      svg.idAttr := "dragging-arrow-group",
      // Temporary line for dragging
      svg.line(
        svg.idAttr := "dragging-arrow-line",
        svg.x1 <-- startX,
        svg.y1 <-- startY,
        svg.x2 <-- endX,
        svg.y2 <-- endY
      ),
      // Circle at the start of the line
      svg.circle(
        svg.idAttr := "dragging-arrow-start-circle",
        svg.r      := "1",
        svg.cx <-- startX,
        svg.cy <-- startY
      ),
      // Circle at the end of the line
      svg.circle(
        svg.idAttr := "dragging-arrow-end-circle",
        svg.r      := "1",
        svg.cx <-- endX,
        svg.cy <-- endY
      )
    )
