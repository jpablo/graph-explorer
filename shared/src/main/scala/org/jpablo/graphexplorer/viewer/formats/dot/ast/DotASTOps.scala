package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat.DotFormatter
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

import scala.annotation.tailrec

enum AttributeTarget derives CanEqual:
  case node, edge, graph

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    ast.id match
      case Some(id) =>
        // TODO: should resetId be called inside toViewerGraphElements?
        EdgeStmt.resetId()
        val g = ViewerGraph(elements = ast.toViewerGraphElements, id = id, tpe = GraphType.valueOf(ast.tpe))
        g.modifyElements.setTo(g.expandStyleAttributes)

      case None =>
        throw new IllegalArgumentException("DotAST must have an id")

  private def nodeStmtToViewerNode(nodeStmt: NodeStmt): ViewerNode =
    node(
      nodeId = NodeId(nodeStmt.node_id.id),
      attributes = Attributes(toAttrsMap(nodeStmt.attr_list))
    )

  private def subGraphToViewerGroup(sub: SubGraph, gId: Option[GroupId] = None): ViewerGroup =
    val attrs = sub.collectAttributesByTarget
    group(
      groupId = gId.getOrElse(GroupId(sub.id.getOrElse(SubGraph.randomId()))),
      attributes = Attributes(attrs.getOrElse(AttributeTarget.graph, Map.empty))
      // arrow and node attributes in a subGraph are not supported in the viewer
      // TODO: copy the attributes to each element!
//      arrowAttrs = Attributes(attrs.getOrElse(AttributeTarget.edge, Map.empty)),
//      nodeAttrs = Attributes(attrs.getOrElse(AttributeTarget.node, Map.empty))
    )

  private def buildViewerGraphElements(
      nodes:       List[ViewerNode],
      arrows:      List[Arrow],
      memberships: List[(ElementId, GroupId)], // List of (element, group) memberships
      groups:      List[ViewerGroup]
  ): ViewerGraphElements =
    val arrowEndpoints  = arrows.flatMap(_.endpoints).toSet
    val nodesMap        = nodes.map(n => n.id -> n).toMap
    val implicitNodeIds = arrowEndpoints -- nodesMap.keySet

    // DOT allows arrows within clusters, we don't.
    val filteredMemberships =
      memberships.foldLeft(Map.empty[GroupMemberId, GroupId]):
        case (acc, (memId, groupId)) =>
          memId match
            case m: GroupMemberId => acc + (m -> groupId)
            case _                => acc

    ViewerGraphElements(
      nodes = nodesMap ++ implicitNodeIds.map(n => n -> node(n)),
      arrows = arrows.map(a => a.id -> a).toMap,
      memberships = filteredMemberships,
      groups = groups.map(g => g.id -> g).toMap
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
        memberships:   List[(ElementId, GroupId)] // List of (element, group) memberships
    ): ViewerGraphElements =
      pendingGroups match

        case Nil => buildViewerGraphElements(nodes.reverse, arrows, memberships.reverse, groups.reverse)

        // corner case: a groupId without children. Skipping it.
        case (_, Nil) :: t => loop(t, arrows, groups, nodes, memberships)

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
                memberships = groupId.fold(memberships)(gId => (viewerGroup.id -> gId) :: memberships)
              )

            case e: EdgeStmt =>
              val edgeArrows = e.expandArrows
              loop(
                pendingGroups = pendingGroups,
                arrows = arrows ++ edgeArrows,
                groups = groups,
                nodes = nodes,
                memberships = groupId.fold(memberships)(gId => edgeArrows.map(_.id -> gId) ++ memberships)
              )

            case n: NodeStmt =>
              val viewerNode = nodeStmtToViewerNode(n)
              loop(
                pendingGroups = pendingGroups,
                arrows = arrows,
                groups = groups,
                nodes = viewerNode :: nodes,
                memberships = groupId.fold(memberships)(gId => (viewerNode.id -> gId) :: memberships)
              )

            case _ =>
              loop(pendingGroups, arrows, groups, nodes, memberships)

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
