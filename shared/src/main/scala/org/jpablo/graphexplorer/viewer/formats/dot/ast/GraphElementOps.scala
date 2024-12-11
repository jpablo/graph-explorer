package org.jpablo.graphexplorer.viewer.formats.dot.ast

//import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec

extension (graphElement: GraphElement)

  // add an attribute [id=$nextId] to all edges
  def attachId: GraphElement =
    graphElement match
      case EdgeStmt(edgeList, attrList) =>
        val edgeListWithIds = edgeList.map:
          case SubGraph(children, id) => SubGraph(children.map(_.attachId), id)
          case other                  => other

        EdgeStmt(edgeListWithIds, Attr(idAttributeKey, AttrValue(EdgeStmt.nextId().toString)) :: attrList)

      case SubGraph(children, id) => SubGraph(children.map(_.attachId), id)
      case other                  => other

  def findAllViewerNodes: Set[ViewerNode] =
    @tailrec
    def loop(
        remaining: List[GraphElement],
        acc:       Map[String, Map[String, AttrValue]]
    ): Map[String, Map[String, AttrValue]] =
      remaining match
        case Nil => acc

        case (e: EdgeStmt) :: t         => loop(remaining = e.toGraphElements ++ t, acc)
        case SubGraph(children, _) :: t => loop(remaining = children ++ t, acc)

        case NodeStmt(nodeId, attr_list) :: t =>
          val attrMap = toAttrsMap(attr_list)
          loop(
            remaining = t,
            acc       = acc.updatedWith(nodeId.id)(_.fold(Some(attrMap))(existing => Some(existing ++ attrMap)))
          )

        case _ :: t => loop(remaining = t, acc)

    loop(List(graphElement), Map.empty)
      .map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
      .toSet

  // Helper function to convert SubGraph to ViewerGroup
  private def convertSubGraphToViewerGroup(sub: SubGraph): ViewerGroup =
    val attrs = sub.findAttributes
    ViewerGroup(
      id        = NodeId(sub.id.getOrElse("G")), // TODO: Generate a unique ID for the group if not provided
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
        nodes:       List[(String, Map[String, AttrValue])],
        memberships: List[(String, Option[String])] = Nil // List of (element, group) memberships
    ): FlattenedGraphElement =
//      pprint.log(remaining)
      remaining match
        case Nil =>
          // Convert accumulated node attributes to ViewerNodes at the end
          val viewerNodes =
            nodes.map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
          val membershipsNodes = memberships.map((id, parent) => NodeId(id) -> parent.map(NodeId(_)))
          FlattenedGraphElement(arrows, groups.reverse, viewerNodes.reverse, membershipsNodes)

        case (_, Nil) :: t =>
          loop(remaining = t, arrows, groups, nodes, memberships)

        // firstChild and parentOtherChildren belong to the same parent node
        case (parent, firstChild :: parentOtherChildren) :: t => // remaining
          firstChild match
            case sub @ SubGraph(subChildren, _) =>
              val subId = sub.id.getOrElse(SubGraph.randomId())
              val rem = (Some(subId) -> subChildren) :: ((parent -> parentOtherChildren) :: t)
              val group = convertSubGraphToViewerGroup(sub)
              val mms = (subId -> parent) :: memberships
              // 1. Add the current subgraph to the groups
              // 2. Add the children to the remaining list
              loop(
                remaining   = rem,
                arrows      = arrows,
                groups      = group :: groups,
                nodes       = nodes,
                memberships = mms
              )

            case e: EdgeStmt =>
              val edgeArrows = e.expandArrows
              val mbs = edgeArrows.flatten.map(_.id.value -> parent) ++ memberships
              loop(
                remaining   = (parent -> parentOtherChildren) :: t,
                arrows      = arrows ++ edgeArrows.flatten,
                groups      = groups,
                nodes       = nodes,
                memberships = mbs
              )

            case NodeStmt(nodeId, attr_list) =>
              val attrMap = toAttrsMap(attr_list)
              loop(
                remaining   = (parent -> parentOtherChildren) :: t,
                arrows      = arrows,
                groups      = groups,
                nodes       = (nodeId.id -> attrMap) :: nodes,
                memberships = (nodeId.id -> parent) :: memberships
              )

            case _ =>
              loop(remaining = (parent -> parentOtherChildren) :: t, arrows, groups, nodes, memberships)

    loop(remaining = List(None -> List(graphElement)), Nil, Nil, Nil)

end extension
