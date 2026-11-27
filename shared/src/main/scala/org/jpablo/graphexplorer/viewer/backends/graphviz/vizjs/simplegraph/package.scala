package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, ArrowPositionParser}
import org.jpablo.graphexplorer.viewer.models.ArrowId

package object simplegraph:

  /** The center of each node of a render, by the name of the node, in points. The y axis goes up, as in Graphviz.
    * A node with no `pos` has no entry. Freeze reads the current place of each node here (ADR 0004).
    */
  def getNodePos(graph: SimpleGraph): Map[String, Point] =
    graph.objects.getOrElse(Nil).iterator
      .collect { case SimpleGraphObject.Node(node) => node }
      .flatMap: node =>
        node.pos.flatMap: pos =>
          pos.split(',') match
            case Array(x, y) => (x.trim.toDoubleOption zip y.trim.toDoubleOption).map((px, py) => node.name -> Point(px, py))
            case _           => None
      .toMap

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
