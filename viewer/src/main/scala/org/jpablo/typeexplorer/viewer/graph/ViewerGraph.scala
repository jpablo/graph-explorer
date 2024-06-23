package org.jpablo.typeexplorer.viewer.graph

import org.jpablo.typeexplorer.viewer.models.{ViewerNodeId, ViewerNode, NamespaceKind}
import org.jpablo.typeexplorer.viewer.tree.Tree
import zio.prelude.{Commutative, Identity}

import scala.annotation.targetName

type Arrow = (ViewerNodeId, ViewerNodeId)

/** A simplified representation of entities and subtype relationships
  *
  * @param arrows
  *   A pair `(a, b)` means that `a` is a subtype of `b`
  * @param nodes
  *   Classes, Objects, Traits, etc
  */
case class ViewerGraph(
    arrows: Set[Arrow],
    nodes:  Set[ViewerNode] = Set.empty
):
  lazy val nodeIds =
    nodes.map(_.nodeId)

  private lazy val directParents: ViewerNodeId => Set[ViewerNodeId] =
    arrows
      .groupBy(_._1)
      .transform((_, ss) => ss.map(_._2))
      .withDefaultValue(Set.empty)

  private lazy val directChildren: ViewerNodeId => Set[ViewerNodeId] =
    arrows
      .groupBy(_._2)
      .transform((_, ss) => ss.map(_._1))
      .withDefaultValue(Set.empty)

  private lazy val nodesById: Map[ViewerNodeId, ViewerNode] =
    nodes.groupMapReduce(_.nodeId)(identity)((_, b) => b)

  private lazy val nsByKind: Map[NamespaceKind, Set[ViewerNode]] =
    nodes.groupBy(_.kind)

  private def arrowsForNodeIds(ids: Set[ViewerNodeId]): Set[Arrow] =
    for
      arrow @ (a, b) <- arrows
      if (ids contains a) && (ids contains b)
    yield arrow

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  def subgraph(ids: Set[ViewerNodeId]): ViewerGraph =
    val foundIds = nodesById.keySet.intersect(ids)
    val foundNodes = foundIds.map(nodesById)
    ViewerGraph(arrowsForNodeIds(foundIds), foundNodes)

  def subgraphByKinds(kinds: Set[NamespaceKind]): ViewerGraph =
    val foundKinds = nsByKind.filter((kind, _) => kinds.contains(kind))
    val foundNS = foundKinds.values.flatten.toSet
    ViewerGraph(arrowsForNodeIds(foundNS.map(_.nodeId)), foundNS)

  // Note: doesn't handle loops.
  // How efficient is this compared to the tail rec version above?
  def unfold(ids: Set[ViewerNodeId], related: ViewerNodeId => Set[ViewerNodeId]): Set[ViewerNodeId] =
    Set
      .unfold(ids) { ss =>
        val ss2 = ss.flatMap(related)
        if ss2.isEmpty then None else Some((ss2, ss2))
      }
      .flatten

  private def allRelated(ids: Set[ViewerNodeId], r: ViewerNodeId => Set[ViewerNodeId]): ViewerGraph =
    subgraph(unfold(ids, r) ++ ids)

  def parentsOfAll(ids:  Set[ViewerNodeId]): ViewerGraph = allRelated(ids, directParents)
  def childrenOfAll(ids: Set[ViewerNodeId]): ViewerGraph = allRelated(ids, directChildren)

  def parentsOf(id:  ViewerNodeId): ViewerGraph = allRelated(Set(id), directParents)
  def childrenOf(id: ViewerNodeId): ViewerGraph = allRelated(Set(id), directChildren)

  lazy val toTrees: Tree[ViewerNode] =
    val paths =
      for ns <- nodes.toList yield (ns.nodeId.toString.split("/").init.toList, ns.displayName, ns)
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
  def filterBySymbolName(str: String): ViewerGraph =
    subgraph(nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase)))

  def filterBy(p: ViewerNode => Boolean): ViewerGraph =
    subgraph(nodes.filter(p).map(_.nodeId))
end ViewerGraph

object ViewerGraph:
  given Commutative[ViewerGraph] with Identity[ViewerGraph] with
    def identity = ViewerGraph.empty
    def combine(l: => ViewerGraph, r: => ViewerGraph) = l ++ r

  // In Scala 3.2 the type annotation is needed.
  val empty: ViewerGraph = new ViewerGraph(Set.empty)
end ViewerGraph
