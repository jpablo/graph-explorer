package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, ArrowPositionParser}
import org.jpablo.graphexplorer.viewer.models.ArrowId

package object simplegraph:

  def getEdgePos(graph: SimpleGraph): Map[String, ArrowPosition] =
    val edgePositions = scala.collection.mutable.Map[String, ArrowPosition]()

    // Create a map from _gvid to name for node lookup
    val nodeMap = graph.objects.map(_.collect {
      case SimpleGraphObject.Node(node) => node._gvid -> node.name
    }.toMap).getOrElse(Map.empty)

    // Collect from main graph edges
    graph.edges.foreach { edgeArray =>
      edgeArray.foreach { edge =>
        edge.pos.foreach { pos =>
          ArrowPositionParser.parse(pos).foreach { arrowPos =>
            // Convert numeric gvids to names
            val tailName = nodeMap.getOrElse(edge.tail, edge.tail.toString)
            val headName = nodeMap.getOrElse(edge.head, edge.head.toString)

            val edgeId = edge.id match {
              case Some(id) =>
                // Try to parse as arrow ID with prefix, fall back to raw ID
                ArrowId.fromSvg(id).map(_.value).getOrElse(id)
              case None => s"$tailName->$headName"
            }
            edgePositions(edgeId) = arrowPos
          }
        }
      }
    }

    edgePositions.toMap
