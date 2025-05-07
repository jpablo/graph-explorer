package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.BBox

sealed trait SelectableElement(val ref: dom.svg.G):
  def selectedClass: String

  protected val refTitle = ref.querySelector("title").textContent
  // example: <g id="edge:id"> ...
  protected val svgIdAttr = ref.id

  def elementId: ElementId
  def nodeId: Option[NodeId]          = elementId match { case n: NodeId => Some(n); case _ => None }
  def arrowId: Option[models.ArrowId] = elementId match { case a: models.ArrowId => Some(a); case _ => None }
  def groupId: Option[models.GroupId] = elementId match { case g: models.GroupId => Some(g); case _ => None }

  val selectionRectClass = "selected-border"

  def unselect(): Unit =
    ref.classList.remove(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect != null then
      rect.remove()

  def select(): Unit =
    ref.classList.add(selectedClass)
    val rect = ref.querySelector(s"rect.$selectionRectClass")
    if rect == null then
      ref.appendChild(SelectedRect().ref)

  private def SelectedRect() =
    val bbox = ref.getBBox()
    svg.rect(
      svg.cls    := selectionRectClass,
      svg.x      := bbox.x.toString,
      svg.y      := bbox.y.toString,
      svg.width  := bbox.width.toString,
      svg.height := bbox.height.toString
    )

object SelectableElement:

  def fromDomElement(e: dom.svg.G): Option[SelectableElement] =
    if e.classList.contains("node") then Some(NodeElement(e))
    else if e.classList.contains("edge") then Some(EdgeElement(e))
    else if e.classList.contains("cluster") then Some(ClusterElement(e))
    else None

  def findAll(ref: dom.Element): Seq[SelectableElement] =
    ref.querySelectorAllT[dom.svg.G]("g").flatMap(fromDomElement)

  def query(ref: dom.Element, elems: ElementIds): Seq[SelectableElement] =
    if elems.isEmpty then
      Seq.empty
    else
      ref
        .querySelectorAllT[dom.svg.G](elems.ids.map(id => s"g[id='${id.toSvg}']").mkString(","))
        .flatMap(fromDomElement)

end SelectableElement

case class NodeElement(ref0: dom.svg.G) extends SelectableElement(ref0):
  val selectedClass = "selected"
  val elementId     = NodeId(refTitle)

case class EdgeElement(private val ref0: dom.svg.G) extends SelectableElement(ref0):
  val selectedClass = "selected"

  private lazy val toArrowId: Option[ArrowId] =
    Arrow.fromSvg(svgIdAttr)

  // if parsing fails, use the title as the nodeId
  lazy val elementId: ArrowId =
    toArrowId.getOrElse(ArrowId(refTitle))

end EdgeElement

case class ClusterElement(ref0: dom.svg.G) extends SelectableElement(ref0):
  val selectedClass = "selected"
  val elementId     = GroupId.fromSvg(svgIdAttr).getOrElse(GroupId(refTitle))

// ------------------------------
// dom.Element extensions
// ------------------------------

extension (e: dom.Element)
  def parentNodes: LazyList[dom.Element] =
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

def getUnionBBox(bboxes: Seq[dom.SVGRect]): BBox =
  // Initialize with extreme values in opposite directions
  var minX = Double.PositiveInfinity
  var minY = Double.PositiveInfinity
  var maxX = Double.NegativeInfinity
  var maxY = Double.NegativeInfinity

  for bbox <- bboxes do
    minX = Math.min(minX, bbox.x)
    minY = Math.min(minY, bbox.y)
    maxX = Math.max(maxX, bbox.x + bbox.width)
    maxY = Math.max(maxY, bbox.y + bbox.height)

  BBox(
    x = minX,
    y = minY,
    width = maxX - minX,
    height = maxY - minY
  )
