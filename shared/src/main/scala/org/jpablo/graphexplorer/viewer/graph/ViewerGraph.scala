package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}

import org.jpablo.graphexplorer.viewer.models.*

/** A simplified representation of entities and subtype relationships
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

  val modifyRootGraphAttrs = this.modify(_.data.groups.at(root.id).attrs.values)
  val modifyRootNodeAttrs = this.modify(_.data.groups.at(root.id).nodeAttrs.values)
  val modifyRootEdgeAttrs = this.modify(_.data.groups.at(root.id).edgeAttrs.values)

  lazy val removeUnsupportedFeatures: ViewerGraph =
    modifyRootGraphAttrs.using(_ - "size")

  def setDefaultTheme: ViewerGraph =
    //    val defaultAttrs = Attributes(Map("style" -> "filled", "fillcolor" -> "white"))
    //    val nodesWithDefaultAttrs = nodes.map(n => n.copy(attrs = n.attrs ++ defaultAttrs))
    //    val arrowsWithDefaultAttrs = arrows.map(a => a.copy(attrs = a.attrs ++ defaultAttrs))
    //    ViewerGraph(arrowsWithDefaultAttrs, nodesWithDefaultAttrs)
    this

  def removeNodes(toRemove: Set[NodeId]): ViewerGraph =
    modifyData.using(_.removeNodes(toRemove))

  def addEdge(source: NodeId, target: NodeId): ViewerGraph =
    modifyData.using(_.addArrow(source, target))

  def addNodeAndEdgeFrom(source: NodeId): (ViewerGraph, NodeId) =
    val nodeId = NodeId.random()
    (addNode(nodeId).addEdge(source, nodeId), nodeId)

  def addNode(nodeId: NodeId): ViewerGraph =
    modifyData.using(_.addNode(nodeId))

  def addRandomNode(): (ViewerGraph, NodeId) =
    val nodeId = NodeId.random()
    (addNode(nodeId), nodeId)

  def addToNewGroup(ids: Set[NodeId], label: String = ""): ViewerGraph =
    modifyData.using(_.addToNewGroup(ids, label))

  def root: ViewerGroup = data.root

  def getRootAttributes(target: AttributeTarget): Map[String, AttrValue] =
    target match
      case AttributeTarget.graph => root.attrs.values
      case AttributeTarget.node  => root.nodeAttrs.values
      case AttributeTarget.edge  => root.edgeAttrs.values

  def updateRootAttributes(target: AttributeTarget)(attrs: Map[String, AttrValue]): ViewerGraph =
    val modifyAttrs = target match
      case AttributeTarget.graph => modifyRootGraphAttrs
      case AttributeTarget.node  => modifyRootNodeAttrs
      case AttributeTarget.edge  => modifyRootEdgeAttrs
    modifyAttrs.using(_ ++ attrs)

  val init = Map.empty[String, AttrValue]

  def getAttributesById(nodeIds: Set[NodeId]): Attributes =
    def collectAttrs(attrs: Map[NodeId, Attributable]) =
      attrs.collect { case (id, n) if id in nodeIds => n.attrs.values }.foldLeft(init)(_ ++ _)

    Attributes(collectAttrs(data.nodes) ++ collectAttrs(data.arrows))

  def updateAttributes(idsToUpdate: Set[NodeId], attrs: Attributes): ViewerGraph =
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
