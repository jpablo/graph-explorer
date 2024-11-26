package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
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
  lazy val arrowsById: Map[NodeId, Arrow] =
    data.arrows.map(a => a.nodeId -> a).toMap

  lazy val nodeById: Map[NodeId, ViewerNode] =
    data.nodes.groupMapReduce(_.id)(identity)((_, b) => b)

  lazy val groupsById: Map[NodeId, ViewerGroup] =
    data.groups.groupMapReduce(_.id)(identity)((_, b) => b)

//  pprint.log(nodeById)

  val nodes = nodeById.values.toSet
  val arrows = arrowsById.values.toSet

//  val graphGroup = ViewerGroup(NodeId("G"), nodes.map(_.id))

  lazy val summary =
    ViewerGraph.Summary(
      nodes  = nodeById.size,
      arrows = arrows.size
    )

  lazy val allNodeIds: Set[NodeId] =
    nodes.map(_.id) ++ arrows.flatMap(a => Set(a.source, a.target))

  lazy val allArrowIds: Set[NodeId] = arrows.map(_.nodeId)

  private lazy val directSuccessors: Map[NodeId, Set[NodeId]] =
    arrows
      .groupBy(_.source)
      .transform((_, ss) => ss.map(_.target))
      .withDefaultValue(Set.empty)

  private lazy val directPredecessors: Map[NodeId, Set[NodeId]] =
    arrows
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

  def attributesById(nodeIds: Set[String]): Attributes =
    val init = Map.empty[String, String]
    val nodeAttrs = nodes.filter(_.id.value in nodeIds).map(_.publicAttrs.values).foldLeft(init)(_ ++ _)
    val edgeAttrs = arrows.filter(_.nodeId.value in nodeIds).map(_.publicAttrs.values).foldLeft(init)(_ ++ _)
    Attributes(nodeAttrs ++ edgeAttrs)

  private def arrowsWithoutNodeIds(ids: Set[NodeId]): Set[Arrow] =
    arrows
      .filterNot(a => (a.source in ids) || (a.target in ids))

  /** allNodeIds that are not in the target of any arrow
    */
  lazy val roots: Set[NodeId] =
    allNodeIds -- arrows.map(_.target)

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  private def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes: Set[ViewerNode] = nodeById.collect { case (id, node) if id in ids => node }.toSet
    val foundNodeIds = foundNodes.map(_.id)
    val relevantArrows = arrows.filter(a => (a.source in foundNodeIds) && (a.target in foundNodeIds))
    ViewerGraph.basic2(relevantArrows, foundNodes)

  def removeNodes(toRemove: Set[NodeId]): ViewerGraph =

    val foundNodes = nodeById.collect { case (id, node) if (id notIn toRemove) => node }
    ViewerGraph.basic2(arrowsWithoutNodeIds(toRemove), foundNodes.toSet)

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
    arrows.filter(p)

//  def toCSV: CSV =
//    CSV(
//      arrows
//        .map(a => Array(a.source.toString, a.target.toString))
//        .toArray
//    )
end ViewerGraph

object ViewerGraph:

  def basic(
      arrows: Set[(NodeId, NodeId)],
      nodes:  Set[ViewerNode] = Set.empty
  ): ViewerGraph =
    fromKeyValues(
      arrowsById = arrows.map(t => Arrow(t._1, t._2)).map(a => a.nodeId -> a).toMap,
      nodeById   = nodes.groupMapReduce(_.id)(identity)((_, b) => b)
    )

  def basic2(
      arrows: Set[Arrow],
      nodes:  Set[ViewerNode] = Set.empty,
      groups: Set[ViewerGroup] = Set.empty
  ): ViewerGraph =
    fromKeyValues(
      arrowsById = arrows.map(t => Arrow(t._1, t._2)).map(a => a.nodeId -> a).toMap,
      nodeById   = nodes.groupMapReduce(_.id)(identity)((_, b) => b),
      groupsById = groups.groupMapReduce(_.id)(identity)((_, b) => b)
    )

  def fromKeyValues(
      arrowsById: Map[NodeId, Arrow],
      nodeById:   Map[NodeId, ViewerNode],
      groupsById: Map[NodeId, ViewerGroup] = Map.empty
  ): ViewerGraph =
    new ViewerGraph(
      ViewerGraphData(
        arrows = arrowsById.map { case (k, v) => v }.toList,
        groups = groupsById.map { case (k, v) => v }.toList,
        nodes  = nodeById.map { case (k, v) => v }.toList
      )
    )

  // In Scala 3.2 the type annotation is needed.
  val empty: ViewerGraph = basic(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
