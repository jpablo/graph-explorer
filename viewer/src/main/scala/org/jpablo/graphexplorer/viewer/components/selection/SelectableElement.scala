package org.jpablo.graphexplorer.viewer.components.selection

import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.BBox
import org.scalajs.dom
import org.scalajs.dom.{Element, FocusEvent, KeyValue}

import scala.scalajs.js

sealed trait SelectableElement(val ref: dom.SVGGElement):
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
      ref.appendChild(selectedRect())

  /** Replaces the `<text>` elements with a `<textArea>` for inline editing. When the user presses Enter, it updates the label.
    */
  def installEditor(
      updateLabel:  (ElementId, String) => Unit,
      clearEditing: () => Unit,
      label:        String
  ): Unit =
    val polygon      = ref.querySelector("polygon").asInstanceOf[dom.SVGPolygonElement]
    val groupBBox    = (if polygon == null then ref else polygon).getBBox()
    val textElements = ref.querySelectorAll("text").toSeq.map(_.asInstanceOf[dom.SVGTextElement])
    val bBox =
      if textElements.isEmpty then
        BBox(groupBBox.x, groupBBox.y, groupBBox.width, groupBBox.height)
      else
        val textBBox = getUnionBBox(textElements.map(_.getBBox()))
        BBox(groupBBox.x, textBBox.y, groupBBox.width, textBBox.height)

    val (fo, input) = buildInputElement(bBox)

    lazy val blurHandler: js.Function1[dom.FocusEvent, Unit] =
      _ => restoreOriginalText()

    def restoreOriginalText(): Unit =
      input.removeEventListener("blur", blurHandler)
      fo.remove()
      textElements.foreach(te => ref.appendChild(te))
      clearEditing()

    input.value = TextUtils.unescape(label)

    input.addEventListener("blur", blurHandler)

    input.onkeydown = (event: dom.KeyboardEvent) => {
      event.stopPropagation()
      event.key match
        case KeyValue.Enter if !event.shiftKey =>
          event.preventDefault()
          restoreOriginalText()
          updateLabel(elementId, input.value)
        case KeyValue.Escape =>
          event.preventDefault()
          input.blur()
        case _ =>
    }
    // --------------------

    textElements.foreach(_.remove())
    ref.appendChild(fo)
    // Focus the input element automatically, slightly delayed
    dom.window.setTimeout(() => { input.focus(); input.select() }, 0)
  end installEditor

  private def buildInputElement(bbox: BBox) =
    val xMargin = 2
    val fo      = dom.document.createElementNS("http://www.w3.org/2000/svg", "foreignObject")
    fo.setAttribute("x", (bbox.x + xMargin).toString) // Center approximately
    fo.setAttribute("y", bbox.y.toString)             // Adjust for input height
    fo.setAttribute("width", (bbox.width - 2 * xMargin).toString)
    fo.setAttribute("height", bbox.height.toString)
    fo.classList.add("editable-text-fo") // Add class for easier selection/removal

    val input = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
    input.classList.add("inline-input")
    fo.appendChild(input)
    (fo, input)

  private def selectedRect() =
    val bbox         = ref.getBBox()
    val pixelPadding = 2
    val paddingScale = SvgUtils.calculateSimpleScale(ref, svgSize = 1, clientSize = pixelPadding)
    val svgPadding   = pixelPadding * paddingScale
    val strokeW      = 1.5 * paddingScale

    val rect = dom.document.createElementNS("http://www.w3.org/2000/svg", "rect").asInstanceOf[dom.SVGRectElement]

    val rectX  = bbox.x - svgPadding - strokeW
    val rectY  = bbox.y - svgPadding - strokeW
    val rectW  = bbox.width + ((svgPadding + strokeW) * 2)
    val rectH  = bbox.height + ((svgPadding + strokeW) * 2)
    val rScale = SvgUtils.calculateSimpleScale(ref, svgSize = 1, clientSize = 5)

    rect.setAttribute("x", rectX.toString)
    rect.setAttribute("y", rectY.toString)
    rect.setAttribute("width", rectW.toString)
    rect.setAttribute("height", rectH.toString)
    rect.setAttribute("stroke-width", strokeW.toString)
    rect.setAttribute("rx", rScale.toString)
    rect.setAttribute("ry", rScale.toString)
    rect.classList.add(selectionRectClass)
    rect

object SelectableElement:

  def fromDomElement(e: dom.Element): Option[SelectableElement] =
    if isDiagramElement(e, "node") then Some(NodeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "edge") then Some(EdgeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "cluster") then Some(ClusterElement(e.asInstanceOf[dom.SVGGElement]))
    else None

  def findAll(ref: dom.Element): Seq[SelectableElement] =
    ref.querySelectorAll("g").flatMap(fromDomElement).toSeq

  def query(ref: dom.Element, elems: ElementIds): Seq[SelectableElement] =
    if elems.isEmpty then
      Seq.empty
    else
      ref.querySelectorAll(elems.ids.map(id => s"g[id='${id.toSvg}']").mkString(",")).flatMap(fromDomElement).toSeq

  private def isDiagramElement(e: dom.Element, cls: String) =
    e.tagName == "g" && e.classList.contains(cls)

end SelectableElement

case class NodeElement(ref0: dom.SVGGElement) extends SelectableElement(ref0):
  val selectedClass     = "selected"
  val elementId: NodeId = models.NodeId(refTitle)

case class EdgeElement(ref0: dom.SVGGElement) extends SelectableElement(ref0):
  val selectedClass = "selected"

  private lazy val toArrowId: Option[ArrowId] =
    Arrow.fromSvg(svgIdAttr)

  // if parsing fails, use the title as the nodeId
  lazy val elementId: ArrowId =
    toArrowId.getOrElse(models.ArrowId(refTitle))

end EdgeElement

case class ClusterElement(ref0: dom.SVGGElement) extends SelectableElement(ref0):
  val selectedClass = "selected"
  val elementId     = models.GroupId(refTitle)

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
