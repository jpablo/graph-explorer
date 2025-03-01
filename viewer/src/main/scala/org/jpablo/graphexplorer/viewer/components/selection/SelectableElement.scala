package org.jpablo.graphexplorer.viewer.components.selection

import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.scalajs.dom
import org.scalajs.dom.Element

sealed trait SelectableElement(ref: dom.SVGGElement):
  def selectedClass: String

  protected val refTitle = ref.querySelector("title").textContent
  protected val refIdAttr = ref.id

  def nodeId: NodeId

  val get = ref
  val selectionRectClass = "selected-border"

  def selectedRect() =
    val bbox = ref.getBBox()
    // Get SVG element and its viewBox
    val svgElement = ref.closest("svg").asInstanceOf[dom.SVGSVGElement]
    val extra = 2
    // Calculate a stroke width that remains visually consistent at different zoom levels
    // by accounting for the SVGs transformation to screen coordinates
    val svgScreenCTM = svgElement.getScreenCTM()
    // Get the scaling factor from the transformation matrix
    val (scaleX, scaleY) = if svgScreenCTM != null then (svgScreenCTM.a, svgScreenCTM.d) else (1.0, 1.0)
    // Calculate average scale and use inverse to get a width that appears constant
    val avgScale = (scaleX + scaleY) / 2
    val desiredScreenWidth = 1.5 // Desired width in screen pixels
    // For very large graphs (small scale), ensure we meet the minimum screen pixel width
    val minRequiredSvgWidth = desiredScreenWidth / avgScale
    // Apply reasonable bounds for the SVG stroke width
    val maxSvgWidth = 10.0 // Increased max for very large graphs with small scale factors
    val finalStrokeWidth = math.min(math.max(minRequiredSvgWidth, 0.5), maxSvgWidth)
    // Calculate and log the actual pixel width on screen
    // val actualPixelWidth = finalStrokeWidth * avgScale
    // println(s"SVG stroke width: $finalStrokeWidth units")
    // println(s"Screen pixel width: $actualPixelWidth pixels")
    // println(s"Scale factor: $avgScale")

    val rect = dom.document.createElementNS("http://www.w3.org/2000/svg", "rect")
    rect.setAttribute("x", (bbox.x - extra).toString)
    rect.setAttribute("y", (bbox.y - extra).toString)
    rect.setAttribute("width", (bbox.width + extra * 2).toString)
    rect.setAttribute("height", (bbox.height + extra * 2).toString)
    rect.setAttribute("stroke-width", finalStrokeWidth.toString)
    rect.setAttribute("rx", "3")
    rect.setAttribute("ry", "3")
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
  val nodeId: NodeId = models.NodeId(refTitle)

case class EdgeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"

  lazy val toArrow: Option[Arrow] =
    Arrow.fromGraphvizTitle(refTitle, refIdAttr)

  // if parsing fails, use the title as the nodeId
  lazy val nodeId: NodeId =
    toArrow.map(_.id).getOrElse(models.NodeId(refTitle))
end EdgeElement

case class ClusterElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"
  val nodeId: NodeId = models.NodeId(refTitle)

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
