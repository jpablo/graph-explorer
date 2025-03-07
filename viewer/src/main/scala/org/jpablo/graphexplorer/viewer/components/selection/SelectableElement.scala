package org.jpablo.graphexplorer.viewer.components.selection

import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Arrow, ElementId, NodeId}
import org.scalajs.dom
import org.scalajs.dom.Element
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils

sealed trait SelectableElement(ref: dom.SVGGElement):
  def selectedClass: String

  protected val refTitle = ref.querySelector("title").textContent
  protected val refIdAttr = ref.id

  def elementId: ElementId
  def nodeId: Option[NodeId] = elementId match { case n: NodeId => Some(n); case _ => None }
  def arrowId: Option[models.ArrowId] = elementId match { case a: models.ArrowId => Some(a); case _ => None }
  def groupId: Option[models.GroupId] = elementId match { case g: models.GroupId => Some(g); case _ => None }

  val get = ref
  val selectionRectClass = "selected-border"

  def selectedRect() =
    val bbox = ref.getBBox()
    val pixelPadding = 2
    val paddingScale = SvgUtils.calculateSimpleScale(ref, svgSize = 1, clientSize = pixelPadding)
    val svgPadding = pixelPadding * paddingScale
    val strokeW = 1.5 * paddingScale

    val rect = dom.document.createElementNS("http://www.w3.org/2000/svg", "rect").asInstanceOf[dom.SVGRectElement]

    val rectX = bbox.x - svgPadding - strokeW
    val rectY = bbox.y - svgPadding - strokeW
    val rectW = bbox.width + ((svgPadding + strokeW) * 2)
    val rectH = bbox.height + ((svgPadding + strokeW) * 2)

    rect.setAttribute("x", rectX.toString)
    rect.setAttribute("y", rectY.toString)
    rect.setAttribute("width", rectW.toString)
    rect.setAttribute("height", rectH.toString)
    rect.setAttribute("stroke-width", strokeW.toString)

    val rScale = SvgUtils.calculateSimpleScale(ref, svgSize = 1, clientSize = 5)
    rect.setAttribute("rx", rScale.toString)
    rect.setAttribute("ry", rScale.toString)
    rect.classList.add(selectionRectClass)
    rect

  def select(): Unit =
    ref.classList.add(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect == null then
      ref.appendChild(selectedRect())

  def unselect(): Unit =
    ref.classList.remove(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect != null then
      rect.remove()

  def toggle(): Unit =
    if ref.classList.contains(selectedClass) then unselect()
    else select()

object SelectableElement:

  def fromDomElement(e: dom.Element): Option[SelectableElement] =
    if isDiagramElement(e, "node") then Some(NodeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "edge") then Some(EdgeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "cluster") then Some(ClusterElement(e.asInstanceOf[dom.SVGGElement]))
    else None

  def findAll(e: dom.Element): Seq[SelectableElement] =
    e.querySelectorAll("g").flatMap(fromDomElement).toSeq

  private def isDiagramElement(e: dom.Element, cls: String) =
    e.tagName == "g" && e.classList.contains(cls)

end SelectableElement

case class NodeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"
  val elementId: NodeId = models.NodeId(refTitle)

case class EdgeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"

  lazy val toArrow: Option[Arrow] =
    Arrow.fromGraphvizTitle(refTitle, refIdAttr)

  // if parsing fails, use the title as the nodeId
  lazy val elementId =
    toArrow.map(a => a.id).getOrElse(models.ArrowId(refTitle))
end EdgeElement

case class ClusterElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"
  val elementId = models.GroupId(refTitle)

// ------------------------------
// dom.Element extensions
// ------------------------------

extension (e: dom.Element)
  def parentNodes: LazyList[Element] =
    e +: LazyList.unfold(e)(e => Option(e.parentNode.asInstanceOf[dom.Element]).map(e => (e, e)))

  def styleMap: Map[String, String] =
    styleToMap(e.getAttribute("style"))

  private def mapToStyle(m: Map[String, String]): String =
    m.map(_ + ":" + _).mkString(";")

  private def styleToMap(style: String | Null): Map[String, String] =
    if style == null || style.isEmpty
    then Map.empty
    else
      style
        .split(";")
        .filterNot(_.isEmpty)
        .map: str =>
          val arr = str.split(":")
          arr.head -> arr.tail.headOption.getOrElse("")
        .toMap

  def replaceStyle(keyValues: (String, String)*): Unit =
    e.setAttribute("style", mapToStyle(keyValues.toMap))

  def updateStyle(keyValues: (String, String)*): Unit =
    e.setAttribute("style", mapToStyle(e.styleMap ++ keyValues.toMap))

  def removeStyle(styleName: String): Unit =
    replaceStyle((e.styleMap - styleName).toList*)
