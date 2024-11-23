package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
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

        EdgeStmt(edgeListWithIds, Attr(idAttributeKey, EdgeStmt.nextId.toString) :: attrList)

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
              val (edgeChildren, edgeArrows) = e.expandArrows.unzip
              loop(remaining = edgeChildren.flatten ++ remaining1, acc = acc ++ edgeArrows.toSet.flatten)

            case Subgraph(children, _) => loop(remaining = children ++ remaining1, acc = acc)
            case _                     => loop(remaining = remaining1, acc = acc)

    loop(List(graphElement))

  def removeGraphNodes(idsToRemove: Set[String]): List[GraphElement] =
    // remove the nodes and edges that are not needed and reconstruct the graph
    val filtered =
      flattenPostOrder(
        Some(graphElement),
        {
          case (NodeStmt(node_id, _), acc) if node_id.id in idsToRemove => acc
          case (n, acc)                                                 => n :: acc

        }
      )
    reconstructGraph(filtered)

def reconstructGraph(elements: List[GraphElement]): List[GraphElement] =
  elements
    .foldLeft(Nil: List[GraphElement]):
      // all children removed, remove the parent as well
      case (Nil, _: Subgraph) => Nil
      // remove edges with zero or one node
      case (Nil, _: EdgeStmt)      => Nil
      case (_ :: Nil, _: EdgeStmt) => Nil
      // reconstruct non-terminal nodes with filtered children
      case (stack, Subgraph(_, id))        => List(Subgraph(stack.reverse, id))
      case (stack, EdgeStmt(_, attr_list)) => List(EdgeStmt(toEdgeElements(stack.reverse), attr_list))
      // add all remaining leaf nodes
      case (stack, n) => n :: stack
    .reverse

def toEdgeElements(elems: List[GraphElement]): List[EdgeElement] =
  elems.map:
    // extract the NodeStmt to conform to EdgeElement = NodeStmt | Subgraph
    case n: NodeStmt => n.node_id
    case g: Subgraph => g
    // if it happens it's a bug!
    case other => throw Exception(s"Unexpected element in edge list: $other")

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

    case (Some(sub @ Subgraph(h :: t, _)), _) =>
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
