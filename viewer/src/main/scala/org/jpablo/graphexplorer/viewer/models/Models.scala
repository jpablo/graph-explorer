package org.jpablo.graphexplorer.viewer.models

import upickle.default.*
import org.jpablo.graphexplorer.viewer.utils.Utils

// ---- Vertices ------

case class NodeId(value: String):
  override def toString: String = value

object NodeId:
  given rw: ReadWriter[NodeId] = stringKeyRW(readwriter[String].bimap[NodeId](_.value, NodeId(_)))

type ViewerKind = Option[String]

case class ViewerNode(
    id:          NodeId,
    displayName: String,
    kind:        ViewerKind = None
)

object ViewerNode:
  def node(name: String) =
    ViewerNode(NodeId(name), name)

// ---- Edges ------

case class ArrowId(value: String) extends AnyVal:
  override def toString: String = value

object ArrowId:
  def random(): ArrowId = ArrowId(Utils.randomUUID())

case class Arrow(
    source: NodeId,
    target: NodeId,
    id:     ArrowId = ArrowId.random()
):
  def toTuple: (NodeId, NodeId) =
    (source, target)

object Arrow:
  def apply(s: String, t: String) =
    new Arrow(NodeId(s), NodeId(t))

  def fromString(input: String): Option[Arrow] =
    val i = input.indexOf("->")
    if i > 0 && i < input.length - 2 then
      val l = input.substring(0, i).trim
      val r = input.substring(i + 2).trim
      if l.nonEmpty && r.nonEmpty then Some(Arrow(NodeId(l), NodeId(r)))
      else None
    else None
