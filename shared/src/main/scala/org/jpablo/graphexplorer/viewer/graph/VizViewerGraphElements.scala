package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes.removeIncorrectCombos
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{EdgeStyle, NodeStyle, Style}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements.nodeMapRW
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
    // innermost declaring subgraph per arrow (see ViewerGraphElements)
    arrowMemberships: Map[ArrowId, GroupId] = Map.empty,
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
      .toViewerGraphElements

  def toViewerGraphElements: ViewerGraphElements =
    ViewerGraphElements(
      nodes = nodes,
      arrows = arrows,
      memberships = memberships,
      arrowMemberships = arrowMemberships,
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


object VizViewerGraphElements:

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
    // Sub-attributes are synthetic: they exist inside a ViewerGraph and nowhere
    // in the DOT language, so text arriving here should carry none.
    //
    // This was an `assert`, and the input it was meant to be impossible turned
    // out to be reachable: `DiagramText.render` printed a graph WITHOUT folding
    // sub-attributes back into `style`, so `gx` wrote `fillstyle="true"` into
    // the user's file and then could not read that file back. The printer is
    // fixed, but files written by a released `gx` are already on disk, and
    // `assertion failed` — naming no attribute and no element — is not a
    // diagnosis anyone can act on.
    //
    // Dropped rather than honoured, because that is what `dot` does with an
    // attribute it does not know. Honouring `fillstyle` would make the viewer
    // paint a fill graphviz would not, and the whole point of this reader is to
    // agree with graphviz. A file that lost its fill this way says so in the
    // only way that stays truthful: it renders the way `dot` renders it.
    val cleaned = attrs -- StyleSubAttributes.subAttributeIds

    // Check for either NodeStyle or Style (EdgeStyle uses Style.attrId)
    val style         = cleaned.get(NodeStyle) orElse cleaned.get(Style) orElse cleaned.get(EdgeStyle)
    val styleSubAttrs = StyleSubAttributes.fromStyleString(style.map(_.toString))
    // 1. Normalize fill color if present
    // 2. Remove the "style" attribute if it exists
    // 3. Add the expanded style sub-attributes back to the attributes map (which the UI will use)
    removeIncorrectCombos(cleaned, styleSubAttrs) - NodeStyle - EdgeStyle - Style ++ styleSubAttrs.toAttributes
