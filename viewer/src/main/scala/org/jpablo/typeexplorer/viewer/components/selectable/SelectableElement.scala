package org.jpablo.typeexplorer.viewer.components.selectable

import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.scalajs.dom

sealed trait SelectableElement(ref: dom.SVGGElement):
  def selectedClass: String
  def selectKey: String
  def selectStyle: String
  protected val title = ref.querySelector("title").textContent
  val nodeId = models.NodeId(title)

  def select(): Unit =
    ref.classList.add(selectedClass)
    ref.updateStyle(selectKey -> selectStyle)

  def unselect(): Unit =
    ref.classList.remove(selectedClass)
    ref.removeStyle(selectKey)

  def toggle(): Unit =
    if ref.classList.contains(selectedClass) then
      unselect()
    else
      select()

object SelectableElement:

  def from(e: dom.Element): Option[SelectableElement] =
    if isDiagramElement(e, "node") then
      Some(NodeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "edge") then
      Some(EdgeElement(e.asInstanceOf[dom.SVGGElement]))
    else
      None

  def findAll(e: dom.Element): collection.Seq[SelectableElement] =
    e.querySelectorAll("g").flatMap(from)


  private def isDiagramElement(e: dom.Element, cls: String) =
    e.tagName == "g" && e.classList.contains(cls)

end SelectableElement

class NodeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"
  val selectKey = "outline"
  val selectStyle = "3px solid rgb(245 158 11)"


class EdgeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"
  val selectKey = "outline"
  val selectStyle = "3px solid rgb(245 158 11)"

  def endpointIds: Option[(NodeId, NodeId)] =
    val input = title
    val i = input.indexOf("->")
    if i > 0 && i < input.length - 2 then
      val l = input.substring(0, i).trim
      val r = input.substring(i + 2).trim
      if l.nonEmpty && r.nonEmpty then
        Some((NodeId(l), NodeId(r)))
      else
        None
    else
      None


extension (e: dom.Element)
  def path =
    e +: LazyList.unfold(e)(e => Option(e.parentNode.asInstanceOf[dom.Element]).map(e => (e, e)))

  def fill = e.getAttribute("fill")
  def fill_=(c: String) = e.setAttribute("fill", c)

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
