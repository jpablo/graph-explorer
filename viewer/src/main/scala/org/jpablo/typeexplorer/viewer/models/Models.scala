package org.jpablo.typeexplorer.viewer.models


opaque type ViewerNodeId = String

type ViewerKind = Option[String]

object ViewerNodeId:
  def apply(value: String): ViewerNodeId = value
  def empty: ViewerNodeId = ""
  extension (s: ViewerNodeId) def toString: String = s

case class ViewerNode(
    nodeId:      ViewerNodeId,
    displayName: String,
    kind:        ViewerKind = None
)
