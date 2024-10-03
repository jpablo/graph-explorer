package org.jpablo.graphexplorer.viewer.components.selectable

import org.jpablo.graphexplorer.viewer.components.selectable.SelectableElement.queryTitle
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.scalajs.dom
import org.scalajs.dom.Element

sealed trait SelectableElement(ref: dom.SVGGElement):
  def selectedClass: String

  protected val title = queryTitle(ref)
  val nodeId = models.NodeId(title)

  def select(): Unit =
    ref.classList.add(selectedClass)

  def unselect(): Unit =
    ref.classList.remove(selectedClass)

  def toggle(): Unit =
    if ref.classList.contains(selectedClass) then unselect()
    else select()

object SelectableElement:

  def build(e: dom.Element): Option[SelectableElement] =
    if isDiagramElement(e, "node") then Some(NodeElement(e.asInstanceOf[dom.SVGGElement]))
    else if isDiagramElement(e, "edge") then Some(EdgeElement(e.asInstanceOf[dom.SVGGElement]))
    else None

  def findAll(e: dom.Element): collection.Seq[SelectableElement] =
    e.querySelectorAll("g").flatMap(build)

  private def isDiagramElement(e: dom.Element, cls: String) =
    e.tagName == "g" && e.classList.contains(cls)

  def queryTitle(e: dom.Element): String =
    e.querySelector("title").textContent

end SelectableElement

case class NodeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"

case class EdgeElement(ref: dom.SVGGElement) extends SelectableElement(ref):
  val selectedClass = "selected"

  def endpointIds: Option[(NodeId, NodeId)] =
    Arrow.fromGraphvizTitle(title).map(_.toTuple)

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
