package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes.StyleSubAttributes.{fromSubAttributes, subAttributeIds}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{
  ArrowTail,
  ArrowType,
  Dir,
  DirType,
  GraphType,
  NodeStyle,
  Overlap,
  Sides,
  Size,
  Style
}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Multiple, Single}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithDefaults

trait AttributesOps:
  this: ViewerGraph =>

  lazy val removeUnsupportedFeatures: ViewerGraph =
    this.modifyAll(_.elements.graphAttributes, _.elements.defaultGroupAttributes).using(_ - Size.attrId - Overlap.attrId)

  /** Expands the "style" attribute into its sub-attributes (fill, bold, invisible, border, corner)
    */
  def expandStyleAttributes: ViewerGraphElements =
    elements
      .copy(
        nodes = nodes.transform((_, n) => n.modifyAttrs.using(expandElementAttributes)),
        groups = groups.transform((_, g) => g.modifyAttrs.using(expandElementAttributes))
      ).modifyAll(
        _.graphAttributes,
        _.defaultNodeAttributes,
        _.defaultArrowAttributes,
        _.defaultGroupAttributes
      ).using(expandElementAttributes)

  // DOT -> ViewerGraph
  // style="..." -> [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle]
  private def expandElementAttributes(attrs: Attributes): Attributes =
    attrs.get(NodeStyle.attrId).fold(attrs): styleAttr =>
      // replace the "style" attribute with its sub-attributes (fill, bold, etc.)
      attrs - NodeStyle.attrId ++ StyleSubAttributes.parse(styleAttr).withDefaults.toAttributes

  /** Combines the style sub-attributes into a single "style" attribute.
    */
  def combineStyleAttributes: ViewerGraphElements =
    elements
      .copy(
        nodes = nodes.transform { (_, n) =>
          n.modifyAttrs.using(combineElementAttributes(_, defaults = Some(elements.defaultNodeAttributes)))
        },
        groups = groups.transform { (_, g) =>
          g.modifyAttrs.using(combineElementAttributes(_, defaults = Some(elements.defaultGroupAttributes)))
        }
      ).modifyAll(
        _.graphAttributes,
        _.defaultNodeAttributes,
        _.defaultArrowAttributes,
        _.defaultGroupAttributes
      ).using(combineElementAttributes(_))

  // ViewerGraph -> DOT
  // Replace the sub-attributes with the combined "style" attribute
  // [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle] -> style="..."
  private def combineElementAttributes(
      attrs:    Attributes,
      defaults: Option[Attributes] = None
  ): Attributes =
    val localSubAttrs = fromSubAttributes(attrs)

    val styleStringO =
      defaults match
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
    *   1. Separates the input IDs into arrow IDs and node IDs 2. Updates attributes for matching arrows 3. Updates attributes for matching
    *      nodes, including any endpoints of updated arrows that were in the original selection
    *
    * @return
    *   Updated ViewerGraphData with the new attributes applied
    */
  def updateAttributes(ids: ElementIds, selectionAttrs: AttributeUpdates): ViewerGraph =
    val classified = ids.classify

    val updatedArrows = arrows.view
      .filterKeys(classified.arrows)
      .mapValues(_.modify(_.attributes).using(selectionAttrs.applyUpdates))
      .toMap

    val updatedClusters = groups.view
      .filterKeys(classified.groups)
      .mapValues(_.modifyAttrs.using(selectionAttrs.applyUpdates))
      .toMap

    val nodeIdsToUpdate = classified.nodes ++
      (updatedArrows.values.flatMap(_.endpoints).toSet & classified.nodes)

    val updatedNodes = nodeIdsToUpdate.foldLeft(nodes): (nodes, id) =>
      nodes.updated(
        id,
        nodes.getOrElse(id, nodeWithDefaults(id)).modifyAttrs.using(selectionAttrs.applyUpdates)
      )

    modifyElements.using(
      _.copy(
        arrows = arrows ++ updatedArrows,
        nodes = updatedNodes,
        groups = groups ++ updatedClusters
      )
    )

  // Used to combine the attributes of multiple selected elements (say two nodes)
  private def mergeAttributeUpdates[K <: ElementId, V <: ViewerElement](
      elementIds: ElementIds,
      elements:   Map[K, V]
  ): Map[AttributeId, AttrValueWithStatus] =
    elements.foldLeft(Map.empty[AttributeId, AttrValueWithStatus]):
      case (acc, (nodeId, attributable)) if nodeId in elementIds =>
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

  def getAttributesUpdatesById(ids: ElementIds): AttributeUpdates =
    AttributeUpdates(
      ids.ids.headOption
        .map:
          case _: ArrowId => mergeAttributeUpdates(ids, arrows)
          case _: GroupId => mergeAttributeUpdates(ids, groups)
          case _: NodeId  => mergeAttributeUpdates(ids, nodes)
        .getOrElse(Map.empty)
    )

  def getDefaultAttributes(target: AttributeTarget): Attributes =
    target match
      case AttributeTarget.graph => elements.defaultGroupAttributes
      case AttributeTarget.node  => elements.defaultNodeAttributes
      case AttributeTarget.edge  => elements.defaultArrowAttributes

  def modifyDefaultAttributes(target: AttributeTarget) =
    target match
      case AttributeTarget.graph => this.modify(_.elements.defaultGroupAttributes)
      case AttributeTarget.node  => this.modify(_.elements.defaultNodeAttributes)
      case AttributeTarget.edge  => this.modify(_.elements.defaultArrowAttributes)

  val defaultNodeTheme =
    Attributes.of(Sides -> 5)

  val defaultEdgeTheme: Attributes =
    val dir = tpe match
      case GraphType.graph   => DirType.none
      case GraphType.digraph => DirType.both
    Attributes.of(Dir -> dir, ArrowTail -> ArrowType.none)

  def setDefaultTheme: ViewerGraph =
    modifyDefaultAttributes(AttributeTarget.node).using(_ ++ defaultNodeTheme)
      .modifyDefaultAttributes(AttributeTarget.edge).using(_ ++ defaultEdgeTheme)

object AttributesOps:

  /** Lens for accessing and updating the main graph attributes */
  def diagramAttributesUpdates: Lens[ViewerGraph, AttributeUpdates] =
    Lens(
      get = graph => graph.elements.graphAttributes.toUpdates,
      update = (graph, updates) => graph.modify(_.elements.graphAttributes).using(updates.applyUpdates)
    )

  /** Bundle functions for updating root attributes of a specific root target (graph, node, edge) */
  def defaultAttributesUpdates(target: AttributeTarget): Lens[ViewerGraph, AttributeUpdates] =
    Lens(
      get = graph => graph.getDefaultAttributes(target).toUpdates,
      update = (graph, updates) => graph.modifyDefaultAttributes(target).using(updates.applyUpdates)
    )

  /** Bundle functions for updating attributes of specific elements */
  def elementAttributesUpdates(elementIds: ElementIds): Lens[ViewerGraph, AttributeUpdates] =
    Lens(
      get = graph => graph.getAttributesUpdatesById(elementIds),
      update = (graph, updates) => graph.updateAttributes(elementIds, updates)
    )
