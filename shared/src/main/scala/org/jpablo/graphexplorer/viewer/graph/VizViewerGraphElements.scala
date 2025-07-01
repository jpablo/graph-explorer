package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes.removeIncorrectCombos
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{EdgeStyle, FillStyle, NodeStyle}
import org.jpablo.graphexplorer.viewer.graph.VizViewerGraphElements.expandElementStyleAttributes
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

import scala.collection.immutable.VectorMap

/** Direct translation from SimpleGraph:
  *   - style attribute: supported
  *   - meaning of missing or empty style attribute: RESET to defaults
  *   - style inheritance: not supported
  *   - sub-attributes: not allowed
  *   - no default attributes for nodes, arrows, groups
  */
case class VizViewerGraphElements(
    nodes: VectorMap[NodeId, ViewerNode] = VectorMap.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the top-level graph is implicit
    // i.e. if an element is not in memberships, it belongs to top-level graph
    memberships: Map[GroupMemberId, GroupId] = Map.empty,
    groups:      Map[GroupId, ViewerGroup] = Map.empty,
    //
    graphAttributes: Attributes = Attributes.empty
) derives ReadWriter:


  /**
   * Combines the processes of expanding "style" attributes into sub-attributes
   * and extracting default attributes from the graph elements. The combined method
   * first decomposes the "style" attribute into its constituent sub-attributes,
   * such as fill, bold, invisible, border, and corner. After this, it identifies
   * common attributes for nodes, arrows, and groups, removing them from the individual
   * elements and relocating them to the respective default attribute categories.
   *
   * @return
   * A `ViewerGraphElements` instance that includes:
   *   - Graph elements (nodes, arrows, and groups) with expanded sub-attributes and
   *     non-default attributes only.
   *   - Extracted default attributes for nodes, arrows, and groups.
   *   - Unmodified graph-level attributes and membership mappings.
   */
  def expandAndExtractDefaultAttributes: ViewerGraphElements =
    expandStyleAttributes
      .extractDefaultAttributes

  def toViewerGraphElements: ViewerGraphElements =
    ViewerGraphElements(
      nodes = nodes,
      arrows = arrows,
      memberships = memberships,
      groups = groups,
      graphAttributes = graphAttributes
    )

  /** Expands the "style" attribute into its sub-attributes (fill, bold, invisible, border, corner)
    *
    * This is done when converting from DOT (SimpleGraph) to ViewerGraph format.
    *
    * @return
    *   A graph without the "style" attribute, but with expanded sub-attributes.
    */
  private[graph] def expandStyleAttributes: VizViewerGraphElements =
    this
      .copy(
        nodes = nodes.transform((_, n) => n.modifyAttrs.using(expandElementStyleAttributes)),
        groups = groups.transform((_, g) => g.modifyAttrs.using(expandElementStyleAttributes)),
        arrows = arrows.transform((_, a) => a.modifyAttrs.using(expandElementStyleAttributes)),
        graphAttributes = expandElementStyleAttributes(graphAttributes)
      )

  /** Extracts default attributes from the graph elements, including nodes, arrows, and groups. Identifies common attributes across all
    * elements of the same type and removes those attributes from individual elements, moving them to the respective default attribute
    * categories. Attributes specific to individual elements or excluded by predefined rules will not be considered as defaults.
    *
    * @return
    *   A `ViewerGraphElements` instance containing:
    *   - Nodes, arrows, and groups with non-default attributes.
    *   - Extracted default attributes for nodes, arrows, and groups.
    *   - Unmodified graph attributes and membership mappings.
    */
  private[graph] def extractDefaultAttributes: ViewerGraphElements =
    import org.jpablo.graphexplorer.viewer.models.*

    // Helper function to find attributes that appear on ALL elements with the same value
    def findCommonAttributes(attributesList: Seq[Attributes], excludeFromDefaults: Set[String] = Set.empty): Attributes =
      if attributesList.isEmpty || attributesList.size == 1 then
        // Don't extract defaults if there's only one element or no elements
        Attributes.empty
      else
        // Get the first element's attributes as candidates
        val firstAttrs = attributesList.head.values
        // Filter to only include attributes that appear on ALL elements with the same value
        // and exclude element-specific attributes that should never be defaults
        val commonAttrs = firstAttrs.filter: (attrId, attrValue) =>
          !excludeFromDefaults.contains(attrId.value) &&
            attributesList.forall(_.values.get(attrId).contains(attrValue))
        Attributes(VectorMap.from(commonAttrs))

    def removeAttributes(attrs: Attributes, toRemove: Set[AttributeId]): Attributes =
      Attributes(attrs.values -- toRemove)

    // Extract default attributes for nodes
    val nodeAttributesList  = nodes.values.map(_.attributes).toSeq
    val nodeExclusions      = Set("_gvid", "name", "pos", "height", "width", "label") // Element-specific + theme attributes
    val defaultNodeAttrs    = findCommonAttributes(nodeAttributesList, nodeExclusions)
    val defaultNodeAttrKeys = defaultNodeAttrs.values.keySet - FillStyle.attrId

    // Remove default attributes from individual nodes
    val nodesWithoutDefaults = nodes.transform: (_, node) =>
      node.modifyAttrs.using(attrs => removeAttributes(attrs, defaultNodeAttrKeys))

    // Extract default attributes for arrows
    val arrowAttributesList  = arrows.values.map(_.attributes).toSeq
    val arrowExclusions      = Set("_gvid", "pos", "lp", "label")
    val defaultArrowAttrs    = findCommonAttributes(arrowAttributesList, arrowExclusions)
    val defaultArrowAttrKeys = defaultArrowAttrs.values.keySet - FillStyle.attrId

    // Remove default attributes from individual arrows
    val arrowsWithoutDefaults = arrows.transform: (_, arrow) =>
      arrow.copy(attributes = removeAttributes(arrow.attributes, defaultArrowAttrKeys))

    // Extract default attributes for groups
    val groupAttributesList  = groups.values.map(_.attributes).toSeq
    val groupExclusions      = Set("_gvid", "name", "cluster", "lp", "lheight", "lwidth", "label", "rank") // Element-specific attributes
    val defaultGroupAttrs    = findCommonAttributes(groupAttributesList, groupExclusions)
    val defaultGroupAttrKeys = defaultGroupAttrs.values.keySet - FillStyle.attrId

    // Remove default attributes from individual groups
    val groupsWithoutDefaults = groups.transform: (_, group) =>
      group.modifyAttrs.using(attrs => removeAttributes(attrs, defaultGroupAttrKeys))

    ViewerGraphElements(
      nodes = nodesWithoutDefaults,
      arrows = arrowsWithoutDefaults,
      memberships = memberships,
      groups = groupsWithoutDefaults,
      graphAttributes = graphAttributes,
      defaultNodeAttributes = defaultNodeAttrs,
      defaultArrowAttributes = defaultArrowAttrs,
      defaultGroupAttributes = defaultGroupAttrs
    )

  end extractDefaultAttributes

