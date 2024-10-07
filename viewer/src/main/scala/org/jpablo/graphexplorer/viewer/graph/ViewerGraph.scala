package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.CSV
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerNode}
import org.jpablo.graphexplorer.viewer.tree.Tree
import org.scalajs.dom

import scala.annotation.targetName

/** A simplified representation of entities and subtype relationships
  *
  * @param arrows
  *   Only NodeIds are used for ends of arrows. For the full definition of a node use the nodes field.
  * @param nodes
  *   Either isolated nodes or full node definitions for arrow ends
  */
case class ViewerGraph(
    arrows: Set[Arrow],
    nodes:  Set[ViewerNode]
):

  lazy val summary =
    ViewerGraph.Summary(
      nodes  = nodes.size,
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

  private lazy val nodeById: Map[NodeId, ViewerNode] =
    nodes.groupMapReduce(_.id)(identity)((_, b) => b)

  private def arrowsForNodeIds(ids: Set[NodeId]): Set[Arrow] =
    arrows
      .filter(a => (ids contains a.source) && (ids contains a.target))

  private def arrowsWithoutNodeIds(ids: Set[NodeId]): Set[Arrow] =
    arrows
      .filterNot(a => (ids contains a.source) || (ids contains a.target))

  /** allNodeIds that are not in the target of any arrow
    */
  lazy val roots: Set[NodeId] =
    allNodeIds -- arrows.map(_.target)

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  private def subgraph(ids: Set[NodeId]): ViewerGraph =
    dom.console.log(s"--> subgraph($ids) --")
    val foundNodes: Set[ViewerNode] = nodeById.collect { case (id, node) if ids.contains(id) => node }.toSet
    val foundNodeIds = foundNodes.map(_.id)
    dom.console.log(s"foundNodeIds: $foundNodeIds")
    arrows.toSeq.sortBy(_.source.value).foreach(a => dom.console.log(s"\t$a"))
    val relevantArrows = arrows.filter(a => (foundNodeIds contains a.source) && (foundNodeIds contains a.target))
    dom.console.log(s"relevantArrows: $relevantArrows")
    dom.console.log("<----")
    ViewerGraph(relevantArrows, foundNodes)

  def remove(toRemove: Set[NodeId]): ViewerGraph =
    val foundNodes = nodeById.collect { case (id, node) if !toRemove.contains(id) => node }
    ViewerGraph(arrowsWithoutNodeIds(toRemove), foundNodes.toSet)

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
    dom.console.log(s"--> subgraphWith($ids) --")
    dom.console.log(ids.flatMap(f).toString)
    dom.console.log("<----")
    subgraph(ids ++ ids.flatMap(f))

  val directSuccessorsGraph: Set[NodeId] => ViewerGraph = subgraphWith(directSuccessors)
  val directPredecessorsGraph: Set[NodeId] => ViewerGraph = subgraphWith(directPredecessors)

  val allSuccessorsGraph: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(directSuccessors)
  val allPredecessorsGraph: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(directPredecessors)

  lazy val toTrees: Tree[ViewerNode] =
    val paths =
      for ns <- nodes.toList yield (ns.id.toString.split("/").init.toList, ns.displayName, ns)
    Tree.fromPaths(paths, ".")

  /** Combines the diagram on the left with the diagram on the right. No new arrows are introduced beyond those present
    * in both diagrams.
    */
  @targetName("combine")
  def ++(other: ViewerGraph): ViewerGraph =
    ViewerGraph(
      arrows = arrows ++ other.arrows,
      nodes  = nodes ++ other.nodes
    )

  /** Creates a new subdiagram with all the symbols containing the given String.
    */
  def filterByNodeId(str: String): ViewerGraph =
    val ids = allNodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase))
    subgraph(ids)

  def filterNodesBy(p: NodeId => Boolean): Set[NodeId] =
    allNodeIds.filter(p)

  def filterArrowsBy(p: Arrow => Boolean) =
    arrows.filter(p)

  def toCSV: CSV =
    CSV(
      arrows
        .map(a => Array(a.source.toString, a.target.toString))
        .toArray
    )
end ViewerGraph

object ViewerGraph:

  @targetName("extra")
  def apply(
      arrows: Set[(NodeId, NodeId)],
      nodes:  Set[ViewerNode] = Set.empty
  ): ViewerGraph =
    new ViewerGraph(
      arrows = arrows.map(t => Arrow(t._1, t._2, None)),
      nodes  = nodes
    )

  // In Scala 3.2 the type annotation is needed.
  val empty = ViewerGraph(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
