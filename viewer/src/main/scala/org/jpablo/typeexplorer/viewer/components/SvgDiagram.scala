package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.DomApi
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.components.svgGroupElement.{ClusterElement, LinkElement, NamespaceElement}
import org.scalajs.dom

class SvgDiagram(svgElement: dom.SVGSVGElement):
  export svgElement.querySelector

  val origW = svgElement.width.baseVal.value
  val origH = svgElement.height.baseVal.value

  svgElement.removeAttribute("style")
  svgElement.removeAttribute("width")
  svgElement.removeAttribute("height")

  private def selectableElements =
    NamespaceElement.selectAll(svgElement) ++ LinkElement.selectAll(svgElement)

  private def namespaceElements =
    NamespaceElement.selectAll(svgElement)

  def clusterElements(cluster: ClusterElement) =
    namespaceElements.filter(_.id.startsWith(cluster.idWithSlashes))

  def clusters =
    ClusterElement.selectAll(svgElement)

  def elementSymbols: Set[models.ViewerNodeId] =
    namespaceElements.map(_.symbol).toSet

  def select(symbols: Set[models.ViewerNodeId]): Unit =
    for elem <- selectableElements if symbols.contains(elem.symbol) do elem.select()

  def unselectAll(): Unit =
    selectableElements.foreach(_.unselect())

  def toLaminar =
    foreignSvgElement(svgElement)

  def toSVGText: String =
    svgElement.outerHTML

  case class BBox(x: Double, y: Double, width: Double, height: Double)

  private def buildSvgElement(id: models.ViewerNodeId) =
    val el =
      getElementById("elem_" + id.toString()).asInstanceOf[dom.SVGSVGElement]
    val e = DomApi.unsafeParseSvgString(el.outerHTML)
    val bbox = el.getBBox()
    (e, BBox(bbox.x, bbox.y, bbox.width, bbox.height))

  def toSVGText(ids: Set[models.ViewerNodeId]): String =
    if (ids.isEmpty) ""
    else
      val (svgs, boxes) = ids.map(buildSvgElement).unzip
      val bbox = boxes.reduce((a, b) =>
        val x = math.min(a.x, b.x)
        val y = math.min(a.y, b.y)
        val width = math.max(a.width, (b.x + b.width) - x)
        val height = math.max(a.height, (b.y + b.height) - y)
        BBox(x, y, width, height)
      )
      val s = svg.svg(
        svg.viewBox := s"${bbox.x} ${bbox.y} ${bbox.width} ${bbox.height}",
        svgs.map(foreignSvgElement).toList
      )
      s.ref.outerHTML

  def getElementById(id: String): dom.Element =
    svgElement.querySelector(s"[id='$id']")

object SvgDiagram:
  val empty = SvgDiagram(svg.svg(svg.width := "0px", svg.height := "0px").ref)
