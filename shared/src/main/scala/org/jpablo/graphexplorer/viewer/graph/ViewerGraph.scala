package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.numberToLetterId
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Multiple, Single}

import scala.annotation.tailrec

/** Represents a graph that can be visualized in the viewer.
  */
case class ViewerGraph(
    data:    ViewerGraphData = ViewerGraphData.minimal,
    id:      String = ViewerGraphData.defaultRootId.value,
    tpe:     String = "digraph",
    counter: Int = 0
) derives CanEqual:

  // --- mutable stuff ----
  private var nodeCounter = counter

  private def nextNodeId(): NodeId =
    @tailrec
    def nextAvailable(): NodeId =
      nodeCounter += 1
      val id = NodeId(numberToLetterId(nodeCounter))
      if id in nodeById then nextAvailable() else id
    nextAvailable()
  // --- end mutable stuff ----

  val root = data.root

  val nodeById = data.nodes
  val nodesSet = data.nodesSet
  val nodeIds = data.nodes.keySet
  lazy val nodesSeq = data.nodes.toSeq

  val arrowById = data.arrows
  val arrowsSet = data.arrowsSet
  val arrowIds = data.arrows.keySet
  lazy val arrowsSeq = data.arrows.toSeq

  val groupById = data.groups

  val allElementIds: ElementIds = ElementIds(data.nodes.keySet ++ arrowIds ++ data.groups.keySet)

  def summary =
    ViewerGraph.Summary(nodes = nodeById.size, arrows = arrowsSet.size)

  lazy val directSuccessors: Map[NodeId, Set[NodeId]] =
    arrowsSet
      .groupBy(_.source)
      .transform((_, ss) => ss.map(_.target))
      .withDefaultValue(Set.empty)

  lazy val directPredecessors: Map[NodeId, Set[NodeId]] =
    arrowsSet
      .groupBy(_.target)
      .transform((_, ss) => ss.map(_.source))
      .withDefaultValue(Set.empty)

  /** allNodeIds that are not in the target of any arrow
    */
  def roots: Set[NodeId] =
    nodeIds -- arrowsSet.map(_.target)

  /** Creates a diagram containing the given symbols and the arrows between them.
    *
    * It ignores groups and memberships.
    */
  private def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes = nodeById.filter((id, _) => id in ids)
    val foundNodeIds = foundNodes.keySet
    val relevantArrows = arrowById.filter((_, a) => (a.source in foundNodeIds) && (a.target in foundNodeIds))
    ViewerGraph(
      ViewerGraphData(
        rootId = data.rootId,
        nodes  = foundNodes,
        arrows = relevantArrows,
        groups = Map(data.rootId -> data.root)
      )
    )

  private val modifyData = this.modify(_.data)

  val modifyRootGraphAttrs = this.modify(_.data.groups.at(root.id).attributes)
  val modifyRootNodeAttrs = this.modify(_.data.groups.at(root.id).nodeAttrs)
  val modifyRootEdgeAttrs = this.modify(_.data.groups.at(root.id).edgeAttrs)

  lazy val removeUnsupportedFeatures: ViewerGraph =
    modifyRootGraphAttrs.using(_ - AttributeId("size"))

  val defaultNodeTheme =
    Attributes(
      Map(
        AttributeId("sides") -> AttrValue("5")
      )
    )

  val defaultEdgeTheme =
    Attributes(
      Map(
        AttributeId("dir")       -> AttrValue("both"),
        AttributeId("arrowtail") -> AttrValue("none")
      )
    )

  def setDefaultTheme: ViewerGraph =
    modifyRootAttributes(AttributeTarget.node).using(_ ++ defaultNodeTheme)
      .modifyRootAttributes(AttributeTarget.edge).using(_ ++ defaultEdgeTheme)

  def removeElements(toRemove: ElementIds): ViewerGraph =
    modifyData.using(_.removeElements(toRemove))

  def addEdge(source: NodeId, target: NodeId): (ViewerGraph, Arrow) =
    val (newData, arrow) = data.addArrow(source, target)
    (modifyData.setTo(newData), arrow)

  def addNode(nodeId: NodeId, groupId: Option[GroupId] = None): ViewerGraph =
    modifyData.using(_.addNode(nodeId, groupId))

  def addNodeAndEdgeFrom(source: NodeId): (ViewerGraph, NodeId) =
    val nodeId = nextNodeId()
    val sourceGroup = data.membership(source)
    val (newGraph, arrow) = addNode(nodeId, sourceGroup).addEdge(source, nodeId)
    (newGraph, nodeId)

  def addRandomNode(groupId: Option[GroupId] = None): (ViewerGraph, NodeId) =
    val nodeId = nextNodeId()
    (addNode(nodeId, groupId), nodeId)

  /** Creates a new group containing the specified nodes.
    *
    * Creates a new group with the given label and moves the specified nodes into it. Any nodes that were previously in
    * other groups will be moved to this new group. Empty groups that result from moving nodes will be removed.
    *
    * @param ids
    *   Set of node IDs to add to the new group
    * @param label
    *   Optional label for the new group, defaults to empty string
    * @return
    *   Updated ViewerGraph with the new group containing the specified nodes
    */
  def addToNewGroup(ids: ElementIds, label: String = ""): ViewerGraph =
    modifyData.using(_.addToNewGroup(ids, label))

  def addToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraph =
    modifyData.using(_.addToGroup(groupId, nodeIds))

  def ungroupSelection(ids: ElementIds): ViewerGraph =
    modifyData.using(_.ungroup(ids.filter(id => id.isNodeId || id.isGroupId)))

  def getRootAttributes(target: AttributeTarget): Attributes =
    target match
      case AttributeTarget.graph => root.attributes
      case AttributeTarget.node  => root.nodeAttrs
      case AttributeTarget.edge  => root.edgeAttrs

  def modifyRootAttributes(target: AttributeTarget) =
    target match
      case AttributeTarget.graph => modifyRootGraphAttrs
      case AttributeTarget.node  => modifyRootNodeAttrs
      case AttributeTarget.edge  => modifyRootEdgeAttrs

  def updateRootAttributes(target: AttributeTarget)(update: Attributes => Attributes): ViewerGraph =
    modifyRootAttributes(target).using(update)

  //
  private def mergeAttributes[K <: ElementId, V <: Attributable](
      nodeIds:       ElementIds,
      attributables: Map[K, V]
  ): Map[AttributeId, SelectionAttrValue] =
    attributables.foldLeft(Map.empty[AttributeId, SelectionAttrValue]):
      case (acc, (nodeId, attributable)) if nodeId in nodeIds =>
        val nodeIdAcc =
          // replace attribute values with Single / Multiple (if they are already in the accumulator and they are different)
          attributable.attributes.values.transform: (attrId, v) =>
            if (attrId in acc) && !acc(attrId).is(v) then Multiple else Single(v)
        acc ++ nodeIdAcc
      case (acc, _) => acc

  def getAttributesById(ids: ElementIds): AttributesUpdates =
    AttributesUpdates(
      ids.ids.headOption
        .map:
          case _: ArrowId => mergeAttributes(ids, data.arrows.map(identity))
          case _: GroupId => mergeAttributes(ids, data.groups.map(identity))
          case _: NodeId  => mergeAttributes(ids, data.nodes.map(identity))
        .getOrElse(Map.empty)
    )

  def updateAttributes(idsToUpdate: ElementIds, updates: AttributesUpdates): ViewerGraph =
    modifyData.using(_.updateAttributes(idsToUpdate, updates))

  /** Unfolds a set of ids using a function that returns the related ids.
    */
  def unfold(f: NodeId => Set[NodeId], ids0: Set[NodeId]): Set[NodeId] =
    // How efficient is this compared to a tail rec version?
    Set
      .unfold((ids0, Set.empty[NodeId])): (ids, visited) =>
        val newBatch = ids.flatMap(f) -- visited
        if newBatch.isEmpty then None
        else Some((newBatch, (newBatch, visited ++ newBatch)))
      .flatten

  private def subgraphUnfoldWith(f: NodeId => Set[NodeId])(ids: Set[NodeId]): ViewerGraph =
    subgraph(ids ++ unfold(f, ids))

  private def subgraphWith(f: NodeId => Set[NodeId])(ids: Set[NodeId]): ViewerGraph =
    subgraph(ids ++ ids.flatMap(f))

  val directSuccessorsGraph: Set[NodeId] => ViewerGraph = subgraphWith(directSuccessors)
  val directPredecessorsGraph: Set[NodeId] => ViewerGraph = subgraphWith(directPredecessors)

  val allSuccessorsGraph: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(directSuccessors)
  val allPredecessorsGraph: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(directPredecessors)

