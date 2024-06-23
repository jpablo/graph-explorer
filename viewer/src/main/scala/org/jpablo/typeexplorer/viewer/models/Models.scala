package org.jpablo.typeexplorer.viewer.models

import org.jpablo.typeexplorer.viewer.utils.Utils

case class ViewerNodeId(value: String) extends AnyVal:
  override def toString: String = value

case class ArrowId(value: String) extends AnyVal:
  override def toString: String = value

object ArrowId:
  def random(): ArrowId = ArrowId(Utils.randomUUID())
end ArrowId

type ViewerKind = Option[String]

case class ViewerNode(
    id:          ViewerNodeId,
    displayName: String,
    kind:        ViewerKind = None
)

case class Arrow(
    source: ViewerNodeId,
    target: ViewerNodeId,
    id:     ArrowId = ArrowId.random()
):
  def toTuple: (ViewerNodeId, ViewerNodeId) =
    (source, target)
