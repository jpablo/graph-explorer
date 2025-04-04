package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat.DotFormatter
import org.jpablo.graphexplorer.viewer.formats.dot.attributes as attr
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeNoDefaults

import scala.annotation.tailrec
import scala.collection.immutable.VectorMap

enum AttributeTarget derives CanEqual:
  case node, edge, graph

/** These attributes are only used for the top level graph (as opposed to group defaults)
  */
val graphAttrIds =
  Set(
    attr.BgColor.attrId,
    attr.Concentrate.attrId,
    attr.Label.attrId,
    attr.LabelJust.attrId,
    attr.Layout.attrId,
    attr.NodeSep.attrId,
    attr.Pad.attrId,
    attr.RankSep.attrId,
    attr.Rankdir.attrId,
    attr.RootGraphLabelLoc.attrId,
    attr.Splines.attrId
  )

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    ast.id match
      case Some(id) =>
        // TODO: should resetId be called inside toViewerGraphElements?
        EdgeStmt.resetId()
        val g = ViewerGraph(elements = ast.toViewerGraphElements, id = id, tpe = attr.GraphType.valueOf(ast.tpe))
        g.modifyElements.setTo(g.expandStyleAttributes)

      case None =>
        throw new IllegalArgumentException("DotAST must have an id")

  private def subGraphToViewerGroup(sub: SubGraph, gId: Option[GroupId] = None): ViewerGroup =
    group(
      groupId = gId.getOrElse(GroupId(sub.id.getOrElse(SubGraph.randomId()))),
      attributes = Attributes(sub.collectAttributesByTarget.getOrElse(AttributeTarget.graph, Map.empty))
    )

  private def buildViewerGraphElements(
      rawNodes:    List[ViewerNode],           // Nodes with only their specific attributes initially
      arrows:      List[Arrow],
      memberships: List[(ElementId, GroupId)], // List of (element, group) memberships
      groups:      List[ViewerGroup],
      groupAttrs:  Map[GroupId, Map[AttributeTarget, Attributes]]
  ): ViewerGraphElements =
    val arrowsMap       = arrows.map(a => a.id -> a).toMap
    val explicitNodeIds = rawNodes.map(_.id).toSet
    val implicitNodeIds = arrows.flatMap(_.endpoints).filterNot(explicitNodeIds)

    // Build the complete membership map (including nodes added via arrows within groups)
    val allMemberships = memberships.foldLeft(VectorMap.empty[GroupMemberId, GroupId]) {
      case (acc, (memId, groupId)) =>
        memId match
          case m: GroupMemberId => acc + (m -> groupId)
          case aId: ArrowId => // If an arrow is in a group, its nodes are implicitly in it too
            arrowsMap.get(aId).map(_.endpoints) match
              case Some(Seq(a, b)) => acc + (a -> groupId) + (b -> groupId)
              case _               => acc // Should not happen if arrowsMap is consistent
    }

    // Top level default attributes
    val attrsByTarget = ast.toSubGraph.collectAttributesByTarget

    val attributes        = Attributes(attrsByTarget.getOrElse(AttributeTarget.graph, Map.empty))
    val defaultArrowAttrs = Attributes(attrsByTarget.getOrElse(AttributeTarget.edge, Map.empty))
    val defaultNodeAttrs  = Attributes(attrsByTarget.getOrElse(AttributeTarget.node, Map.empty))

    // Helper to find all ancestor groups and combine their node attributes
    def getInheritedNodeAttributes(memberId: GroupMemberId): Attributes =
      @tailrec
      def findAncestorGroups(currentId: GroupMemberId, ancestors: List[GroupId]): List[GroupId] =
        allMemberships.get(currentId) match
          case Some(parentId) => findAncestorGroups(parentId, parentId :: ancestors)
          case None           => ancestors

      val ancestorGroups = findAncestorGroups(memberId, Nil) // List from closest to farthest

      // Start with root defaults, then apply ancestor defaults from farthest to closest
      ancestorGroups.reverse.foldLeft(defaultNodeAttrs): (accAttrs, groupId) =>
        val groupNodeSpecificAttrs = groupAttrs.get(groupId).flatMap(_.get(AttributeTarget.node)).getOrElse(Attributes.empty)
        accAttrs ++ groupNodeSpecificAttrs // Closer group attributes override farther ones

    // Calculate final attributes for explicit nodes
    val finalNodesMap = VectorMap.from(
      rawNodes.map: node =>
        // Node-specific attributes take the highest precedence
        node.id -> nodeNoDefaults(node.id, getInheritedNodeAttributes(node.id) ++ node.attributes)
    )
    // Create implicit nodes with their inherited attributes
    val implicitNodesMap = VectorMap.from(
      implicitNodeIds.map(nId => nId -> nodeNoDefaults(nId, getInheritedNodeAttributes(nId)))
    )

    ViewerGraphElements(
      nodes = finalNodesMap ++ implicitNodesMap,
      arrows = arrowsMap,
      memberships = allMemberships,
      groups = groups.map(g => g.id -> g).toMap,
      graphAttributes = attributes.filterKeys(_ in graphAttrIds),
      defaultNodeAttributes = defaultNodeAttrs, // These were already used as the base for inheritance
      defaultArrowAttributes = defaultArrowAttrs,
      defaultGroupAttributes = attributes.filterKeys(_ notIn graphAttrIds)
    )

  /** Builds a ViewerGraphElements from a DotAST.
    *
    * This method traverses the AST and builds a ViewerGraphElements structure. It handles groups, edges, and nodes, and ensures they are
    * correctly associated.
    *
    * @return
    *   A ViewerGraphElements structure matching the AST.
    */
  def toViewerGraphElements: ViewerGraphElements =
    @tailrec
    def loop(
        pendingGroups: List[(Option[GroupId], List[GraphElement])],
        arrows:        List[Arrow],
        groups:        List[ViewerGroup],
        nodes:         List[ViewerNode], // Will collect nodes with only specific attrs here
        memberships:   List[(ElementId, GroupId)],
        groupAttrs:    Map[GroupId, Map[AttributeTarget, Attributes]] = Map.empty
    ): ViewerGraphElements =
      pendingGroups match

        case Nil =>
          // Pass raw nodes to buildViewerGraphElements for final attribute calculation
          buildViewerGraphElements(nodes.reverse, arrows, memberships.reverse, groups.reverse, groupAttrs)

        // corner case: a groupId without children. Skipping it.
        case (_, Nil) :: t => loop(t, arrows, groups, nodes, memberships, groupAttrs)

        // firstChild and children belong to the same parent group
        case (groupId, firstChild :: children) :: restGroups => // remaining
          // \________ first group _________/

          val pendingGroups = (groupId -> children) :: restGroups

          firstChild match

            case sub: SubGraph =>
              val viewerGroup       = subGraphToViewerGroup(sub)
              val byTarget          = sub.collectAttributesByTarget
              val updatedGroupAttrs = groupAttrs + (viewerGroup.id -> byTarget.map { case (k, v) => k -> Attributes(v) })
              loop(
                pendingGroups = (Some(viewerGroup.id) -> sub.children) :: pendingGroups,
                arrows = arrows,
                groups = viewerGroup :: groups,
                nodes = nodes,
                memberships = groupId.fold(memberships)(gId => (viewerGroup.id -> gId) :: memberships),
                groupAttrs = updatedGroupAttrs
              )

            case e: EdgeStmt =>
              val edgeArrows = e.expandArrows
              loop(
                pendingGroups = pendingGroups,
                arrows = arrows ++ edgeArrows,
                groups = groups,
                nodes = nodes,
                memberships = groupId.fold(memberships)(gId => edgeArrows.reverse.map(_.id -> gId) ++ memberships),
                groupAttrs = groupAttrs
              )

            case n: NodeStmt =>
              // Store ONLY the node's specific attributes for now. Inheritance happens later.
              val specificAttributes = Attributes(toAttrsMap(n.attr_list))
              val viewerNode = nodeNoDefaults(
                nodeId = NodeId(n.node_id.id),
                attributes = specificAttributes
              )
              loop(
                pendingGroups = pendingGroups,
                arrows = arrows,
                groups = groups,
                nodes = viewerNode :: nodes,
                memberships = groupId.fold(memberships)(gId => (viewerNode.id -> gId) :: memberships),
                groupAttrs = groupAttrs
              )

            case _ =>
              loop(pendingGroups, arrows, groups, nodes, memberships, groupAttrs)

    loop(
      pendingGroups = List(None -> ast.children),
      arrows = Nil,
      groups = Nil,
      nodes = Nil,
      memberships = Nil
    )

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(List(), _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t       => loop(t, state)
        case h :: t                        => loop(t, h :: state)
        case Nil                           => state.reverse

    ast.modify(_.children).using(loop(_))

  def render(keepInternal: Boolean = false): String =
    DotFormatter.renderFormat(ast, keepInternal)
