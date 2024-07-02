package org.jpablo.typeexplorer.viewer.components.svgGroupElement

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

  def selectAll(e: dom.Element) =
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

//  ref.querySelector("path").removeAttribute("stroke")

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


//  def select(): Unit =
//    for el <- ref.children do el.updateStyle("stroke" -> "rgb(245 158 11)", "stroke-width" -> "3.0")
//
//  def unselect(): Unit =
//    for el <- ref.children do el.updateStyle("stroke" -> "#181818", "stroke-width" -> "1.0")



//  ---------------------------------------------

sealed trait SvgGroupElement(val ref: dom.SVGGElement):
  def prefix: String
//  def box: Option[dom.SVGElement]
  private def selectKey = "outline"
  private def selectStyle = "3px solid rgb(245 158 11)"

  val id = ref.id.stripPrefix(prefix)
  val symbol = models.NodeId(id)
  private val selectedClass = "selected"

  def select(): Unit =
    ref.classList.add(selectedClass)
    ref.updateStyle(selectKey -> selectStyle)

  def unselect(): Unit =
    ref.classList.remove(selectedClass)
    ref.removeStyle(selectKey)

  def toggle(): Unit =
    if ref.classList.contains(selectedClass) then unselect() else select()

object SvgGroupElement:
  def fromDomSvgElement(e: dom.Element): Option[SvgGroupElement] =
    NamespaceElement.from(e) orElse ClusterElement.from(e)

class NamespaceElement(ref: dom.SVGGElement) extends SvgGroupElement(ref):
  def prefix = NamespaceElement.prefix
  private val boxTagName = "rect"

//  def box: Option[dom.SVGElement] =
//    ref
//      .getElementsByTagName(boxTagName)
//      .find(_.getAttribute("id") == id)
//      .map(_.asInstanceOf[dom.SVGElement])

object NamespaceElement:
  val prefix = "elem_"
  private val selector = s"g[id ^= $prefix]"

  def from(e: dom.Element): Option[NamespaceElement] =
    if e.isNamespace then Some(NamespaceElement(e.asInstanceOf[dom.SVGGElement]))
    else None

  def selectAll(e: dom.Element) =
    e.querySelectorAll(selector).flatMap(from)

class ClusterElement(ref: dom.SVGGElement) extends SvgGroupElement(ref):
  def prefix = ClusterElement.prefix
  private val boxTagName = "path"

  /** PlantUML "namespace" (aka cluster) ids can't contain slashes, so for now they have dots (`a.b.c)` OTOH "classes"
    * (namespaces) have ids of the form `a/b/c`, which means that in order to compare them we need the following method.
    * See: https://forum.plantuml.net/17150/namespace-with-slashes-in-the-name?show=17151#a17151
    */
  val idWithSlashes = id.replace('.', '/')

  def box: Option[dom.SVGElement] =
    ref
      .getElementsByTagName(boxTagName)
      .headOption
      .map(_.asInstanceOf[dom.SVGElement])

object ClusterElement:
  val prefix = "cluster_"
  private val selector = s"g[id ^= $prefix]"

  def from(e: dom.Element): Option[ClusterElement] =
    if e.isPackage then Some(ClusterElement(e.asInstanceOf[dom.SVGGElement]))
    else None

  def selectAll(e: dom.Element) =
    e.querySelectorAll(selector).flatMap(from)


extension (e: dom.Element)
  def path =
    e +: LazyList.unfold(e)(e => Option(e.parentNode.asInstanceOf[dom.Element]).map(e => (e, e)))

  def isDiagramElement(prefix: String) =
    e.tagName == "g" && e
      .hasAttribute("id") && e.getAttribute("id").startsWith(prefix)

  def isNamespace = e.isDiagramElement(NamespaceElement.prefix)
  def isPackage = e.isDiagramElement(ClusterElement.prefix)
//  def isLink = e.isDiagramElement(LinkElement.prefix)

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
