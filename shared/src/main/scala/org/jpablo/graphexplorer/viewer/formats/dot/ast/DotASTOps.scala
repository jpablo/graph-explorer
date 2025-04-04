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

  private def nodeStmtToViewerNode(nodeStmt: NodeStmt): ViewerNode =
    nodeNoDefaults(
      nodeId = NodeId(nodeStmt.node_id.id),
      attributes = Attributes(toAttrsMap(nodeStmt.attr_list))
    )

  private def subGraphToViewerGroup(sub: SubGraph, gId: Option[GroupId] = None): ViewerGroup =
    group(
      groupId = gId.getOrElse(GroupId(sub.id.getOrElse(SubGraph.randomId()))),
      attributes = Attributes(sub.collectAttributesByTarget.getOrElse(AttributeTarget.graph, Map.empty))
    )

  private def buildViewerGraphElements(
      nodes:       List[ViewerNode],
      arrows:      List[Arrow],
      memberships: List[(ElementId, GroupId)], // List of (element, group) memberships
      groups:      List[ViewerGroup],
      groupAttrs:  Map[GroupId, Map[AttributeTarget, Attributes]]
  ): ViewerGraphElements =
    val nodesMap        = VectorMap.from(nodes.map(n => n.id -> n))
    val arrowsMap       = arrows.map(a => a.id -> a).toMap
    val nodeIds         = nodesMap.keySet
    val implicitNodeIds = arrows.flatMap(_.endpoints).filterNot(nodeIds)

    // DOT allows arrows within clusters, we don't.
    val allMemberships =
      memberships.foldLeft(VectorMap.empty[GroupMemberId, GroupId]):
        case (acc, (memId, groupId)) =>
          memId match
            case m: GroupMemberId => acc + (m -> groupId)
            case aId: ArrowId =>
              val Seq(a, b) = arrowsMap(aId).endpoints
              acc + (a -> groupId) + (b -> groupId)

    // Top level attributes. Only keep one set of attributes for each target.
    // TODO: In case of multiple attributes, we need to keep track of their scope (nodes following the attribute)
    // and apply them accordingly.
    val attrsByTarget = ast.toSubGraph.collectAttributesByTarget

    val attributes = Attributes(attrsByTarget.getOrElse(AttributeTarget.graph, Map.empty))
    val arrowAttrs = Attributes(attrsByTarget.getOrElse(AttributeTarget.edge, Map.empty))
    val nodeAttrs  = Attributes(attrsByTarget.getOrElse(AttributeTarget.node, Map.empty))

    // These attributes are only used for the top level graph (as opposed to group defaults)
    // We separate them when importing from DOT and merge them when exporting to DOT.
    val graphAttrIds = Set(
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

    // Create implicit nodes with their group's node attributes if they belong to a group
    val implicitNodes = implicitNodeIds.map { nodeId =>
      val groupNodeAttrs = allMemberships.get(nodeId)
        .flatMap(groupId => groupAttrs.get(groupId).flatMap(_.get(AttributeTarget.node)))
        .getOrElse(Attributes.empty)
      nodeId -> nodeNoDefaults(nodeId, groupNodeAttrs)
    }

    ViewerGraphElements(
      nodes = nodesMap ++ implicitNodes,
      arrows = arrowsMap,
      memberships = allMemberships,
      groups = groups.map(g => g.id -> g).toMap,
      graphAttributes = attributes.filterKeys(_ in graphAttrIds),
      defaultNodeAttributes = nodeAttrs,
      defaultArrowAttributes = arrowAttrs,
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
        nodes:         List[ViewerNode],
        memberships:   List[(ElementId, GroupId)],                                // List of (element, group) memberships
        groupAttrs:    Map[GroupId, Map[AttributeTarget, Attributes]] = Map.empty // Track group-specific attributes
    ): ViewerGraphElements =
      pendingGroups match

        case Nil => buildViewerGraphElements(nodes.reverse, arrows, memberships.reverse, groups.reverse, groupAttrs)

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
              val nodeAttributes = groupId
                .flatMap(gId => groupAttrs.get(gId).flatMap(_.get(AttributeTarget.node)))
                .getOrElse(Attributes.empty)

              val viewerNode = nodeNoDefaults(
                nodeId = NodeId(n.node_id.id),
                attributes = nodeAttributes ++ Attributes(toAttrsMap(n.attr_list))
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
