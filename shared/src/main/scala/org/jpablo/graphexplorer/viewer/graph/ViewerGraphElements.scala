package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes.{fromExpandedAttributes, subAttributeIds}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{FillColor, Style}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

import scala.collection.immutable.VectorMap

/** Internal representation of a graph in the viewer.
  *   - style attribute: not supported
  *   - sub-attributes: supported
  *   - style inheritance (with sub-attributes): supported
  *   - default attributes: supported
  *   - meaning of missing or empty style attribute: inherit from global defaults (or hardcoded)
  */
case class ViewerGraphElements(
    nodes: VectorMap[NodeId, ViewerNode] = VectorMap.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the top-level graph is implicit
    // i.e. if an element is not in memberships, it belongs to top-level graph
    memberships: Map[GroupMemberId, GroupId] = Map.empty,
    // The subgraph an ARROW was declared in (innermost). Arrows absent here
    // belong to the top-level graph. Kept separate from `memberships` so the
    // GroupMemberId-based selection/visibility machinery is untouched — but
    // the DOT round-trip must preserve it: layout engines like fdp lay out
    // clusters separately, so an intra-cluster edge re-serialized at top
    // level changes the whole drawing ("wrong ownership of arrows").
    arrowMemberships: Map[ArrowId, GroupId] = Map.empty,
    groups:      Map[GroupId, ViewerGroup] = Map.empty,
    //
    graphAttributes: Attributes = Attributes.empty,
    // Global style
    defaultNodeAttributes:  Attributes = Attributes.empty,
    defaultArrowAttributes: Attributes = Attributes.empty,
//    defaultGroupAttributes: Attributes = Attributes.empty
) derives ReadWriter

object ViewerGraphElements:

  implicit val nodeMapRW: ReadWriter[VectorMap[NodeId, ViewerNode]] =
    readwriter[Map[String, ViewerNode]].bimap[VectorMap[NodeId, ViewerNode]](
      _.map { case (k, v) => k.value -> v },
      map => VectorMap.from(map.map { case (k, v) => NodeId(k) -> v })
    )

  val defaultRootId = GroupId("G")
  val minimal       = ViewerGraphElements()

  extension (elements: ViewerGraphElements)

    /** Combines the style sub-attributes into a single "style" attribute, simulating style inheritance.
      *
      * This is done when converting from ViewerGraph to DOT format.
      *
      * The convention is: ViewerGraph contains expanded sub-attributes, while DOT uses a single "style" attribute.
      *
      * The challenge is that while DOT internally combines attributes (local / global), it doesn't do that with style-subattributes.
      *
      * For example:
      *
      *    digraph "G" { node[shape=rectangle, style="rounded"] "a" [style="dashed"]; }
      *
      * style="dashed" replaces style="rounded", instead of combining them.
      *
      * Simulating inheritance requires this:
      *
      *   digraph "G" { node[shape=rectangle, style="rounded"] "a" [style="dashed,rounded"]; }
      */
    def combineStyleAttributes: ViewerGraphElements =
      // Extract defaults for style sub-attributes so elements can inherit them
      val nodeDefaultsSubAttrs  = fromExpandedAttributes(elements.defaultNodeAttributes)
      val arrowDefaultsSubAttrs = fromExpandedAttributes(elements.defaultArrowAttributes)
      val graphSubAttrs         = fromExpandedAttributes(elements.graphAttributes)

      elements.copy(
        nodes = elements.nodes.transform { (_, n) => n.modifyAttrs.using(attrs => combineAttributes(attrs, nodeDefaultsSubAttrs)) },
        arrows = elements.arrows.transform { (_, a) => a.modifyAttrs.using(attrs => combineAttributes(attrs, arrowDefaultsSubAttrs)) },
        groups = elements.groups.transform { (_, g) => g.modifyAttrs.using(combineAttributes) },
        graphAttributes = combineAttributes(elements.graphAttributes),
        defaultNodeAttributes  = combineDefaultAttributes(elements.defaultNodeAttributes, nodeDefaultsSubAttrs),
        defaultArrowAttributes = combineDefaultAttributes(elements.defaultArrowAttributes, arrowDefaultsSubAttrs)
      )

  private def combineAttributes(expandedAttrs: Attributes): Attributes =
    // Collapse only the element’s own sub-attributes into style (no inheritance)
    addStyle(expandedAttrs, fromExpandedAttributes(expandedAttrs).toStyleStrings)

  private def combineAttributes(expandedAttrs: Attributes, defaults: StyleSubAttributes): Attributes =
    // Merge element sub-attributes with defaults to simulate inheritance for DOT export
    val elementSubAttrs  = fromExpandedAttributes(expandedAttrs)
    val effectiveSubAttr = elementSubAttrs.combine(defaults)

    // Forbid invalid expanded state: an explicit fillcolor without an effective filled style
    val hasFillColor   = expandedAttrs.get(FillColor).isDefined
    val effectiveFilled = effectiveSubAttr.fill.is(true)
    assert(!(hasFillColor && !effectiveFilled),
      s"Invalid attributes: 'fillcolor' present without 'filled' style in element: ${expandedAttrs.values}")

    // If the element has no explicit style sub-attributes, do not emit a style
    // attribute here. Rely on defaults in the `node [...]` block instead.
    // This avoids redundantly outputting style="filled" on elements that
    // simply inherit the default filled style.
    val elementHasExplicitStyle = elementSubAttrs != StyleSubAttributes.missing
    val styleForElement =
      if elementHasExplicitStyle then
        // Use effective (element + defaults) to simulate inheritance when emitting DOT.
        // If no tokens result but the element had explicit style sub-attributes, emit an explicit reset: style="".
        effectiveSubAttr.toStyleStrings.orElse(Some(""))
      else None

    addStyle(expandedAttrs, styleForElement)

  private def combineDefaultAttributes(expandedAttrs: Attributes, subAttrs: StyleSubAttributes): Attributes =
    // Emit default style (including filled) when present, so node [style="filled"] can be output.
    addStyle(expandedAttrs, subAttrs.toStyleStringNoDefaults)

  private def addStyle(expandedAttrs: Attributes, styleOpt: Option[String]): Attributes =
    styleOpt match
      case None        => expandedAttrs -- subAttributeIds
      case Some(style) => expandedAttrs -- subAttributeIds + (Style.attrId -> AttrValue(style))
