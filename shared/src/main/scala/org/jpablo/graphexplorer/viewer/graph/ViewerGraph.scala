package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Multiple, Single}

/** Represents a graph that can be visualized in the viewer.
  *
  * @param id
  *   The unique identifier for this graph
  * @param data
  *   The underlying graph data containing nodes, arrows, groups and memberships
  * @param tpe
  *   The type of graph, defaults to "digraph" for directed graphs
  */
case class ViewerGraph(
    id:   String,
    data: ViewerGraphData,
    tpe:  String = "digraph"
):
  val nodeById = data.nodes
  val nodesSet = data.nodesSet
  val arrowsSet = data.arrowsSet

  def summary =
    ViewerGraph.Summary(
      nodes  = nodeById.size,
      arrows = arrowsSet.size
    )

  val allNodeIds: Set[NodeId] =
    nodesSet.map(_.id) ++ arrowsSet.flatMap(a => Set(a.source, a.target))

  val allArrowIds: Set[NodeId] = arrowsSet.map(_.id)

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
    allNodeIds -- arrowsSet.map(_.target)

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  private def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes: Set[ViewerNode] = nodeById.collect { case (id, node) if id in ids => node }.toSet
    val foundNodeIds = foundNodes.map(_.id)
    val relevantArrows = arrowsSet.filter(a => (a.source in foundNodeIds) && (a.target in foundNodeIds))
    ViewerGraph.basic2(relevantArrows, foundNodes)

  private val modifyData = this.modify(_.data)

  val modifyRootGraphAttrs = this.modify(_.data.groups.at(root.id).attributes)
  val modifyRootNodeAttrs = this.modify(_.data.groups.at(root.id).nodeAttrs)
  val modifyRootEdgeAttrs = this.modify(_.data.groups.at(root.id).edgeAttrs)

  lazy val removeUnsupportedFeatures: ViewerGraph =
    modifyRootGraphAttrs.using(_ - "size")

  val defaultNodeTheme =
    Attributes(
      Map(
        "sides" -> AttrValue("5"),
      )
    )

  val defaultEdgeTheme =
    Attributes(
      Map(
        "dir" -> AttrValue("both"),
        "arrowtail" -> AttrValue("none"),
      )
    )

  def setDefaultTheme: ViewerGraph =
    modifyRootAttributes(AttributeTarget.node).using(_ ++ defaultNodeTheme)
      .modifyRootAttributes(AttributeTarget.edge).using(_ ++ defaultEdgeTheme)

  def removeNodes(toRemove: Set[NodeId]): ViewerGraph =
    modifyData.using(_.removeElements(toRemove))

  def addEdge(source: NodeId, target: NodeId): (ViewerGraph, Arrow) =
    val (newData, arrow) = data.addArrow(source, target)
    (modifyData.setTo(newData), arrow)

  def addNode(nodeId: NodeId, groupId: Option[GroupId] = None): ViewerGraph =
    modifyData.using(_.addNode(nodeId, groupId))

  def addNodeAndEdgeFrom(source: NodeId): (ViewerGraph, NodeId) =
    val nodeId = ViewerGraph.nextNodeId(this)
    val sourceGroup = data.getMembership(source)
    val (newGraph, arrow) = addNode(nodeId, Some(sourceGroup)).addEdge(source, nodeId)
    (newGraph, nodeId)

  def addRandomNode(groupId: Option[GroupId] = None): (ViewerGraph, NodeId) =
    val nodeId = ViewerGraph.nextNodeId(this)
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
  def addToNewGroup(ids: Set[NodeId], label: String = ""): ViewerGraph =
    modifyData.using(_.addToNewGroup(ids, label))

  def addToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraph =
    modifyData.using(_.addToGroup(groupId, nodeIds))

  def ungroupSelection(ids: Set[NodeId]): ViewerGraph =
    val validIds: Set[ElementId] = ids.collect:
      case id if id in nodeById => id: ElementId
      case id if GroupId(id.value) in data.groups => id: ElementId
    modifyData.using(_.ungroup(validIds))

  def root: ViewerGroup = data.root

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

  def setRootAttributes(target: AttributeTarget)(attrs: Attributes): ViewerGraph =
    modifyRootAttributes(target).setTo(attrs)

  private def collectAttrs2(nodeIds: Set[NodeId], attrs: Map[NodeId, Attributable]): Map[String, SelectionAttrValue] =
    attrs.foldLeft(Map.empty):
      case (acc, (nodeId, attributable)) if nodeId in nodeIds =>
        val nodeIdAcc =
          attributable.attributes.values.transform: (attrId, v) =>
            // if attrId already exists then we have multiple values
            if attrId in acc then Multiple else Single(v)
        acc ++ nodeIdAcc
      case (acc, _) => acc

  private def filterAttrs(nodeIds: Set[NodeId], attrs: Map[NodeId, Attributable]): Map[NodeId, Attributes] =
    attrs.view
      .filterKeys(nodeIds)
      .toMap
      .transform((_, n) => n.attributes)

  def getAttributesById2(nodeIds: Set[NodeId]): AttributesUpdates =
    AttributesUpdates(
      nodeIds.headOption
        .map: id =>
          if NodeId.isArrowId(id) then
            collectAttrs2(nodeIds, data.arrows)
          else if NodeId.isClusterId(id) then
            collectAttrs2(nodeIds, data.groups.map((g, v) => (NodeId(g.value), v)))
          else if id in data.nodes then
            collectAttrs2(nodeIds, data.nodes)
          else Map.empty
        .getOrElse(Map.empty)
    )

  def updateAttributes(idsToUpdate: Set[NodeId], attrs: AttributesUpdates): ViewerGraph =
    modifyData.using(_.updateAttributes(idsToUpdate, attrs))

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
    val ids = allNodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase))
    subgraph(ids)

  def filterNodesBy(p: NodeId => Boolean): Set[NodeId] =
    allNodeIds.filter(p)

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

  private var nodeCounter = 0

  private def resetCounter(): Unit = nodeCounter = 0

  private def nextNodeId(graph: ViewerGraph): NodeId =
    def findNextAvailableId(): NodeId =
      nodeCounter += 1
      val id = NodeId(numberToLetterId(nodeCounter))
      if id in graph.nodeById then findNextAvailableId()
      else id
    findNextAvailableId()

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

  val defaultRootId = GroupId("G")
  val emptyTopLevel = ViewerGroup.empty(defaultRootId)

  def basic(
      arrows: Set[(NodeId, NodeId)],
      nodes:  Set[ViewerNode] = Set.empty
  ): ViewerGraph =
    fromKeyValues(
      arrowsById = arrows.map(t => Arrow(t._1, t._2)).map(a => a.id -> a).toMap,
      nodeById   = nodes.groupMapReduce(_.id)(identity)((_, b) => b)
    )

  def basic2(
      arrows: Set[Arrow],
      nodes:  Set[ViewerNode] = Set.empty,
      groups: Set[ViewerGroup] = Set.empty
  ): ViewerGraph =
    fromKeyValues(
      arrowsById = arrows.map(t => Arrow(t._1, t._2)).map(a => a.id -> a).toMap,
      nodeById   = nodes.groupMapReduce(_.id)(identity)((_, b) => b),
      groupsById = groups.groupMapReduce(_.id)(identity)((_, b) => b)
    )

  def fromKeyValues(
      arrowsById: Map[NodeId, Arrow],
      nodeById:   Map[NodeId, ViewerNode],
      groupsById: Map[GroupId, ViewerGroup] = Map.empty
  ): ViewerGraph =
    resetCounter()
    val groups = groupsById.updatedWith(defaultRootId)(_.orElse(Some(emptyTopLevel)))
    new ViewerGraph(
      id = "G",
      ViewerGraphData(
        rootId      = defaultRootId,
        arrows      = arrowsById,
        groups      = groups,
        nodes       = nodeById,
        memberships = Map.empty
      )
    )

  val empty: ViewerGraph = basic(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
