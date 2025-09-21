package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Shape}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithDefaults
import scala.collection.immutable.VectorMap

trait CombineNodesOps:
  this: ViewerGraph =>

  /** Checks if the given nodes can be combined into a record node.
    * Nodes can be combined if they all belong to the same group (or all have no group).
    */
  def canCombineNodes(nodeIds: Set[NodeId]): Boolean =
    if nodeIds.size < 2 then false
    else
      val groups = nodeIds.map(memberships.get)
      groups.size == 1  // All nodes have the same group (or all have None)

  /** Combines multiple nodes into a single record node, preserving all edges.
    *
    * @param nodeIds Set of node IDs to combine
    * @return Updated ViewerGraph with the nodes combined into a record node
    */
  def combineIntoRecord(nodeIds: Set[NodeId]): ViewerGraph =
    if !canCombineNodes(nodeIds) then
      this  // Return unchanged if nodes cannot be combined
    else
      val nodesToCombine = nodeIds.flatMap(getNode).toSeq
      if nodesToCombine.size != nodeIds.size then
        this  // Some nodes not found, return unchanged
      else
        val newNodeId = nextNodeId()
        val recordLabel = createRecordLabel(nodesToCombine)

        // Get the group membership of the original nodes (they all have the same)
        val groupMembership = memberships.get(nodesToCombine.head.id)

        // Create the new record node
        val newNode = nodeWithDefaults(
          newNodeId,
          Attributes.of(
            Shape -> Shape.record,
            Label -> recordLabel
          )
        )

        // Create port mapping: original node ID -> port name
        val portMapping: Map[NodeId, String] = nodesToCombine.zipWithIndex.map { case (node, idx) =>
          node.id -> s"f$idx"
        }.toMap

        // Remap all edges
        val remappedArrows = arrows.map { case (arrowId, arrow) =>
          val newSource = portMapping.get(arrow.source) match
            case Some(port) => arrow.source  // Will be replaced below
            case None => arrow.source

          val newTarget = portMapping.get(arrow.target) match
            case Some(port) => arrow.target
            case None => arrow.target

          val newSourcePort = portMapping.get(arrow.source) match
            case Some(port) => Some(port)
            case None => arrow.sourcePort

          val newTargetPort = portMapping.get(arrow.target) match
            case Some(port) => Some(port)
            case None => arrow.targetPort

          // Update source/target if they were in the combined nodes
          val updatedArrow = arrow.copy(
            source = if portMapping.contains(arrow.source) then newNodeId else arrow.source,
            target = if portMapping.contains(arrow.target) then newNodeId else arrow.target,
            sourcePort = newSourcePort,
            targetPort = newTargetPort
          )

          updatedArrow.id -> updatedArrow
        }

        // Build the new graph
        val updatedNodes = (nodes -- nodeIds) + (newNodeId -> newNode)
        val updatedMemberships = (memberships -- nodeIds) ++ groupMembership.map(g => newNodeId -> g)

        copy(
          elements = elements.copy(
            nodes = updatedNodes,
            arrows = VectorMap.from(remappedArrows),
            memberships = updatedMemberships
          )
        )

  /** Creates a record label string from a sequence of nodes.
    * Format: "<f0> Label1 | <f1> Label2 | <f2> Label3"
    */
  private def createRecordLabel(nodes: Seq[ViewerNode]): String =
    nodes.zipWithIndex.map { case (node, idx) =>
      val label = node.label.toString
      // Escape special characters in labels for record format
      val escapedLabel = label
        .replace("|", "\\|")
        .replace("<", "\\<")
        .replace(">", "\\>")
        .replace("{", "\\{")
        .replace("}", "\\}")
      s"<f$idx> $escapedLabel"
    }.mkString(" | ")

  /** Checks if a node is a record node (has shape=record or shape=Mrecord). */
  def isRecordNode(nodeId: NodeId): Boolean =
    getNode(nodeId).exists { node =>
      node.attributes.values.get(Shape.attrId).exists { shape =>
        val shapeStr = shape.toString
        shapeStr == "record" || shapeStr == "Mrecord"
      }
    }

  /** Checks if the given node can be split (is a record node). */
  def canSplitRecord(nodeId: NodeId): Boolean = isRecordNode(nodeId)

  /** Splits a record node back into individual nodes, preserving all edges.
    *
    * @param nodeId ID of the record node to split
    * @return Updated ViewerGraph with the record split into individual nodes
    */
  def splitRecordNode(nodeId: NodeId): ViewerGraph =
    if !canSplitRecord(nodeId) then
      this  // Return unchanged if not a record node
    else
      getNode(nodeId) match
        case None => this  // Node not found
        case Some(recordNode) =>
          val recordLabel = recordNode.label.toString
          val fieldLabels = parseRecordLabel(recordLabel)

          if fieldLabels.isEmpty then
            this  // Cannot parse label, return unchanged
          else
            // Get the group membership of the record node
            val groupMembership = memberships.get(nodeId)

            // Create new nodes for each field
            val newNodes = fieldLabels.map { label =>
              val newId = nextNodeId()
              newId -> nodeWithDefaults(newId, Attributes.of(Label -> label))
            }

            // Create reverse port mapping: port name -> new node ID
            val portToNodeMap: Map[String, NodeId] = newNodes.zipWithIndex.map {
              case ((newId, _), idx) => s"f$idx" -> newId
            }.toMap

            // Remap all edges
            val remappedArrows = arrows.map { case (arrowId, arrow) =>
              // Check if source is the record node with a port
              val (newSource, newSourcePort) =
                if arrow.source == nodeId && arrow.sourcePort.isDefined then
                  (portToNodeMap.getOrElse(arrow.sourcePort.get, arrow.source), None)
                else
                  (arrow.source, arrow.sourcePort)

              // Check if target is the record node with a port
              val (newTarget, newTargetPort) =
                if arrow.target == nodeId && arrow.targetPort.isDefined then
                  (portToNodeMap.getOrElse(arrow.targetPort.get, arrow.target), None)
                else
                  (arrow.target, arrow.targetPort)

              val updatedArrow = arrow.copy(
                source = newSource,
                target = newTarget,
                sourcePort = newSourcePort,
                targetPort = newTargetPort
              )

              updatedArrow.id -> updatedArrow
            }

            // Build the new graph
            val updatedNodes = (nodes - nodeId) ++ newNodes.map { case (id, node) => id -> node }
            val updatedMemberships = (memberships - nodeId) ++
              groupMembership.map(g => newNodes.map { case (id, _) => id -> g }).getOrElse(Map.empty)

            copy(
              elements = elements.copy(
                nodes = updatedNodes,
                arrows = VectorMap.from(remappedArrows),
                memberships = updatedMemberships
              )
            )

  /** Parses a record label to extract individual field labels.
    * Input format: "<f0> Label1 | <f1> Label2 | <f2> Label3"
    * Returns: Seq("Label1", "Label2", "Label3")
    */
  private def parseRecordLabel(recordLabel: String): Seq[String] =
    recordLabel.split(" \\| ").toSeq.map { field =>
      // Remove port identifier (e.g., "<f0> ")
      val labelPart = field.replaceFirst("^<f\\d+>\\s*", "")
      // Unescape special characters
      labelPart
        .replace("\\|", "|")
        .replace("\\<", "<")
        .replace("\\>", ">")
        .replace("\\{", "{")
        .replace("\\}", "}")
    }.filter(_.nonEmpty)