//  lazy val toTrees: Tree[ViewerNode] =
//    val paths =
//      for ns <- nodes.toList yield (ns.id.toString.split("/").init.toList, ns.label, ns)
//    Tree.fromPaths(paths, ".")

  /** Creates a new subdiagram with all the symbols containing the given String.
    */
  def filterByNodeId(str: String): ViewerGraph =
    val ids = nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase))
    subgraph(ids)

  def filterNodesBy(p: NodeId => Boolean): Set[NodeId] =
    nodeIds.filter(p)

  def filterArrowsBy(p: Arrow => Boolean) =
    arrowsSet.filter(p)

//  def toCSV: CSV =
//    CSV(
//      arrows
//        .map(a => Array(a.source.toString, a.target.toString))
//        .toArray
//    )
end ViewerGraph

object ViewerGraph:

  private def numberToLetterId(n: Int): String =
    if n <= 0 then throw IllegalArgumentException("Node ID number must be positive")
    else
      def toBase26(n: Int): List[Int] =
        if n == 0 then Nil
        else
          val adjusted = n - 1
          val quotient = adjusted / 26
          val remainder = adjusted % 26
          remainder :: (if quotient > 0 then toBase26(quotient) else Nil)

      toBase26(n).reverse.map(i => (i + 97).toChar).mkString

  def basic(arrows: (NodeId, NodeId)*): ViewerGraph =
    ViewerGraph(
      data = ViewerGraphData(arrows = arrows.map((a, b) => Arrow(a, b)).map(a => a.id -> a).toMap)
    )

  val empty: ViewerGraph = ViewerGraph()

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
