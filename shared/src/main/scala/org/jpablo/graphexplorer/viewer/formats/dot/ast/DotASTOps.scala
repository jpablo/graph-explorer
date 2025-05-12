package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotASTOps.{
  buildViewerGraphElements,
  edgeToViewerArrows,
  nodeToViewerNode,
  subGraphToViewerGroup
}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat.DotFormatter
import org.jpablo.graphexplorer.viewer.formats.dot.attributes as attr
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Cluster
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements.ancestorGroups
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.Arrow.nextArrow
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
        val g = ViewerGraph(elements = ast.toViewerGraphElements, id = id, tpe = attr.GraphType.valueOf(ast.tpe))
        g.modifyElements.setTo(g.expandStyleAttributes)

      case None =>
        throw new IllegalArgumentException("DotAST must have an id")

  /** Builds a ViewerGraphElements from a DotAST.
    *
    * This method traverses the AST and builds a ViewerGraphElements structure. It handles groups, edges, and nodes, and ensures they are
    * correctly associated.
    *
    * @return
    *   A ViewerGraphElements structure matching the AST.
    */
  def toViewerGraphElements: ViewerGraphElements =
    val arrowSequence = new DefaultSequenceGenerator
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
          buildViewerGraphElements(ast, nodes.reverse, arrows, memberships.reverse, groups.reverse, groupAttrs)

        // corner case: a groupId without children. Skipping it.
        case (_, Nil) :: t => loop(t, arrows, groups, nodes, memberships, groupAttrs)

        // firstChild and children belong to the same parent group
        case (groupId, firstChild :: children) :: restGroups => // remaining
          // \________ first group _________/

          val pendingGroups = (groupId -> children) :: restGroups

          firstChild match

            case sub: SubGraph =>
              val viewerGroup = subGraphToViewerGroup(sub)
              loop(
                pendingGroups = (Some(viewerGroup.id) -> sub.children) :: pendingGroups,
                arrows = arrows,
                groups = viewerGroup :: groups,
                nodes = nodes,
                memberships = groupId.fold(memberships)(gId => (viewerGroup.id -> gId) :: memberships),
                groupAttrs = groupAttrs + (viewerGroup.id -> sub.collectAttributesByTarget)
              )

            case e: EdgeStmt =>
              val edgeArrows = edgeToViewerArrows(e)(using arrowSequence)
              loop(
                pendingGroups = pendingGroups,
                arrows = arrows ++ edgeArrows,
                groups = groups,
                nodes = nodes,
                memberships = groupId.fold(memberships)(gId => edgeArrows.reverse.map(_.id -> gId) ++ memberships),
                groupAttrs = groupAttrs
              )

            case n: NodeStmt =>
              val viewerNode = nodeToViewerNode(n)
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
    DotFormatter(ast, keepInternal).render()

end extension

object DotASTOps:

  def subGraphToViewerGroup(sub: SubGraph): ViewerGroup =
    val (gId, clusterName) = sub.id.map(GroupId.fromDot).getOrElse(GroupId(SubGraph.randomId()) -> false)

    val attrs      = sub.collectAttributesByTarget.getOrElse(AttributeTarget.graph, Attributes.empty)
    val hasCluster = attrs.get(Cluster).getOrElse(AttrValue(clusterName.toString))
    group(
      groupId = gId,
      attributes = attrs + (Cluster.attrId -> hasCluster)
    )

  def nodeToViewerNode(stmt: NodeStmt): ViewerNode =
    nodeNoDefaults(
      nodeId = NodeId(stmt.node_id.id),
      // Store ONLY the node's specific attributes for now. Inheritance happens later.
      attributes = toAttrsMap(stmt.attr_list)
    )

  /** @return
    *   all arrows generated by the nodes in the edge_list
    */
  def edgeToViewerArrows(stmt: EdgeStmt)(using seq: SequenceGenerator): List[Arrow] =
    val attrs = toAttrsMap(stmt.attr_list)
    stmt.edge_list
      .sliding(2)
      .toList
      .flatMap:
        // (1) Not sure this case is even possible
        case List(SubGraph(_, _)) => Nil

        // (2) a -> b =>  a -> b
        case List(DotNodeId(a, pa), DotNodeId(b, pb)) =>
          List(nextArrow(a -> b, attrs, pa.map(_.id), pb.map(_.id)))

        // (3) a -> {x y ...}  =>  a -> x, a -> y, ...
        case List(DotNodeId(a, pa), sub @ SubGraph(_, _)) =>
          sub.allNodesIds.map(x => nextArrow(a -> x, attrs, sourcePort = pa.map(_.id)))

        // (4) {x y ...} -> a  =>  x -> a, y -> a, ...
        case List(sub @ SubGraph(_, _), DotNodeId(a, pa)) =>
          sub.allNodesIds.map(x => nextArrow(x -> a, attrs, targetPort = pa.map(_.id)))

        // (5) {x y ...} -> {a b ...}  =>  x -> a, x -> b, y -> a, y -> b, ...
        case List(sub1 @ SubGraph(_, _), sub2 @ SubGraph(_, _)) =>
          for
            x <- sub1.allNodesIds
            a <- sub2.allNodesIds
          yield nextArrow(x -> a, attrs)

        case _ => Nil

  /** Take all the flattened AST elements and build a ViewerGraphElements structure.
    */
  def buildViewerGraphElements(
      ast:         DotAST,
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

    val attributes        = attrsByTarget.getOrElse(AttributeTarget.graph, Attributes.empty)
    val defaultArrowAttrs = attrsByTarget.getOrElse(AttributeTarget.edge, Attributes.empty)
    val defaultNodeAttrs  = attrsByTarget.getOrElse(AttributeTarget.node, Attributes.empty)

    // Helper to find all ancestor groups and combine their node attributes
    def inheritedAttributes(memberId: GroupMemberId): Attributes =
      // List from closest to farthest
      val ancestors = ancestorGroups(allMemberships, memberId, Nil)
      // Start with root defaults, then apply ancestor defaults from farthest to closest
      ancestors.reverse.foldLeft(defaultNodeAttrs): (accAttrs, groupId) =>
        val groupNodeSpecificAttrs = groupAttrs.get(groupId).flatMap(_.get(AttributeTarget.node)).getOrElse(Attributes.empty)
        accAttrs ++ groupNodeSpecificAttrs // Closer group attributes override farther ones

    // Calculate final attributes for explicit nodes, filtering out those matching root defaults
    val finalNodesMap = VectorMap.from(
      rawNodes.map: node =>
        val finalAttrs = inheritedAttributes(node.id) ++ node.attributes
        node.id -> nodeNoDefaults(node.id, finalAttrs.filterNot(defaultNodeAttrs.contains))
    )
    // Create implicit nodes with their inherited attributes, filtering out those matching root defaults
    val implicitNodesMap = VectorMap.from(
      implicitNodeIds.map: nId =>
        nId -> nodeNoDefaults(nId, inheritedAttributes(nId).filterNot(defaultNodeAttrs.contains))
    )

    ViewerGraphElements(
      // explicit nodes take precedence over implicit ones
      nodes = implicitNodesMap ++ finalNodesMap,
      arrows = arrowsMap,
      memberships = allMemberships,
      groups = groups.map(g => g.id -> g).toMap,
      graphAttributes = attributes.filterKeys(_ in graphAttrIds),
      defaultNodeAttributes = defaultNodeAttrs,
      defaultArrowAttributes = defaultArrowAttrs,
      defaultGroupAttributes = attributes.filterKeys(_ notIn graphAttrIds)
    )
