package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.formats.dot.ast.SubGraph.randomId
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
//import org.jpablo.graphexplorer.viewer.formats.CSV
import org.jpablo.graphexplorer.viewer.models.*
//import org.jpablo.graphexplorer.viewer.tree.Tree

/** A simplified representation of entities and subtype relationships
  *
  * @param arrows
  *   Only NodeIds are used for ends of arrows. For the full definition of a node use the nodes field.
  * @param nodeById
  *   Either isolated nodes or full node definitions for arrow ends
  */

case class ViewerGraph(data: ViewerGraphData, id: Option[String] = None, tpe: String = "digraph"):
  // Efficient access to elements
  def arrowsById = data.arrows
  def nodeById = data.nodes
  def groupsById = data.groups
  def nodesSet = data.nodesSet
  def arrowsSet = data.arrowsSet

  def summary =
    ViewerGraph.Summary(
      nodes  = nodeById.size,
      arrows = arrowsSet.size
    )

  def allNodeIds: Set[NodeId] =
    nodesSet.map(_.id) ++ arrowsSet.flatMap(a => Set(a.source, a.target))

  def allArrowIds: Set[NodeId] = arrowsSet.map(_.id)

  def directSuccessors: Map[NodeId, Set[NodeId]] =
    arrowsSet
      .groupBy(_.source)
      .transform((_, ss) => ss.map(_.target))
      .withDefaultValue(Set.empty)

  def directPredecessors: Map[NodeId, Set[NodeId]] =
    arrowsSet
      .groupBy(_.target)
      .transform((_, ss) => ss.map(_.source))
      .withDefaultValue(Set.empty)

  def removeUnsupportedFeatures: ViewerGraph =
    //    val supportedAttrs = Set("label", "id")
    //    val supportedNodes = nodes.map(n => n.copy(attrs = n.attrs.filterKeys(supportedAttrs.contains)))
    //    val supportedArrows = arrows.map(a => a.copy(attrs = a.attrs.filterKeys(supportedAttrs.contains)))
    //    ViewerGraph(supportedArrows, supportedNodes)
    this

  def setDefaultTheme: ViewerGraph =
    //    val defaultAttrs = Attributes(Map("style" -> "filled", "fillcolor" -> "white"))
    //    val nodesWithDefaultAttrs = nodes.map(n => n.copy(attrs = n.attrs ++ defaultAttrs))
    //    val arrowsWithDefaultAttrs = arrows.map(a => a.copy(attrs = a.attrs ++ defaultAttrs))
    //    ViewerGraph(arrowsWithDefaultAttrs, nodesWithDefaultAttrs)
    this

  private def arrowsWithoutNodeIds(ids: Set[NodeId]): Set[Arrow] =
    arrowsSet
      .filterNot(a => (a.source in ids) || (a.target in ids))

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

  def removeNodes(toRemove: Set[NodeId]): ViewerGraph =
    this.copy(data = data.removeNodes(toRemove))

  def addEdge(source: NodeId, target: NodeId): ViewerGraph =
    val newSeq = data.maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    this
      .modify(_.data.arrows).using(_ + (arrow.id -> arrow))
      .modify(_.data.memberships).using(_ + (arrow.id -> Some(data.rootNodeId)))

  def addNodeAndEdgeFrom(source: NodeId): ViewerGraph =
    val newNode = node(randomId())
    val newArrow = Arrow(source, newNode.id)
    this
      .modifyAll(_.data.nodes).using(_ + (newNode.id -> newNode))
      .modify(_.data.arrows).using(_ + (newArrow.id -> newArrow))

  def addRandomNode(): ViewerGraph =
    val newNode = node(randomId())
    this.modify(_.data.nodes).using(_ + (newNode.id -> newNode))

  def root: ViewerGroup = data.root

  def getRootAttributes(target: AttributeTarget): Map[String, String] =
    target match
      case AttributeTarget.graph => root.attrs.values
      case AttributeTarget.node  => root.nodeAttrs.values
      case AttributeTarget.edge  => root.edgeAttrs.values

  def updateRootAttributes(target: AttributeTarget)(attrs: Map[String, String]): ViewerGraph =
    val modifyRoot =
      target match
        case AttributeTarget.graph => root.modify(_.attrs.values)
        case AttributeTarget.node  => root.modify(_.nodeAttrs.values)
        case AttributeTarget.edge  => root.modify(_.edgeAttrs.values)
    this.modify(_.data.groups).using(_ + (root.id -> modifyRoot.using(_ ++ attrs)))

  val init = Map.empty[String, String]

  def getAttributesById(nodeIds: Set[NodeId]): Attributes =
    def collectAttrs(attrs: Map[NodeId, Attributable]) =
      attrs.collect { case (id, n) if id in nodeIds => n.publicAttrs.values }.foldLeft(init)(_ ++ _)

    Attributes(collectAttrs(data.nodes) ++ collectAttrs(data.arrows))

  def updateAttributes(idsToUpdate: Set[NodeId], attrs: Attributes): ViewerGraph =
    val (arrowIdsToUpdate, nodeIdsToUpdate) = idsToUpdate.partition(Arrow.isArrowId)

    val arrowsToUpdate = data.arrows.filter((id, _) => id in arrowIdsToUpdate)
    val updatedArrows = arrowsToUpdate.transform((_, a) => a.mergeAttrs(attrs))

    val endpointsToUpdate = arrowsToUpdate.values.flatMap(_.endpoints).toSet & idsToUpdate
    // only update these if they are in ids
    val nodesToUpdate = nodeIdsToUpdate ++ endpointsToUpdate

    val updatedNodes =
      nodesToUpdate.foldLeft(data.nodes): (nodesMap, nodeId) =>
        nodesMap
          .updatedWith(nodeId)(_.fold(Some(node(nodeId.value, attrs.values)))(n => Some(n.mergeAttrs(attrs))))

    val updatedMembership =
      updatedNodes.keys.map(id => id -> data.memberships.getOrElse(id, Some(root.id))).toMap

    copy(
      data = data.copy(
        arrows      = data.arrows ++ updatedArrows,
        nodes       = updatedNodes,
        memberships = data.memberships ++ updatedMembership
      )
    )

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

//  /** Combines the diagram on the left with the diagram on the right. No new arrows are introduced beyond those present
//    * in both diagrams.
//    */
//  @targetName("combine")
//  def ++(other: ViewerGraph): ViewerGraph =
//    ViewerGraph(
//      arrows   = arrows ++ other.arrows,
//      nodeById = nodeById ++ other.nodeById
//    )

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

  val defaultRootId = NodeId("G")
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
      groupsById: Map[NodeId, ViewerGroup] = Map.empty
  ): ViewerGraph =
    val groups = groupsById.updatedWith(defaultRootId)(_.orElse(Some(emptyTopLevel)))
    new ViewerGraph(ViewerGraphData(arrowsById, groups, nodeById, Map(defaultRootId -> None)))

  val empty: ViewerGraph = basic(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
