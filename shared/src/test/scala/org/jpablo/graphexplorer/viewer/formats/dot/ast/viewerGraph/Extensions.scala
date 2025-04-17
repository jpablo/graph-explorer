package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.{Attr, DotNodeId}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId
import org.jpablo.graphexplorer.viewer.models.{NodeId, ViewerNode}


extension (dotNodeId: DotNodeId)
  def nodeTuple: (NodeId, ViewerNode) = nodeWithId(dotNodeId.id)
  def toNodeId: NodeId = NodeId(dotNodeId.id)
  def toAttr = Attr("id", dotNodeId.toNodeId.toSvg)

