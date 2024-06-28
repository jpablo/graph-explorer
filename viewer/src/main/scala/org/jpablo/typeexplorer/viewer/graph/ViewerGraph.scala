package org.jpablo.typeexplorer.viewer.graph

import org.jpablo.typeexplorer.viewer.models.{Arrow, ViewerKind, ViewerNode, ViewerNodeId}
import org.jpablo.typeexplorer.viewer.tree.Tree
import org.jpablo.typeexplorer.viewer.utils.CSV
import zio.prelude.{Commutative, Identity}

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
  lazy val nodeIds =
    nodes.map(_.id)

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

  private lazy val nodeById: Map[ViewerNodeId, ViewerNode] =
    nodes.groupMapReduce(_.id)(identity)((_, b) => b)

  private lazy val nodesByKind: Map[ViewerKind, Set[ViewerNode]] =
    nodes.groupBy(_.kind)

  lazy val kinds =
    nodes.map(_.kind)

  private def arrowsForNodeIds(ids: Set[ViewerNodeId]): Set[Arrow] =
    for
      a <- arrows
      if (ids contains a.source) && (ids contains a.target)
    yield a

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  def subgraph(ids: Set[ViewerNodeId]): ViewerGraph =
    val foundIds = nodeById.keySet.intersect(ids)
    val foundNodes = foundIds.map(nodeById)
    ViewerGraph(arrowsForNodeIds(foundIds), foundNodes)

  def subgraphByKinds(kinds: Set[ViewerKind]): ViewerGraph =
    val foundKinds = nodesByKind.filter((kind, _) => kinds.contains(kind))
    val foundNS = foundKinds.values.flatten.toSet
    ViewerGraph(arrowsForNodeIds(foundNS.map(_.id)), foundNS)

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
  def filterBySymbolName(str: String): ViewerGraph =
    subgraph(nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase)))

  def filterBy(p: ViewerNode => Boolean): ViewerGraph =
    subgraph(nodes.filter(p).map(_.id))
end ViewerGraph

object ViewerGraph:

  @targetName("extra")
  def apply(
      arrows: Set[(ViewerNodeId, ViewerNodeId)],
      nodes:  Set[ViewerNode] = Set.empty
  ): ViewerGraph =
    new ViewerGraph(
      arrows = arrows.map((a, b) => Arrow(a, b)),
      nodes  = nodes
    )

  def from(csv: CSV): ViewerGraph =
    val arrows =
      csv.rows.map: row =>
        ViewerNodeId(row(0)) -> ViewerNodeId(row(1))
    ViewerGraph(arrows.toSet)

  given Commutative[ViewerGraph] with Identity[ViewerGraph] with
    def identity = ViewerGraph.empty
    def combine(l: => ViewerGraph, r: => ViewerGraph) = l ++ r

  // In Scala 3.2 the type annotation is needed.
  val empty: ViewerGraph = ViewerGraph(Set.empty, Set.empty)
end ViewerGraph
