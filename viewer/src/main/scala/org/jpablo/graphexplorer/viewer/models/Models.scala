package org.jpablo.graphexplorer.viewer.models

import upickle.default.*
import org.jpablo.graphexplorer.viewer.utils.Utils
import compiletime.asMatchable
import Arrow.{idAttributeKey, titleIdSeparator}

// ---- Vertices ------

case class NodeId(value: String) extends AnyVal:
  override def toString: String = value

object NodeId:
  given rw: ReadWriter[NodeId] = stringKeyRW(readwriter[String].bimap[NodeId](_.value, NodeId(_)))

type ViewerKind = Option[String]

trait Attributable:
  def attrs: Attributes

  def label: String =
    attrs.values.getOrElse("label", "")

  def idAttr: String =
    attrs.values.getOrElse(idAttributeKey, "")

case class ViewerNode(id: NodeId, attrs: Attributes = Attributes.empty, kind: ViewerKind = None) extends Attributable

object ViewerNode:
  def node(name: String) = ViewerNode(NodeId(name))

// ---- Edges ------

case class ArrowId(value: String) extends AnyVal:
  override def toString: String = value

object ArrowId:
  def random(): ArrowId = ArrowId(Utils.randomUUID())

case class Arrow(
    source: NodeId,
    target: NodeId,
    attrs:  Attributes = Attributes.empty
) extends Attributable:

  // Re-create the string used by graphviz in the `<title>` element of the SVG.
  def nodeId: NodeId = NodeId(s"${source.value}$titleIdSeparator${target.value}:$idAttr")

  def nodeIds = Set(source, target, nodeId)

  override def equals(obj: Any): Boolean =
    obj.asMatchable match
      case that: Arrow =>
        this.source == that.source && this.target == that.target && this.idAttr == that.idAttr
      case _ =>
        false

  override def hashCode(): Int = (source, target, idAttr).##

  override def toString: String = s"Arrow($idAttr)"

end Arrow

object Arrow:

  val idAttributeKey = "id"
  val titleIdSeparator = "->"

  def apply(t: (String, String), attrs: Map[String, String]): Arrow =
    new Arrow(NodeId(t._1), NodeId(t._2), Attributes(attrs))

  // Expects `title` to be the "<title>" generated by graphviz for arrows.
  // `idAttr` is used to disambiguate multiple arrows between the same nodes.
  def fromGraphvizTitle(title: String, idAttr: String): Option[Arrow] =
    val i = title.indexOf(titleIdSeparator)
    if i > 0 && i < title.length - 2 then
      val l = title.substring(0, i).trim
      val r = title.substring(i + 2).trim
      if l.nonEmpty && r.nonEmpty then
        Some(Arrow(NodeId(l), NodeId(r), Attributes(Map(idAttributeKey -> idAttr))))
      else
        None
    else
      None

  given scala.Ordering[Arrow] with
    def compare(x: Arrow, y: Arrow): Int =
      val s = x.source.value `compareTo` y.source.value
      if s != 0 then s
      else
        val t = x.target.value `compareTo` y.target.value
        if t != 0 then t else x.idAttr `compareTo` y.idAttr
end Arrow

case class Attributes(values: Map[String, String]) extends AnyVal

object Attributes:
  val empty = Attributes(Map.empty)
