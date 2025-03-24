package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{Attributes, NodeId, ViewerNode}

package object ast:
  def nodeWithId(nodeIdOrString: NodeId | String, attrs: (String, String)*) =
    val nodeId =
      nodeIdOrString match
        case id: NodeId  => id
        case str: String => NodeId(str)
    nodeId -> node(nodeId, Attributes.of(attrs*))
