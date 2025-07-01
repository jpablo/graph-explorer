package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes.{fromExpandedAttributes, subAttributeIds}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Style, FillStyle, FillColor}
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
    groups:      Map[GroupId, ViewerGroup] = Map.empty,
    //
    graphAttributes: Attributes = Attributes.empty,
    // Deprecated: attributes will be inlined in nodes and arrows for now.
    defaultNodeAttributes:  Attributes = Attributes.empty,
    defaultArrowAttributes: Attributes = Attributes.empty,
    defaultGroupAttributes: Attributes = Attributes.empty
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
      val nodeDefaultSubAttrs  = fromExpandedAttributes(elements.defaultNodeAttributes)
      val arrowDefaultSubAttrs = fromExpandedAttributes(elements.defaultArrowAttributes)
      val groupDefaultSubAttrs = fromExpandedAttributes(elements.defaultGroupAttributes)
      val graphSubAttrs        = fromExpandedAttributes(elements.graphAttributes)
      elements
        .copy(
          nodes = elements.nodes.transform { (_, n) => n.modifyAttrs.using(combineAttributes(_, nodeDefaultSubAttrs)) },
          arrows = elements.arrows.transform { (_, a) => a.modifyAttrs.using(combineAttributes(_, arrowDefaultSubAttrs)) },
          groups = elements.groups.transform { (_, g) => g.modifyAttrs.using(combineAttributes(_, groupDefaultSubAttrs)) },
          graphAttributes = combineAttributes(elements.graphAttributes, graphSubAttrs),
          defaultNodeAttributes = combineDefaultAttributes(elements.defaultNodeAttributes, nodeDefaultSubAttrs),
          defaultArrowAttributes = combineDefaultAttributes(elements.defaultArrowAttributes, arrowDefaultSubAttrs),
          defaultGroupAttributes = combineDefaultAttributes(elements.defaultGroupAttributes, groupDefaultSubAttrs)
        )

  private def combineAttributes(expandedAttrs: Attributes, defaults: StyleSubAttributes): Attributes =
    // Forbid invalid expanded state on elements: fillcolor present without style=filled.
    val hasFillColor = expandedAttrs.get(FillColor).isDefined
    val isFilled     = expandedAttrs.get(FillStyle).exists(_.isTrue)
    assert(!(hasFillColor && !isFilled),
      s"Invalid attributes: 'fillcolor' present without 'filled' style in element: ${expandedAttrs.values}")

    // Allow defaults (including filled) to participate in style combination to simulate inheritance.
    addStyle(expandedAttrs, fromExpandedAttributes(expandedAttrs).toStyleStrings(defaults))

  private def combineDefaultAttributes(expandedAttrs: Attributes, subAttrs: StyleSubAttributes): Attributes =
    // Emit default style (including filled) when present, so node [style="filled"] can be output.
    addStyle(expandedAttrs, subAttrs.toStyleStringNoDefaults)

  private def addStyle(expandedAttrs: Attributes, styleOpt: Option[String]): Attributes =
    styleOpt match
      case None        => expandedAttrs -- subAttributeIds
      case Some(style) => expandedAttrs -- subAttributeIds + (Style.attrId -> AttrValue(style))