object VizViewerGraphElements:

  implicit val nodeMapRW: ReadWriter[VectorMap[NodeId, ViewerNode]] =
    readwriter[Map[String, ViewerNode]].bimap[VectorMap[NodeId, ViewerNode]](
      _.map { case (k, v) => k.value -> v },
      map => VectorMap.from(map.map { case (k, v) => NodeId(k) -> v })
    )

  val defaultRootId = GroupId("G")
  val minimal       = VizViewerGraphElements()

  /** Expands the "style" attribute of a graph element into its corresponding sub-attributes. For example, deconstructs a "style" attribute
    * string (e.g., "filled,bold") into specific sub-attributes such as fill, bold, invisible, border, and corner styles.
    *
    * Used in: DOT -> SimpleGraph -> ViewerGraph
    *
    * @param attrs
    *   the set of attributes associated with a graph element, which may contain a "style" attribute
    * @return
    *   a new set of attributes where the "style" attribute, if present, is replaced with its sub-attributes
    */
  private def expandElementStyleAttributes(attrs: Attributes): Attributes =
    import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style

    // At this point there should be no sub-attributes in the attributes map.
    StyleSubAttributes.subAttributeIds.foreach(attrId => assert(attrs.get(attrId).isEmpty))

    // Check for either NodeStyle or Style (EdgeStyle uses Style.attrId)
    val style         = attrs.get(NodeStyle) orElse attrs.get(Style) orElse attrs.get(EdgeStyle)
    val styleSubAttrs = StyleSubAttributes.fromStyleString(style.map(_.toString))
    // 1. Normalize fill color if present
    // 2. Remove the "style" attribute if it exists
    // 3. Add the expanded style sub-attributes back to the attributes map (which the UI will use)
    removeIncorrectCombos(attrs, styleSubAttrs) - NodeStyle - EdgeStyle - Style ++ styleSubAttrs.toAttributes
