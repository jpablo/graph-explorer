package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe

import scala.annotation.tailrec

enum AttributeTarget:
  case node, edge, graph

def randomId(): String = randomUUIDSafe()

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    ast.id match
      case Some(id) =>
        EdgeStmt.resetId()
        val flattened = ast.toFlattenedElements
        val viewerGraphData = flattened.toViewerGraphData
        ViewerGraph(id, viewerGraphData, ast.tpe)
      case None =>
        throw new IllegalArgumentException("DotAST must have an id")

  private def subGraphToViewerGroup(subId: String, sub: SubGraph): ViewerGroup =
    val attrs = sub.findAttributes
    ViewerGroup(
      id        = GroupId(subId),
      attrs     = Attributes(attrs.getOrElse(AttributeTarget.graph, Map.empty)),
      edgeAttrs = Attributes(attrs.getOrElse(AttributeTarget.edge, Map.empty)),
      nodeAttrs = Attributes(attrs.getOrElse(AttributeTarget.node, Map.empty))
    )

  def toFlattenedElements: FlattenedGraphElement =
    @tailrec
    def loop(
        remaining:   List[(Option[String], List[GraphElement])],
        arrows:      List[Arrow],
        groups:      List[ViewerGroup],
        nodes:       List[ViewerNode],
        memberships: List[(String, String)] = Nil // List of (element, group) memberships
    ): FlattenedGraphElement =
      remaining match
        case Nil =>
          // Convert accumulated node attributes to ViewerNodes at the end
          FlattenedGraphElement(
            rootId      = GroupId(ast.id.getOrElse("G")),
            arrows      = arrows,
            groups      = groups.reverse,
            nodes       = nodes.reverse,
            memberships = memberships.map((id, parent) => NodeId(id) -> GroupId(parent))
          )

        case (_, Nil) :: t =>
          loop(remaining = t, arrows, groups, nodes, memberships)

        // firstChild and children belong to the same parent node
        case (parent, firstChild :: children) :: t => // remaining
          firstChild match

            case sub @ SubGraph(subChildren, id) =>
              val subId = id.getOrElse(SubGraph.randomId())
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
                memberships = parent.fold(memberships)(p => edgeArrows.flatten.map(_.id.value -> p) ++ memberships)
              )

            case NodeStmt(nodeId, attr_list) =>
              loop(
                remaining   = (parent -> children) :: t,
                arrows      = arrows,
                groups      = groups,
                nodes       = ViewerNode(NodeId(nodeId.id), Attributes(toAttrsMap(attr_list))) :: nodes,
                memberships = parent.fold(memberships)(p => (nodeId.id -> p) :: memberships)
              )

            case _ =>
              loop(remaining = (parent -> children) :: t, arrows, groups, nodes, memberships)

    loop(remaining = List(None -> ast.children), Nil, Nil, Nil)

//  def setDefaultTheme: DotAST =
//    ast.modify(_.children).using: children =>
//      Newline() :: Pad() :: AttrStmt("node", List(Attr("style", "filled"))) :: children

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => loop(t, state)
        case h :: t                     => loop(t, h :: state)
        case Nil                        => state.reverse

    ast.modify(_.children).using(loop(_))
