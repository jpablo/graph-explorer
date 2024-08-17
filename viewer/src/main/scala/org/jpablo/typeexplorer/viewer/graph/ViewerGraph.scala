package org.jpablo.typeexplorer.viewer.graph

import org.jpablo.typeexplorer.viewer.formats.CSV
import org.jpablo.typeexplorer.viewer.models.{Arrow, NodeId, ViewerKind, ViewerNode}
import org.jpablo.typeexplorer.viewer.tree.Tree
import zio.prelude.{Commutative, Identity}

import scala.annotation.targetName

trait Decoder[A]:
  def decode(s: String): Either[String, A]

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

  private lazy val directParents: NodeId => Set[NodeId] =
    arrows
      .groupBy(_._1)
      .transform((_, ss) => ss.map(_._2))
      .withDefaultValue(Set.empty)

  private lazy val directChildren: NodeId => Set[NodeId] =
    arrows
      .groupBy(_._2)
      .transform((_, ss) => ss.map(_._1))
      .withDefaultValue(Set.empty)

  private lazy val nodeById: Map[NodeId, ViewerNode] =
    nodes.groupMapReduce(_.id)(identity)((_, b) => b)

  private lazy val nodesByKind: Map[ViewerKind, Set[ViewerNode]] =
    nodes.groupBy(_.kind)

  lazy val kinds =
    nodes.map(_.kind)

  private def arrowsForNodeIds(ids: Set[NodeId]): Set[Arrow] =
    for
      a <- arrows
      if (ids contains a.source) && (ids contains a.target)
    yield a

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundIds = nodeById.keySet.intersect(ids)
    val foundNodes = foundIds.map(nodeById)
    ViewerGraph(arrowsForNodeIds(foundIds), foundNodes)

  def subgraphByKinds(kinds: Set[ViewerKind]): ViewerGraph =
    val foundKinds = nodesByKind.filter((kind, _) => kinds.contains(kind))
    val foundNS = foundKinds.values.flatten.toSet
    ViewerGraph(arrowsForNodeIds(foundNS.map(_.id)), foundNS)

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

  private def allRelated(f: NodeId => Set[NodeId])(ids: Set[NodeId]): ViewerGraph =
    subgraph(unfold(f, ids))

  private def directRelated(f: NodeId => Set[NodeId])(ids: Set[NodeId]): ViewerGraph =
    subgraph(ids.flatMap(f))

  val parentsOfAll = allRelated(directParents)
  val childrenOfAll = allRelated(directChildren)

  def parentsOf(id:  NodeId): ViewerGraph = allRelated(directParents)(Set(id))
  def childrenOf(id: NodeId): ViewerGraph = allRelated(directChildren)(Set(id))

  val directParentsOfAll = directRelated(directParents)
  val directChildrenOfAll = directRelated(directChildren)

  def directParentsOf(id:  NodeId): ViewerGraph = directParentsOfAll(Set(id))
  def directChildrenOf(id: NodeId): ViewerGraph = directChildrenOfAll(Set(id))

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

  given Commutative[ViewerGraph] with Identity[ViewerGraph] with
    def identity = ViewerGraph.empty
    def combine(l: => ViewerGraph, r: => ViewerGraph) = l ++ r

  // In Scala 3.2 the type annotation is needed.
  val empty = ViewerGraph(Set.empty, Set.empty)
end ViewerGraph
