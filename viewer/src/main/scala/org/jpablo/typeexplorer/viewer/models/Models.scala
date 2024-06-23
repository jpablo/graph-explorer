package org.jpablo.typeexplorer.viewer.models

import org.jpablo.typeexplorer.viewer.utils.Utils

// ---- Vertices ------

case class ViewerNodeId(value: String) extends AnyVal:
  override def toString: String = value

type ViewerKind = Option[String]

case class ViewerNode(
    id:          ViewerNodeId,
    displayName: String,
    kind:        ViewerKind = None
)

// ---- Edges ------

case class ArrowId(value: String) extends AnyVal:
  override def toString: String = value

object ArrowId:
  def random(): ArrowId = ArrowId(Utils.randomUUID())
end ArrowId

case class Arrow(
    source: ViewerNodeId,
    target: ViewerNodeId,
    id:     ArrowId = ArrowId.random()
):
  def toTuple: (ViewerNodeId, ViewerNodeId) =
    (source, target)
