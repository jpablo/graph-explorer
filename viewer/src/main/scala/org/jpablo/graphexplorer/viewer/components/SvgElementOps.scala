package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.DOMPoint
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint, SvgPoint, UserActionRect}

extension (clientPoint: ClientPoint)
  def toSvgPoint(screenCtm: dom.SVGMatrix): SvgPoint =
    val DOMPoint = new DOMPoint(clientPoint.x, clientPoint.y).matrixTransform(screenCtm.inverse())
    SvgPoint(DOMPoint.x, DOMPoint.y)

extension (rect: UserActionRect)
  def toSvgPair(screenCtm: dom.SVGMatrix): (SvgPoint, SvgPoint) =
    (rect.start.toSvgPoint(screenCtm), rect.end.toSvgPoint(screenCtm))

class SvgElementOps(val ref: dom.SVGSVGElement):

  def size = (ref.width.baseVal.value, ref.height.baseVal.value)

  ref.setAttribute("class", "graphviz")
  ref.removeAttribute("style")

  // ------------------

  private def selectableElements =
    SelectableElement.findAll(ref)

//  def select(ids: Set[models.NodeId]): Unit =
//    for elem <- selectableElements if elem.elementId in ids do elem.select()

  private def buildSvgElement(elem: SelectableElement): (dom.svg.Element, BBox) =
    // Clone the element to avoid modifying the original
    val e = DomApi.unsafeParseSvgString(elem.ref.outerHTML)
    // Remove the selected border from the cloned element
    val selectedBorders = e.querySelectorAll(".selected-border")
    for (node <- selectedBorders) do
      node.parentNode.removeChild(node)
    val bbox = elem.ref.getBBox()
    (e, BBox(bbox.x, bbox.y, bbox.width, bbox.height))

  def toSVGTextWithIds(ids: ElementIds): String =
    if (ids.isEmpty) ""
    else
      val (svgs, boxes) = SelectableElement.findAll(ref).filter(_.elementId in ids).map(buildSvgElement).unzip
      val bbox = boxes.reduce((a, b) =>
        val x      = a.x min b.x
        val y      = a.y min b.y
        val width  = a.width max (b.x + b.width - x)
        val height = ((a.y + a.height) max (b.y + b.height)) - y
        BBox(x, y, width, height)
      )
      val s = svgCanvas.emptySvg(bbox, svgs.map(foreignSvgElement))
      s.ref.outerHTML

object SvgElementOps:
  def empty = SvgElementOps(svg.svg(svg.width := "0px", svg.height := "0px", svg.g()).ref)
