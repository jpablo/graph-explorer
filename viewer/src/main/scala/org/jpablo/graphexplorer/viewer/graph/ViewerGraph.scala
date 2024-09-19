package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.formats.CSV
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerNode}
import org.jpablo.graphexplorer.viewer.tree.Tree

import scala.annotation.targetName

/** A simplified representation of entities and subtype relationships
  *
  * @param arrows
  *   A pair `(a, b)` means that `a` is a subtype of `b`
  * @param nodes
  *   Classes, Objects, Traits, etc
  */
case class ViewerGraph(
    arrows: Set[Arrow],
    nodes:  Set[ViewerNode]
):

  lazy val stats =
    s"Nodes: ${nodes.size}, Arrows: ${arrows.size}"

  lazy val nodeIds =
    nodes.map(_.id)

  private lazy val findDirectSuccessors: NodeId => Set[NodeId] =
    arrows
      .groupBy(_.source)
      .transform((_, ss) => ss.map(_.target))
      .withDefaultValue(Set.empty)

  private lazy val findDirectPredecessors: NodeId => Set[NodeId] =
    arrows
      .groupBy(_.target)
      .transform((_, ss) => ss.map(_.source))
      .withDefaultValue(Set.empty)

  private lazy val nodeById: Map[NodeId, ViewerNode] =
    nodes.groupMapReduce(_.id)(identity)((_, b) => b)

  private def arrowsForNodeIds(ids: Set[NodeId]): Set[Arrow] =
    for
      a <- arrows
      if (ids contains a.source) && (ids contains a.target)
    yield a

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes = nodeById.collect { case (id, node) if ids.contains(id) => node }
    ViewerGraph(arrowsForNodeIds(ids), foundNodes.toSet)

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
    subgraph(unfold(f, ids))

  private def subgraphWith(f: NodeId => Set[NodeId])(ids: Set[NodeId]): ViewerGraph =
    subgraph(ids.flatMap(f))

  val directSuccessors: Set[NodeId] => ViewerGraph = subgraphWith(findDirectSuccessors)
  val unfoldSuccessors: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(findDirectSuccessors)

  val directPredecessors: Set[NodeId] => ViewerGraph = subgraphWith(findDirectPredecessors)
  val unfoldPredecessors: Set[NodeId] => ViewerGraph = subgraphUnfoldWith(findDirectPredecessors)

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
    subgraph(nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase)))

  def filterBy(p: ViewerNode => Boolean): ViewerGraph =
    subgraph(nodes.filter(p).map(_.id))

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
      arrows = arrows.map(Arrow(_, _)),
      nodes = arrows
        .flatMap(a => Set(a._1, a._2))
        .map(id => ViewerNode(id, id.toString)) ++ nodes
    )

  // In Scala 3.2 the type annotation is needed.
  val empty = ViewerGraph(Set.empty, Set.empty)
end ViewerGraph
