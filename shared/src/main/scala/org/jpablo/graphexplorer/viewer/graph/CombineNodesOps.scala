package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Shape, Rankdir}
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

        // Get the graph's rankdir to determine optimal record orientation
        val rankdir = elements.graphAttributes.values
          .get(Rankdir.attrId)
          .flatMap(attr => Rankdir.values.find(_.toString == attr.toString))
          .getOrElse(Rankdir.TB)

        val recordLabel = createRecordLabel(nodesToCombine, rankdir)

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

        // Remap all edges, keeping the old->new ArrowId correspondence so arrow
        // cluster ownership (arrowMemberships) survives the id change.
        val arrowRemap: Seq[(ArrowId, Arrow)] = arrows.toSeq.map { case (oldArrowId, arrow) =>
          val updatedArrow = arrow.copy(
            source = if portMapping.contains(arrow.source) then newNodeId else arrow.source,
            target = if portMapping.contains(arrow.target) then newNodeId else arrow.target,
            sourcePort = portMapping.get(arrow.source).orElse(arrow.sourcePort),
            targetPort = portMapping.get(arrow.target).orElse(arrow.targetPort)
          )
          oldArrowId -> updatedArrow
        }
        val remappedArrows          = VectorMap.from(arrowRemap.map((_, a) => a.id -> a))
        val idRemap                 = arrowRemap.map((oldId, a) => oldId -> a.id).toMap
        val updatedArrowMemberships = elements.arrowMemberships.flatMap((oldId, g) => idRemap.get(oldId).map(_ -> g))

        // Build the new graph
        val updatedNodes = (nodes -- nodeIds) + (newNodeId -> newNode)
        val updatedMemberships = (memberships -- nodeIds) ++ groupMembership.map(g => newNodeId -> g)

        copy(
          elements = elements.copy(
            nodes = updatedNodes,
            arrows = remappedArrows,
            memberships = updatedMemberships,
            arrowMemberships = updatedArrowMemberships
          )
        )

  /** Creates a record label string from a sequence of nodes.
    * For space optimization, uses opposite orientation from graph:
    * - TB/BT graphs: wraps in {} for vertical stacking (saves horizontal space)
    * - LR/RL graphs: no wrapping for horizontal stacking (saves vertical space)
    */
  private def createRecordLabel(nodes: Seq[ViewerNode], rankdir: Rankdir): String =
    val fields = nodes.zipWithIndex.map { case (node, idx) =>
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

    // For TB/BT graphs, wrap in {} to force vertical stacking
    // For LR/RL graphs, keep horizontal (default behavior)
    rankdir match
      case Rankdir.TB | Rankdir.BT => s"{$fields}"
      case Rankdir.LR | Rankdir.RL => fields

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

          if fieldLabels.forall(_.isEmpty) then
            this  // Nothing meaningful to split into, return unchanged
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

            // Fallback for edges that touch the record without a resolvable port
            // (a port-less edge, or a port name not present because the label was
            // hand-authored). The record node is deleted below, so anything pointing
            // at it must be re-homed to a real field node — the first one — instead
            // of the now-nonexistent record id.
            val firstNewNodeId = newNodes.head._1

            // Remap all edges, keeping old->new ArrowId correspondence for arrowMemberships.
            val arrowRemap: Seq[(ArrowId, Arrow)] = arrows.toSeq.map { case (oldArrowId, arrow) =>
              val (newSource, newSourcePort) =
                if arrow.source == nodeId then
                  (arrow.sourcePort.flatMap(portToNodeMap.get).getOrElse(firstNewNodeId), None)
                else
                  (arrow.source, arrow.sourcePort)

              val (newTarget, newTargetPort) =
                if arrow.target == nodeId then
                  (arrow.targetPort.flatMap(portToNodeMap.get).getOrElse(firstNewNodeId), None)
                else
                  (arrow.target, arrow.targetPort)

              val updatedArrow = arrow.copy(
                source = newSource,
                target = newTarget,
                sourcePort = newSourcePort,
                targetPort = newTargetPort
              )
              oldArrowId -> updatedArrow
            }
            val remappedArrows          = VectorMap.from(arrowRemap.map((_, a) => a.id -> a))
            val idRemap                 = arrowRemap.map((oldId, a) => oldId -> a.id).toMap
            val updatedArrowMemberships = elements.arrowMemberships.flatMap((oldId, g) => idRemap.get(oldId).map(_ -> g))

            // Build the new graph
            val updatedNodes = (nodes - nodeId) ++ newNodes.map { case (id, node) => id -> node }
            val updatedMemberships = (memberships - nodeId) ++
              groupMembership.map(g => newNodes.map { case (id, _) => id -> g }).getOrElse(Map.empty)

            copy(
              elements = elements.copy(
                nodes = updatedNodes,
                arrows = remappedArrows,
                memberships = updatedMemberships,
                arrowMemberships = updatedArrowMemberships
              )
            )

  /** Parses a record label to extract individual field labels.
    * Handles both formats:
    * - Vertical: "{<f0> Label1 | <f1> Label2 | <f2> Label3}"
    * - Horizontal: "<f0> Label1 | <f1> Label2 | <f2> Label3"
    * Returns: Seq("Label1", "Label2", "Label3")
    */
  private def parseRecordLabel(recordLabel: String): Seq[String] =
    // Remove outer curly braces if present (vertical format)
    val cleanLabel = if recordLabel.startsWith("{") && recordLabel.endsWith("}") then
      recordLabel.substring(1, recordLabel.length - 1)
    else
      recordLabel

    // Keep every field positionally (do NOT drop empty labels): the f<index> ports
    // on edges are positional, so dropping an empty field would misalign the indices
    // and send an edge into the wrong (or a deleted) node when splitting.
    cleanLabel.split(" \\| ").toSeq.map { field =>
      // Remove port identifier (e.g., "<f0> ")
      val labelPart = field.replaceFirst("^<f\\d+>\\s*", "")
      // Unescape special characters
      labelPart
        .replace("\\|", "|")
        .replace("\\<", "<")
        .replace("\\>", ">")
        .replace("\\{", "{")
        .replace("\\}", "}")
    }

  /** Transposes a record node between horizontal and vertical orientations.
    * Toggles between wrapped (vertical) and unwrapped (horizontal) formats.
    *
    * @param nodeId ID of the record node to transpose
    * @return Updated ViewerGraph with the record transposed
    */
  def transposeRecord(nodeId: NodeId): ViewerGraph =
    if !isRecordNode(nodeId) then
      this  // Return unchanged if not a record node
    else
      getNode(nodeId) match
        case None => this  // Node not found
        case Some(recordNode) =>
          val currentLabel = recordNode.label.toString

          // Toggle between vertical (with {}) and horizontal (without {})
          val newLabel = if currentLabel.startsWith("{") && currentLabel.endsWith("}") then
            // Currently vertical, make horizontal
            currentLabel.substring(1, currentLabel.length - 1)
          else
            // Currently horizontal, make vertical
            s"{$currentLabel}"

          // Update the node with the new label
          val updatedNode = ViewerNode.nodeNoDefaults(
            recordNode.id,
            recordNode.attributes + (Label.attrId -> AttrValue(newLabel))
          )

          copy(
            elements = elements.copy(
              nodes = nodes.updated(nodeId, updatedNode)
            )
          )