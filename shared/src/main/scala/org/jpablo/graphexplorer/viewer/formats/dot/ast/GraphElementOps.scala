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

        EdgeStmt(edgeListWithIds, Attr(idAttributeKey, EdgeStmt.nextId.toString) :: attrList)

      case SubGraph(children, id) => SubGraph(children.map(_.attachId), id)
      case other                  => other

  def findAllViewerNodes: Set[ViewerNode] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Map[String, Map[String, String]]): Map[String, Map[String, String]] =
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

  def findAllDirectChildren: FlattenedGraphElement =
    @tailrec
    def loop(
        remaining:   List[(Option[String], List[GraphElement])],
        arrows:      List[Arrow],
        groups:      List[ViewerGroup],
        nodes:       List[(String, Map[String, String])],
        memberships: List[(String, Option[String])] = Nil // List of (element, group) memberships
    ): FlattenedGraphElement =
//      pprint.log(memberships, showFieldNames = false)
//      pprint.log((remaining.length, arrows.length, groups.length, nodes.length), "loop")
      remaining match
        case Nil =>
//          pprint.log("empty remaining")
          // Convert accumulated node attributes to ViewerNodes at the end
          val viewerNodes =
            nodes.map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
          val membershipsNodes = memberships.map((id, parent) => NodeId(id) -> parent.map(NodeId(_)))
          FlattenedGraphElement(arrows.reverse, groups.reverse, viewerNodes.reverse, membershipsNodes.reverse)

        case (_, Nil) :: t =>
//          pprint.log(parent, "empty children")
          loop(remaining = t, arrows, groups, nodes, memberships)

        // firstChild and parentOtherChildren belong to the same parent node
        case (parent, firstChild :: parentOtherChildren) :: t => // remaining
//          pprint.log(parent, "remaining children")
          firstChild match
            case sub @ SubGraph(subChildren, _) =>
//              println("SubGraph")
              val subId = sub.id.getOrElse(SubGraph.randomId())
              val rem = (Some(subId) -> subChildren) :: ((parent -> parentOtherChildren) :: t)
              val group = convertSubGraphToViewerGroup(sub)
              val mms = (subId -> parent) :: memberships
//              pprint.log(rem.length, showFieldNames = true)
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
//              println("EdgeStmt")
              val (edgeChildren, edgeArrows) = e.expandArrows.unzip

              // missing e.idAttr!!
              val mbs = edgeArrows.flatten.map(_.id.value -> parent) ++ memberships
              loop(
                remaining   = (parent -> parentOtherChildren) :: t,
                arrows      = edgeArrows.flatten ++ arrows,
                groups      = groups,
                nodes       = nodes,
                memberships = mbs
              )

            case NodeStmt(nodeId, attr_list) =>
//              println("NodeStmt")
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

@tailrec
def flattenPostOrder(
    root:    Option[GraphElement],
    fn:      (GraphElement, List[GraphElement]) => List[GraphElement],
    pending: List[(GraphElement, List[GraphElement])] = Nil, // Stack of (Root, List[Child])
    acc:     List[GraphElement] = Nil                        // result flattened tree in post-order
): List[GraphElement] =
  (root, pending) match
    // -----------------------------------
    // processing non-leaf nodes:
    // - descend to the first child
    // - add the rest of the children to the pending stack, alongside the current node
    // -----------------------------------
    case (Some(edge @ EdgeStmt(_, _)), _) =>
      val h :: t = edge.toGraphElements: @unchecked
      flattenPostOrder(root = Some(h), fn, pending = (edge, t) :: pending, acc)

    case (Some(sub @ SubGraph(h :: t, _)), _) =>
      flattenPostOrder(root = Some(h), fn, pending = (sub, t) :: pending, acc)

    // for leaf nodes we add a single None children, to simulate the case of nullable children
    case (Some(leaf), _) =>
      flattenPostOrder(root = None, fn, pending = (leaf, Nil) :: pending, acc)
    // -----------------------------------
    // processing leaf nodes, backtracking
    // -----------------------------------
    case (None, (elem, deps) :: t) =>
      // are there any dependencies to be handled for elem?
      deps match
        case Nil             => flattenPostOrder(root = None, fn, pending = t, acc = fn(elem, acc))
        case dep :: moreDeps => flattenPostOrder(root = Some(dep), fn, pending = (elem, moreDeps) :: t, acc)
    // -----------------------------------
    // Done
    // -----------------------------------
    case (n, Nil) => (n.toList ++ acc).reverse
