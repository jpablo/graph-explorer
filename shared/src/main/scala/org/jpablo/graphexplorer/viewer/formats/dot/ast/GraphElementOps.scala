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

  def findAllSubGraphs: Set[SubGraph] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[SubGraph] = Set.empty): Set[SubGraph] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case sub @ SubGraph(children, _) =>
              // Add current subgraph to accumulator and process its children
              loop(
                remaining = children ++ remaining1,
                acc       = acc + sub
              )
            case EdgeStmt(edgeList, _) =>
              // Process subgraphs in edge list
              val subgraphChildren = edgeList.collect {
                case s: SubGraph => s.children
              }.flatten
              loop(
                remaining = subgraphChildren ++ remaining1,
                acc       = acc
              )
            case _ =>
              loop(remaining = remaining1, acc = acc)

    loop(List(graphElement))

  def findAllArrows: Set[Arrow] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: t =>
          h match
            case e: EdgeStmt =>
              // TODO: Review if we actually need to process remaining2
              val (edgeChildren, edgeArrows) = e.expandArrows.unzip
              loop(remaining = edgeChildren.flatten ++ t, acc = acc ++ edgeArrows.toSet.flatten)

            case SubGraph(children, _) => loop(remaining = children ++ t, acc = acc)
            case _                     => loop(remaining = t, acc = acc)

    loop(List(graphElement))

  // Helper function to convert SubGraph to ViewerGroup
  private def convertSubGraphToViewerGroup(sub: SubGraph): ViewerGroup =
    val attrs = sub.findAttributes
    ViewerGroup(
      id        = NodeId(sub.id.getOrElse("G")), // TODO: Generate a unique ID for the group if not provided
      attrs     = Attributes(attrs.getOrElse(AttributeTarget.graph, Map.empty)),
      edgeAttrs = Attributes(attrs.getOrElse(AttributeTarget.edge, Map.empty)),
      nodeAttrs = Attributes(attrs.getOrElse(AttributeTarget.node, Map.empty))
    )

  def findAllElements: (Set[Arrow], Set[ViewerGroup], Set[ViewerNode]) =
    @tailrec
    def loop(
        remaining: List[GraphElement],
        arrows:    Set[Arrow] = Set.empty,
        groups:    Set[ViewerGroup] = Set.empty,
        nodes:     Map[String, Map[String, String]] = Map.empty
    ): (Set[Arrow], Set[ViewerGroup], Set[ViewerNode]) =
      remaining match
        case Nil =>
          // Convert accumulated node attributes to ViewerNodes at the end
          val viewerNodes = nodes.map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs))).toSet
          (arrows, groups, viewerNodes)

        case h :: t =>
          h match
            case sub @ SubGraph(children, _) =>
              loop(
                remaining = children ++ t,
                arrows    = arrows,
                groups    = groups + convertSubGraphToViewerGroup(sub),
                nodes     = nodes
              )

            case e: EdgeStmt =>
              val (edgeChildren, edgeArrows) = e.expandArrows.unzip
              val newArrows = arrows ++ edgeArrows.toSet.flatten

              // Process edge nodes
              val edgeNodes = e.edge_list.flatMap {
                case n: DotNodeId => List(NodeStmt(n, Nil))
                case s: SubGraph  => s.children
              }

              loop(
                remaining = edgeChildren.flatten ++ edgeNodes ++ t,
                arrows    = newArrows,
                groups    = groups,
                nodes     = nodes
              )

            case NodeStmt(nodeId, attr_list) =>
              val attrMap = toAttrsMap(attr_list)
              loop(
                remaining = t,
                arrows    = arrows,
                groups    = groups,
                nodes     = nodes.updatedWith(nodeId.id)(_.fold(Some(attrMap))(existing => Some(existing ++ attrMap)))
              )

            case _ =>
              loop(
                remaining = t,
                arrows    = arrows,
                groups    = groups,
                nodes     = nodes
              )

    loop(List(graphElement))

  def findAllDirectChildren
      : (List[(Option[NodeId], Arrow)], List[(Option[NodeId], ViewerGroup)], List[(Option[NodeId], ViewerNode)]) =
    @tailrec
    def loop(
        remaining: List[(Option[String], List[GraphElement])],
        arrows:    List[(Option[String], Arrow)],
        groups:    List[(Option[String], ViewerGroup)],
        nodes:     List[((Option[String], String), Map[String, String])]
    ): (List[(Option[NodeId], Arrow)], List[(Option[NodeId], ViewerGroup)], List[(Option[NodeId], ViewerNode)]) =
//      pprint.log((remaining.length, arrows.length, groups.length, nodes.length), "loop")
      remaining match
        case Nil =>
