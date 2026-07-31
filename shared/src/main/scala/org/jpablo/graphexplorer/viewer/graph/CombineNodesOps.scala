package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.dot.RecordTree
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Shape, Rankdir}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithDefaults
import scala.collection.immutable.VectorMap

trait CombineNodesOps:
  this: ViewerGraph =>

  /** Rebuilds the arrows map by transforming every arrow, and re-keys `elements.arrowMemberships` from old to new ArrowIds so cluster
    * ownership survives the id change — the bulk sibling of `ViewerGraph.rekeyArrowMembership`.
    */
  private def remapArrows(transform: Arrow => Arrow): (VectorMap[ArrowId, Arrow], Map[ArrowId, GroupId]) =
    val arrowRemap         = arrows.toSeq.map((oldId, arrow) => oldId -> transform(arrow))
    val remapped           = VectorMap.from(arrowRemap.map((_, a) => a.id -> a))
    val idRemap            = arrowRemap.map((oldId, a) => oldId -> a.id).toMap
    val rekeyedMemberships = elements.arrowMemberships.flatMap((oldId, g) => idRemap.get(oldId).map(_ -> g))
    (remapped, rekeyedMemberships)

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

        // Remap all edges; cluster ownership survives the id change (remapArrows).
        val (remappedArrows, updatedArrowMemberships) = remapArrows { arrow =>
          arrow.copy(
            source = if portMapping.contains(arrow.source) then newNodeId else arrow.source,
            target = if portMapping.contains(arrow.target) then newNodeId else arrow.target,
            sourcePort = portMapping.get(arrow.source).orElse(arrow.sourcePort),
            targetPort = portMapping.get(arrow.target).orElse(arrow.targetPort)
          )
        }

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
    val fields: Vector[RecordTree] = nodes.zipWithIndex.map { case (node, idx) =>
      RecordTree.Leaf(Some(s"f$idx"), RecordTree.storedText(node.label.toString))
    }.toVector

    // For TB/BT graphs, wrap in {} to force vertical stacking
    // For LR/RL graphs, keep horizontal (default behavior)
    val root = rankdir match
      case Rankdir.TB | Rankdir.BT => RecordTree.Group(None, Vector(RecordTree.Group(None, fields)))
      case Rankdir.LR | Rankdir.RL => RecordTree.Group(None, fields)
    RecordTree.serialize(root)

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
          // Every leaf becomes a node, positionally, nested groups flattened in
          // field order. Empty fields are KEPT: dropping one would misalign the
          // positional f<index> fallback below and re-home edges wrongly.
          val recordLeaves = RecordTree.leaves(RecordTree.parse(recordLabel))

          if recordLeaves.forall(_.text.isEmpty) then
            this  // Nothing meaningful to split into, return unchanged
          else
            // Get the group membership of the record node
            val groupMembership = memberships.get(nodeId)

            // Create new nodes for each field
            val newNodes = recordLeaves.map { leaf =>
              val newId = nextNodeId()
              newId -> nodeWithDefaults(newId, Attributes.of(Label -> RecordTree.unescapeSpecials(leaf.text)))
            }

            // Reverse port mapping: the label's REAL port names win; positional
            // f<index> names fill the gaps so hand-edited ports still resolve.
            val portToNodeMap: Map[String, NodeId] =
              val positional = newNodes.zipWithIndex.map { case ((newId, _), idx) => s"f$idx" -> newId }
              val real       = recordLeaves.zip(newNodes).flatMap { case (leaf, (newId, _)) => leaf.port.map(_ -> newId) }
              (positional ++ real).toMap

            // Fallback for edges that touch the record without a resolvable port
            // (a port-less edge, or a port name not present because the label was
            // hand-authored). The record node is deleted below, so anything pointing
            // at it must be re-homed to a real field node — the first one — instead
            // of the now-nonexistent record id.
            val firstNewNodeId = newNodes.head._1

            // Remap all edges; cluster ownership survives the id change (remapArrows).
            val (remappedArrows, updatedArrowMemberships) = remapArrows { arrow =>
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

              arrow.copy(
                source = newSource,
                target = newTarget,
                sourcePort = newSourcePort,
                targetPort = newTargetPort
              )
            }

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

  /** Replaces the label of a record node — the single write path for cell-level
    * (structured) record edits, which parse/serialize through [[RecordTree]].
    */
  def withRecordLabel(nodeId: NodeId, newLabel: String): ViewerGraph =
    getNode(nodeId) match
      case Some(node) if isRecordNode(nodeId) =>
        val updatedNode = ViewerNode.nodeNoDefaults(node.id, node.attributes + (Label.attrId -> AttrValue(newLabel)))
        copy(elements = elements.copy(nodes = nodes.updated(nodeId, updatedNode)))
      case _ => this

  /** Replaces a node's HTML-like label (stored with the html flag, serialized
    * in DOT's `<...>` form) — the write path for html-table cell edits, which
    * parse/print through [[org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabelOps]].
    */
  def withHtmlLabel(nodeId: NodeId, newLabel: String): ViewerGraph =
    getNode(nodeId) match
      case Some(node) =>
        val value       = AttrValue(org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrEq(newLabel, html = true))
        val updatedNode = ViewerNode.nodeNoDefaults(node.id, node.attributes + (Label.attrId -> value))
        copy(elements = elements.copy(nodes = nodes.updated(nodeId, updatedNode)))
      case None => this

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

          // Toggle the outer {} through the parsed tree — string slicing would
          // corrupt labels like "{a}|{b}" whose braces don't span the whole label.
          // A single ported group child is wrapped rather than unwrapped: dropping
          // the group would lose its port.
          val root = RecordTree.parse(currentLabel)
          val newRoot = root.children match
            case Vector(RecordTree.Group(None, inner)) => RecordTree.Group(None, inner)
            case cs => RecordTree.Group(None, Vector(RecordTree.Group(None, cs)))
          withRecordLabel(nodeId, RecordTree.serialize(newRoot))