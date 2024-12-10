package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.SubGraph.randomId
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

// import scala.collection.mutable
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

case class ViewerGraph(
    id:      String,
    data:    ViewerGraphData,
    tpe:     String = "digraph",
//    version: Version = 0,
//    origin:  ChangeOrigin = ChangeOrigin.CodeMirror
):
  // Efficient access to elements
//  def arrowsById = data.arrows
  val nodeById = data.nodes
//  def groupsById = data.groups
  val nodesSet = data.nodesSet
  val arrowsSet = data.arrowsSet

//  def nextVersion(): ViewerGraph =
//    println(s"ViewerGraph # nextVersion(): $version -> ${version + 1}")
//    copy(version = version + 1/*, origin = ChangeOrigin.Graph*/)

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

  lazy val removeUnsupportedFeatures: ViewerGraph =
    this
      .modify(_.data.groups)
      .using(_ + (root.id -> root.modify(_.attrs.values).using(_ - "size")))

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
//      .modify(_.data).using(_.modifyArrows(_.addOne(arrow.id -> arrow)))
      .modify(_.data).using(_.addArrow(arrow))
      .modify(_.data).using(_.addMembership(arrow.id, Some(data.rootNodeId)))
//      .modify(_.data.memberships).using(_.addOne(arrow.id -> Some(data.rootNodeId)))

  def addNodeAndEdgeFrom(source: NodeId): ViewerGraph =
    val newNode = node(randomId())
    val newArrow = Arrow(source, newNode.id)
    this
      .modifyAll(_.data.nodes).using(_ + (newNode.id -> newNode))
//      .modify(_.data).using(_.modifyArrows(_.addOne(newArrow.id -> newArrow)))
      .modify(_.data).using(_.addArrow(newArrow))

  def addRandomNode(): ViewerGraph =
    val newNode = node(randomId())
    this.modify(_.data.nodes).using(_ + (newNode.id -> newNode))

  def root: ViewerGroup = data.root

  def getRootAttributes(target: AttributeTarget): Map[String, AttrValue] =
    target match
      case AttributeTarget.graph => root.attrs.values
      case AttributeTarget.node  => root.nodeAttrs.values
      case AttributeTarget.edge  => root.edgeAttrs.values

  def updateRootAttributes(target: AttributeTarget)(attrs: Map[String, AttrValue]): ViewerGraph =
//    println("ViewerGraph # updateRootAttributes")
    val modifyRoot =
      target match
        case AttributeTarget.graph => root.modify(_.attrs.values)
        case AttributeTarget.node  => root.modify(_.nodeAttrs.values)
        case AttributeTarget.edge  => root.modify(_.edgeAttrs.values)
    this
      .modify(_.data.groups)
      .using(_ + (root.id -> modifyRoot.using(_ ++ attrs)))
//      .nextVersion()

  val init = Map.empty[String, AttrValue]

  def getAttributesById(nodeIds: Set[NodeId]): Attributes =
    def collectAttrs(attrs: Map[NodeId, Attributable]) =
      attrs.collect { case (id, n) if id in nodeIds => n.publicAttrs.values }.foldLeft(init)(_ ++ _)

    Attributes(collectAttrs(data.nodes) ++ collectAttrs(data.arrowsMap))

  def updateAttributes(idsToUpdate: Set[NodeId], attrs: Attributes): ViewerGraph =
    val (arrowIdsToUpdate, nodeIdsToUpdate) = idsToUpdate.partition(Arrow.isArrowId)

    val arrowsToUpdate: Arrows = data.filterArrows((id, _) => id in arrowIdsToUpdate)
    val updatedArrows = arrowsToUpdate.transform((_, a) => a.mergeAttrs(attrs))
    //  val updatedArrows = arrowsToUpdate.mapValuesInPlace((_, a) => a.mergeAttrs(attrs))

    // val updatedArrows =
    //   arrowIdsToUpdate.foldLeft(data.arrows): (arrowsMap, arrowId) =>
    //     arrowsMap
    //       .updatedWith(arrowId) {
    //         _.fold(
    //           Some(Arrow(arrowId.source, arrowId.target, attrs.values))
    //         )(a => Some(a.mergeAttrs(attrs)))
    //       }

    val endpointsToUpdate = arrowsToUpdate.values.flatMap(_.endpoints).toSet & idsToUpdate
    // only update these if they are in ids
    val allNodeIdsToUpdate = nodeIdsToUpdate ++ endpointsToUpdate

    val updatedNodes =
      allNodeIdsToUpdate.foldLeft(data.nodes): (nodesMap, nodeId) =>
        nodesMap
          .updatedWith(nodeId) {
            _.fold(
              Some(node(nodeId.value, attrs.values))
            )(n => Some(n.mergeAttrs(attrs)))
          }

    val updatedMembership =
      updatedNodes.keys.map(id => id -> data.memberships.getOrElse(id, Some(root.id))).toMap

    copy(
      data = data.copy(
        arrows      = data.concatArrows(updatedArrows),
        nodes       = updatedNodes,
        memberships = data.memberships ++ updatedMembership
      )
    )//.nextVersion()

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
    new ViewerGraph(
      id = "G",
      ViewerGraphData(
        // arrows = mutable.LinkedHashMap.from(arrowsById),
        arrows = arrowsById,
        groups = groups,
        nodes  = nodeById,
//      memberships = mutable.LinkedHashMap(defaultRootId -> None)
        memberships = Map(defaultRootId -> None)
      )
    )

  val empty: ViewerGraph = basic(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
