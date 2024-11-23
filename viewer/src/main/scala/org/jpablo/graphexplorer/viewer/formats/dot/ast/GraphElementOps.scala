package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerNode}

import scala.annotation.tailrec

extension (graphElement: GraphElement)

  // add an attribute [id=$nextId] to all edges
  def attachId: GraphElement =
    graphElement match
      case EdgeStmt(edgeList, attrList) =>
        val edgeListWithIds = edgeList.map:
          case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
          case other                  => other

        EdgeStmt(edgeListWithIds, Attr("id", EdgeStmt.nextId.toString) :: attrList)

      case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
      case other                  => other

  def findAllViewerNodes: Set[ViewerNode] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Map[String, Map[String, String]]): Map[String, Map[String, String]] =
      remaining match
        case Nil => acc

        case EdgeStmt(edgeList, _) :: tail =>
          val edgeChildren = edgeList.flatMap:
            case n: DotNodeId => List(NodeStmt(n, Nil))
            case s: Subgraph  => s.children
          loop(remaining = edgeChildren ++ tail, acc = acc)

        case NodeStmt(nodeId, attr_list) :: tail =>
          val attrMap = toAttrsMap(attr_list)
          loop(
            remaining = tail,
            acc       = acc.updatedWith(nodeId.id)(_.fold(Some(attrMap))(existing => Some(existing ++ attrMap)))
          )

        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)

        case _ :: tail => loop(remaining = tail, acc = acc)

    loop(List(graphElement), Map.empty)
      .map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
      .toSet

  def findAllArrows: Set[Arrow] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case e: EdgeStmt =>
              // TODO: Review if we actually need to process remaining2
              val (edgeChildren, edgeArrows) = e.allArrows1.unzip
              loop(remaining = edgeChildren.flatten ++ remaining1, acc = acc ++ edgeArrows.toSet.flatten)

            case Subgraph(children, _) => loop(remaining = children ++ remaining1, acc = acc)
            case _                     => loop(remaining = remaining1, acc = acc)

    loop(List(graphElement))

  // TODO: make this tail recursive
  def removeGraphNodes(idsToRemove: Set[String]): Option[GraphElement] =
    graphElement match
      case n: NodeStmt if n.node_id.id in idsToRemove => None

      case EdgeStmt(edgeList, attrList) =>
        val remainingEdges: List[EdgeElement] = edgeList
          .map:
            // embed DotNodeId in a NodeStmt to have a common type (GraphElement)
            case n: DotNodeId => NodeStmt(n, Nil)
            case s: Subgraph  => s
          .flatMap(_.removeGraphNodes(idsToRemove))
          .map:
            // extract the NodeStmt to conform to EdgeElement = NodeStmt | Subgraph
            case n: NodeStmt => n.node_id
            case g: Subgraph => g
            // if it happens it's a bug!
            case other => throw Exception(s"Unexpected element in edge list: $other")

        // Is this faster than `remainingEdges.length < 2` ?
        remainingEdges match
          case _ :: _ :: _ => Some(EdgeStmt(remainingEdges, attrList))
          case _           => None

      case Subgraph(children, id) =>
        val remainingChildren = children.flatMap(_.removeGraphNodes(idsToRemove))
        if remainingChildren.isEmpty then None else Some(Subgraph(remainingChildren, id))

      case other => Some(other)

  def removeGraphNodes1(idsToRemove: Set[String]): List[GraphElement] =
    dom.console.log(s"\t-> removeGraphNodes1")
    val flattened = flattenPostOrder(Some(graphElement), Nil, Nil)
    dom.console.log(s"\t<-")
    pprint.log(flattened)
    // remove the nodes and edges that are not needed and reconstruct the graph
    flattened.foldLeft(List.empty[GraphElement]) { (stack, elem: GraphElement) =>
      elem match
        case EdgeStmt(_, attr_list) =>
          val remainingEdges: List[EdgeElement] =
            stack.reverse.map:
              // extract the NodeStmt to conform to EdgeElement = NodeStmt | Subgraph
              case n: NodeStmt => n.node_id
              case g: Subgraph => g
              // if it happens it's a bug!
              case other => throw Exception(s"Unexpected element in edge list: $other")
          List(EdgeStmt(remainingEdges, attr_list))

        case Subgraph(_, id)          => List(Subgraph(stack.reverse, id))
        case n @ NodeStmt(node_id, _) => if node_id.id in idsToRemove then stack else n :: stack
        case n                        => n :: stack
    }

@tailrec
def flattenPostOrder(
    root:    Option[GraphElement],
    pending: List[(GraphElement, List[GraphElement])], // Stack of (Root, Child*)
    acc:     List[GraphElement]                                // nodes without the idsToRemove
): List[GraphElement] =
  pprint.log((root, pending, acc))
  (root, pending) match
    // -----------------------------------
    // processing non-leaf nodes:
    // - descend to the first child
    // - add the rest of the children to the pending stack, alongside the current node
    // -----------------------------------
    case (e @ Some(edge @ EdgeStmt(_, _)), _) =>
      val h :: t = edge.toGraphElements: @unchecked
      flattenPostOrder(root = Some(h), pending = (edge, t) :: pending, acc)

    case (s @ Some(sub @ Subgraph(h :: t, _)), _) =>
      flattenPostOrder(root = Some(h), pending = (sub, t) :: pending, acc)

    // for leaf nodes we add a single None children, to simulate the case of nullable children
    case (Some(l), _) =>
      flattenPostOrder(root = None, pending = (l, Nil) :: pending, acc)

    // -----------------------------------
    // processing leaf nodes, backtracking
    // -----------------------------------
    case (None, (elem, deps) :: t) =>
      // are there any dependencies to be handled for elem?
      deps match
        case Nil             => flattenPostOrder(root = None, pending = t, acc = elem :: acc)
        case dep :: moreDeps => flattenPostOrder(root = Some(dep), pending = (elem, moreDeps) :: t, acc)
    // -----------------------------------
    // Done
    // -----------------------------------
    case (n, Nil) => (n.toList ++ acc).reverse
