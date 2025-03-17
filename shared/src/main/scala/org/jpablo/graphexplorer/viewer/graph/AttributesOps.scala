package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.style.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.style.StyleSubAttributes.{fromSubAttributes, subAttributeIds}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{ArrowTail, ArrowType, Dir, DirType, GraphType, NodeStyle, Overlap, Sides, Size, Style}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Multiple, Single}

trait AttributesOps:
  this: ViewerGraph =>

  lazy val modifyRootGraphAttrs = this.modify(_.elements.groups.at(rootGroup.id).attributes)
  lazy val modifyRootNodeAttrs = this.modify(_.elements.groups.at(rootGroup.id).nodeAttrs)
  lazy val modifyRootEdgeAttrs = this.modify(_.elements.groups.at(rootGroup.id).arrowAttrs)

  lazy val removeUnsupportedFeatures: ViewerGraph =
    modifyRootGraphAttrs.using(_ - Size.attrId - Overlap.attrId)

  def expandStyleAttributes: ViewerGraphElements =
    elements.copy(
      groups = groups.transform { (id, g) =>
        g.copy(
          attributes = expandElementAttributes(id, g.attributes),
          arrowAttrs = expandElementAttributes(id, g.arrowAttrs),
          nodeAttrs  = expandElementAttributes(id, g.nodeAttrs)
        )
      },
      nodes = nodes.transform((id, n) => n.copy(attributes = expandElementAttributes(id, n.attributes)))
    )

  // DOT -> ViewerGraph
  // style="..." -> [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle]
  private def expandElementAttributes(id: ElementId, attrs: Attributes): Attributes =
    attrs.get(NodeStyle.attrId).fold(attrs): styleAttr =>
      // replace the "style" attribute with its sub-attributes (fill, bold, etc.)
      attrs - NodeStyle.attrId ++ StyleSubAttributes.parse(styleAttr).withDefaults.toSubAttributes

  def combineStyleAttributes: ViewerGraphElements =
    elements.copy(
      groups = groups.transform { (id, g) =>
        g.copy(
          attributes = combineElementAttributes(id, g.attributes),
          arrowAttrs = combineElementAttributes(id, g.arrowAttrs),
          nodeAttrs  = combineElementAttributes(id, g.nodeAttrs)
        )
      },
      nodes = nodes.transform { (id, n) =>
        n.copy(
          attributes = combineElementAttributes(id, n.attributes, globals = Some(rootGroup.nodeAttrs))
        )
      }
    )

  // ViewerGraph -> DOT
  // Replace the sub-attributes with the combined "style" attribute
  // [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle] -> style="..."
  private def combineElementAttributes(
      id:      ElementId,
      attrs:   Attributes,
      globals: Option[Attributes] = None
  ): Attributes =
    val localSubAttrs = fromSubAttributes(attrs)

    val styleStringO =
      globals match
        case None =>
          val styleString = localSubAttrs.toStyleStringSimple
          if styleString.isEmpty then None else Some(styleString)
        case Some(globalAttrs) =>
          localSubAttrs.toStyleCombined(fromSubAttributes(globalAttrs))

    val filteredAttrs = attrs -- subAttributeIds
    styleStringO match
      case None        => filteredAttrs // remove the style attribute
      case Some(style) => filteredAttrs + (Style.attrId -> AttrValue(style))

  /** Updates attributes for a set of nodes and arrows.
    *
    * This method updates the attributes of the specified nodes and arrows:
    *   1. Separates the input IDs into arrow IDs and node IDs 2. Updates attributes for matching arrows 3. Updates
    *      attributes for matching nodes, including any endpoints of updated arrows that were in the original selection
    *
    * @return
    *   Updated ViewerGraphData with the new attributes applied
    */
  def updateAttributes(ids: ElementIds, updates: AttributesUpdates): ViewerGraph =
    val classified = ids.classify

    val updatedArrows = arrows.view
      .filterKeys(arrowId => arrowId in classified.arrows)
      .mapValues(_.modify(_.attributes).using(updates.applyUpdatesTo))
      .toMap

    val clusterIds = classified.clusters.map(id => GroupId(id.value))

    val updatedClusters = groups.view
      .filterKeys(groupId => groupId in clusterIds)
      .mapValues(_.modify(_.attributes).using(updates.applyUpdatesTo))
      .toMap

    val nodeIdsToUpdate = classified.nodes ++
      (updatedArrows.values.flatMap(_.endpoints).toSet & classified.nodes)

    val updatedNodes = nodeIdsToUpdate.foldLeft(nodes): (nodes, id) =>
      nodes.updated(
        id,
        nodes.getOrElse(id, ViewerNode(id)).modify(_.attributes).using(updates.applyUpdatesTo)
      )

    modifyElements.using(
      _.copy(
        arrows = arrows ++ updatedArrows,
        nodes  = updatedNodes,
        groups = groups ++ updatedClusters
      )
    )

  // Used to combine the attributes of multiple selected elements (say two nodes)
  private def mergeAttributeUpdates[K <: ElementId, V <: Attributable](
      nodeIds:       ElementIds,
      attributables: Map[K, V]
  ): Map[AttributeId, SelectionAttrValue] =
    attributables.foldLeft(Map.empty[AttributeId, SelectionAttrValue]):
      case (acc, (nodeId, attributable)) if nodeId in nodeIds =>
        val nodeIdAcc =
          // replace attribute values with Single / Multiple (if they are already in the accumulator and they are different)
          attributable.attributes.values.transform: (attrId, v) =>
            if (attrId in acc) && !acc(attrId).is(v) then Multiple else Single(v)
        acc ++ nodeIdAcc
      case (acc, _) => acc

  def getAttributesById(id: ElementId): Attributes =
    id match
      case id: ArrowId => arrows.get(id).fold(Attributes.empty)(_.attributes)
      case id: GroupId => groups.get(id).fold(Attributes.empty)(_.attributes)
      case id: NodeId  => nodes.get(id).fold(Attributes.empty)(_.attributes)

  def getAttributesUpdatesById(ids: ElementIds): AttributesUpdates =
    AttributesUpdates(
      existing = ids.ids.headOption
        .map:
          case _: ArrowId => mergeAttributeUpdates(ids, arrows.map(identity))
          case _: GroupId => mergeAttributeUpdates(ids, groups.map(identity))
          case _: NodeId  => mergeAttributeUpdates(ids, nodes.map(identity))
        .getOrElse(Map.empty)
    )

  def getRootAttributes(target: AttributeTarget): Attributes =
    target match
      case AttributeTarget.graph => rootGroup.attributes
      case AttributeTarget.node  => rootGroup.nodeAttrs
      case AttributeTarget.edge  => rootGroup.arrowAttrs

  def modifyRootAttributes(target: AttributeTarget) =
    target match
      case AttributeTarget.graph => modifyRootGraphAttrs
      case AttributeTarget.node  => modifyRootNodeAttrs
      case AttributeTarget.edge  => modifyRootEdgeAttrs

  def updateRootAttributes(target: AttributeTarget)(update: Attributes => Attributes): ViewerGraph =
    modifyRootAttributes(target).using(update)

  val defaultNodeTheme =
    Attributes(
      Map(
        Sides.attrId -> AttrValue("5")
      )
    )

  val defaultEdgeTheme =
    val dir = tpe match
      case GraphType.graph => DirType.none
      case GraphType.digraph => DirType.both
    Attributes(
      Map(
        Dir.attrId       -> AttrValue(dir.toString),
        ArrowTail.attrId -> AttrValue(ArrowType.none.toString)
      )
    )

  def setDefaultTheme: ViewerGraph =
    modifyRootAttributes(AttributeTarget.node).using(_ ++ defaultNodeTheme)
      .modifyRootAttributes(AttributeTarget.edge).using(_ ++ defaultEdgeTheme)

object AttributesOps:

  /** Bundle functions for updating root attributes of a specific root target (graph, node, edge) */
  def rootAttributesUpdates(target: AttributeTarget): Lens[ViewerGraph, AttributesUpdates] =
    Lens(
      in  = graph => graph.getRootAttributes(target).toUpdates,
      out = (graph, updates) => graph.updateRootAttributes(target)(updates.applyUpdatesTo)
    )

  /** Bundle functions for updating attributes of specific elements */
  def elementAttributesUpdates(elementIds: ElementIds): Lens[ViewerGraph, AttributesUpdates] =
    Lens(
      in  = graph => graph.getAttributesUpdatesById(elementIds),
      out = (graph, updates) => graph.updateAttributes(elementIds, updates)
    )
