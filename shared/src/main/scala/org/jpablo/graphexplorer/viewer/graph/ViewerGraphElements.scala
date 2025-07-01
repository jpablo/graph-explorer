package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes.StyleSubAttributes.{fromSubAttributes, subAttributeIds}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{NodeStyle, Style}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

import scala.annotation.tailrec
import scala.collection.immutable.VectorMap

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

  @tailrec
  def ancestorGroups(
      memberships: Map[GroupMemberId, GroupId],
      currentId:   GroupMemberId,
      ancestors:   List[GroupId]
  ): List[GroupId] =
    memberships.get(currentId) match
      case Some(parentId) => ancestorGroups(memberships, parentId, parentId :: ancestors)
      case None           => ancestors

  extension (elements: ViewerGraphElements)
    /** Expands the "style" attribute into its sub-attributes (fill, bold, invisible, border, corner)
      */
    def expandStyleAttributes: ViewerGraphElements =
      elements
        .copy(
          nodes = elements.nodes.transform((_, n) => n.modifyAttrs.using(expandElementAttributes)),
          groups = elements.groups.transform((_, g) => g.modifyAttrs.using(expandElementAttributes))
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
          nodes = elements.nodes.transform { (_, n) =>
            n.modifyAttrs.using(combineElementAttributes(_, defaults = Some(elements.defaultNodeAttributes)))
          },
          groups = elements.groups.transform { (_, g) =>
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
