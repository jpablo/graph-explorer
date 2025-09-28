package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.BBox

/** Base trait for interactive graph elements in the SVG canvas.
  *
  * Provides selection functionality, element identification, and DOM manipulation for nodes, edges, and clusters in the graph
  * visualization.
  *
  * @param ref
  *   The underlying SVG group element
  */
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
    ref.querySelectorAllT("g").flatMap(fromDomElement)

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

  /** Checks if this node is a record node by looking for polygon shape and port indicators */
  def isRecordNode: Boolean =
    // Record nodes have polygon elements and labels with ports (e.g., "<f0>")
    val polygons = ref.querySelectorAll("polygon")
    val text = ref.querySelector("text")
    polygons.length > 0 && text != null && text.textContent.contains("<f")

  /** Calculates which cell of a record node was clicked based on mouse position */
  def getCellAtPosition(clientX: Double, clientY: Double): Option[String] =
    if !isRecordNode then None
    else
      // Parse the text content to count fields
      val text = ref.querySelector("text")
      if text == null then None
      else
        val content = text.textContent
        // Count fields by counting port markers
        val fieldCount = "<f\\d+>".r.findAllIn(content).length
        if fieldCount == 0 then None
        else
          // Get the bounding box of the node
          val bbox = ref.getBoundingClientRect()

          // Determine if record is vertical or horizontal based on label format
          val isVertical = content.contains("{") && content.contains("}")

          if isVertical then
            // For vertical records, divide height by field count
            val cellHeight = bbox.height / fieldCount
            val relativeY = clientY - bbox.top
            val cellIndex = Math.floor(relativeY / cellHeight).toInt
            if cellIndex >= 0 && cellIndex < fieldCount then
              Some(s"f$cellIndex")
            else None
          else
            // For horizontal records, divide width by field count
            val cellWidth = bbox.width / fieldCount
            val relativeX = clientX - bbox.left
            val cellIndex = Math.floor(relativeX / cellWidth).toInt
            if cellIndex >= 0 && cellIndex < fieldCount then
              Some(s"f$cellIndex")
            else None

case class EdgeElement(private val ref0: dom.svg.G) extends SelectableElement(ref0):
  val selectedClass = "selected"

  private lazy val toArrowId: Option[ArrowId] =
    Arrow.fromSvg(svgIdAttr)

  // if parsing fails, use the title as the nodeId
  lazy val elementId: ArrowId =
    toArrowId.getOrElse(ArrowId(refTitle))

  override def select(): Unit =
    ref.classList.add(selectedClass)

  override def unselect(): Unit =
    ref.classList.remove(selectedClass)

end EdgeElement

case class ClusterElement(ref0: dom.svg.G) extends SelectableElement(ref0):
  val selectedClass = "selected"
  val elementId     = GroupId.fromSvg(svgIdAttr).getOrElse(GroupId(refTitle))

case class RecordCellElement(ref0: dom.svg.G, recordNodeId: NodeId, port: String) extends SelectableElement(ref0):
  val selectedClass = "selected"
  val elementId     = RecordCellId(recordNodeId, port)

  override def select(): Unit =
    ref.classList.add(selectedClass)
    // Add visual indicator for the specific cell
    val cellRect = getCellBoundingBox()
    cellRect.foreach { bbox =>
      val rect = dom.document.createElementNS("http://www.w3.org/2000/svg", "rect").asInstanceOf[dom.svg.RectElement]
      rect.classList.add(selectionRectClass)
      rect.classList.add("cell-selection")
      rect.setAttribute("x", bbox.x.toString)
      rect.setAttribute("y", bbox.y.toString)
      rect.setAttribute("width", bbox.width.toString)
      rect.setAttribute("height", bbox.height.toString)
      ref.appendChild(rect)
    }

  override def unselect(): Unit =
    ref.classList.remove(selectedClass)
    val rects = ref.querySelectorAll(s"rect.$selectionRectClass.cell-selection")
    for i <- 0 until rects.length do
      rects(i).remove()

  private def getCellBoundingBox(): Option[BBox] =
    val text = ref.querySelector("text")
    if text == null then None
    else
      val content = text.textContent
      val fieldCount = "<f\\d+>".r.findAllIn(content).length
      val portIndex = port.drop(1).toIntOption.getOrElse(0)

      if fieldCount == 0 || portIndex >= fieldCount then None
      else
        val bbox = ref.getBBox()
        val isVertical = content.contains("{") && content.contains("}")

        if isVertical then
          val cellHeight = bbox.height / fieldCount
          Some(BBox(
            x = bbox.x,
            y = bbox.y + (portIndex * cellHeight),
            width = bbox.width,
            height = cellHeight
          ))
        else
          val cellWidth = bbox.width / fieldCount
          Some(BBox(
            x = bbox.x + (portIndex * cellWidth),
            y = bbox.y,
            width = cellWidth,
            height = bbox.height
          ))

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
