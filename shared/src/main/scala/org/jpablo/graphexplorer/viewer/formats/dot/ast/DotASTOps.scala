package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.*
import scala.annotation.tailrec

enum AttributeTarget derives CanEqual:
  case node, edge, graph

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    ast.id match
      case Some(id) =>
        EdgeStmt.resetId()
        val flattened = ast.toFlattenedElements
        val viewerGraphData = ViewerGraphData.from(flattened)
        ViewerGraph(viewerGraphData.expandStyleAttributes, id, ast.tpe)
      case None =>
        throw new IllegalArgumentException("DotAST must have an id")

  private def subGraphToViewerGroup(subId: GroupId, sub: SubGraph): ViewerGroup =
    val attrs = sub.findAttributes
    ViewerGroup(
      id         = subId,
      attributes = Attributes(attrs.getOrElse(AttributeTarget.graph, Map.empty)),
      edgeAttrs  = Attributes(attrs.getOrElse(AttributeTarget.edge, Map.empty)),
      nodeAttrs  = Attributes(attrs.getOrElse(AttributeTarget.node, Map.empty))
    )

  def toFlattenedElements: FlattenedGraphElement =
    @tailrec
    def loop(
        remaining:   List[(Option[GroupId], List[GraphElement])],
        arrows:      List[Arrow],
        groups:      List[ViewerGroup],
        nodes:       List[ViewerNode],
        memberships: List[(ElementId, GroupId)] // List of (element, group) memberships
    ): FlattenedGraphElement =
      remaining match
        case Nil =>
          val rootId = GroupId(ast.id.getOrElse("G"))
          val graphGroup = subGraphToViewerGroup(rootId, ast.asSubgraph)
          FlattenedGraphElement(
            rootId      = rootId,
            arrows      = arrows,
            groups      = (graphGroup :: groups).reverse,
            nodes       = nodes.reverse,
            memberships = memberships.reverse
          )

        case (_, Nil) :: t =>
          loop(remaining = t, arrows, groups, nodes, memberships)

        // firstChild and children belong to the same parent node
        case (parent, firstChild :: children) :: t => // remaining
          firstChild match

            case sub @ SubGraph(subChildren, id) =>
              val subId = GroupId(id.getOrElse(SubGraph.randomId()))
              loop(
                remaining   = (Some(subId) -> subChildren) :: ((parent -> children) :: t),
                arrows      = arrows,
                groups      = subGraphToViewerGroup(subId, sub) :: groups,
                nodes       = nodes,
                memberships = parent.fold(memberships)(p => (subId -> p) :: memberships)
              )

            case e: EdgeStmt =>
              val edgeArrows = e.expandArrows
              loop(
                remaining   = (parent -> children) :: t,
                arrows      = arrows ++ edgeArrows.flatten,
                groups      = groups,
                nodes       = nodes,
                memberships = parent.fold(memberships)(p => edgeArrows.flatten.map(_.id -> p) ++ memberships)
              )

            case NodeStmt(dotNodeId, attr_list) =>
              val nodeId = NodeId(dotNodeId.id)
              loop(
                remaining   = (parent -> children) :: t,
                arrows      = arrows,
                groups      = groups,
                nodes       = ViewerNode(nodeId, Attributes(toAttrsMap(attr_list))) :: nodes,
                memberships = parent.fold(memberships)(p => (nodeId -> p) :: memberships)
              )

            case _ =>
              loop(remaining = (parent -> children) :: t, arrows, groups, nodes, memberships)

    loop(
      remaining   = List(None -> ast.children),
      arrows      = Nil,
      groups      = Nil,
      nodes       = Nil,
      memberships = Nil
    )

//  def setDefaultTheme: DotAST =
//    ast.modify(_.children).using: children =>
//      Newline() :: Pad() :: AttrStmt("node", List(Attr("style", "filled"))) :: children

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(List(), _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t       => loop(t, state)
        case h :: t                        => loop(t, h :: state)
        case Nil                           => state.reverse

    ast.modify(_.children).using(loop(_))