//          pprint.log("empty remaining")
          // Convert accumulated node attributes to ViewerNodes at the end
          val viewerNodes =
            nodes.map((id, attrs) => id._1.map(NodeId(_)) -> ViewerNode(NodeId(id._2), Attributes(attrs)))
          val arrowNodes = arrows.map((id, arrow) => id.map(NodeId(_)) -> arrow)
          val groupNodes = groups.map((id, group) => id.map(NodeId(_)) -> group)
          (arrowNodes.reverse, groupNodes.reverse, viewerNodes.reverse)

        case (_, Nil) :: t =>
//          pprint.log(parent, "empty children")
          loop(remaining = t, arrows, groups, nodes)

        // firstChild and parentOtherChildren belong to the same parent node
        case (parent, firstChild :: parentOtherChildren) :: t => // remaining
//          pprint.log(parent, "remaining children")
          firstChild match
            case sub @ SubGraph(subChildren, _) =>
//              println("SubGraph")
              val rem = (sub.id -> subChildren) :: ((parent -> parentOtherChildren) :: t)
              val gps = (parent -> convertSubGraphToViewerGroup(sub)) :: groups
//              pprint.log(rem.length, showFieldNames = true)
              // 1. Add the current subgraph to the groups
              // 2. Add the children to the remaining list
              loop(
                remaining = rem,
                arrows    = arrows,
                groups    = gps,
                nodes     = nodes
              )

            case e: EdgeStmt =>
//              println("EdgeStmt")
              val (edgeChildren, edgeArrows) = e.expandArrows.unzip

              loop(
                remaining = (parent -> parentOtherChildren) :: t,
                arrows    = edgeArrows.flatten.map(parent -> _) ++ arrows,
                groups    = groups,
                nodes     = nodes
              )

            case NodeStmt(nodeId, attr_list) =>
//              println("NodeStmt")
              val attrMap = toAttrsMap(attr_list)
              loop(
                remaining = (parent -> parentOtherChildren) :: t,
                arrows    = arrows,
                groups    = groups,
                nodes     = (parent -> nodeId.id, attrMap) :: nodes
              )

            case _ =>
              loop(remaining = (parent -> parentOtherChildren) :: t, arrows, groups, nodes)

    loop(remaining = List(None -> List(graphElement)), Nil, Nil, Nil)

  def removeGraphNodes(idsToRemove: Set[String], debug: Boolean = false): List[GraphElement] =
    // remove the nodes and edges that are not needed and reconstruct the graph
    val filtered =
      flattenPostOrder(
        Some(graphElement),
        // this is a foldRight, basically
        {
          case (n @ NodeStmt(node_id, _), acc) =>
            if debug then pprint.log(n, "removeGraphNodes", showFieldNames = false)
//            if node_id.id in idsToRemove then Nil else n :: acc
            n :: acc
          case (e @ EdgeStmt(List(DotNodeId(v1, _), DotNodeId(v2, _)), attrs), acc) =>
            if debug then pprint.log(e, "removeGraphNodes", showFieldNames = false)
//            pprint.log(e.allArrows)
//            val a = Arrow((v1, v2), toAttrsMap(attrs))
//            if a.nodeId.value in idsToRemove then
//              acc
//            else
            e :: acc
          case (n, acc) =>
            if debug then pprint.log(n, "removeGraphNodes", showFieldNames = false)
            n :: acc

        }
      )
    if debug then pprint.log(filtered, "[removeGraphNodes]", showFieldNames = false)
    reconstructGraph(filtered)

def reconstructGraph(elements: List[GraphElement]): List[GraphElement] =
  // stack contains the children of the next non-terminal node
  elements
    .foldLeft(Nil: List[GraphElement]):
      // all children removed, remove the parent as well
      case (Nil, _: SubGraph) => Nil
      // remove edges with zero or one children left
      case (Nil, _: EdgeStmt)      => Nil
      case (_ :: Nil, _: EdgeStmt) => Nil
      // reconstruct non-terminal nodes with filtered children
      case (stack, SubGraph(_, id))        => List(SubGraph(stack.reverse, id))
      case (stack, EdgeStmt(_, attr_list)) => List(EdgeStmt(toEdgeElements(stack.reverse), attr_list))
      // add all remaining leaf nodes
      case (stack, n) => n :: stack
    .reverse

def toEdgeElements(elems: List[GraphElement]): List[EdgeElement] =
  elems.map:
    // extract the NodeStmt to conform to EdgeElement = NodeStmt | Subgraph
    case n: NodeStmt => n.node_id
    case g: SubGraph => g
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
