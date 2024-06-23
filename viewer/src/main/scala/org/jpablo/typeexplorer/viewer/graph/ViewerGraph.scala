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
  lazy val symbols =
    nodes.map(_.nodeId)

  lazy val directParents: ViewerNodeId => Set[ViewerNodeId] =
    arrows
      .groupBy(_._1)
      .transform((_, ss) => ss.map(_._2))
      .withDefaultValue(Set.empty)

  private lazy val directChildren: ViewerNodeId => Set[ViewerNodeId] =
    arrows
      .groupBy(_._2)
      .transform((_, ss) => ss.map(_._1))
      .withDefaultValue(Set.empty)

  lazy val nsBySymbol: Map[ViewerNodeId, ViewerNode] =
    nodes.groupMapReduce(_.nodeId)(identity)((_, b) => b)

  private lazy val nsByKind: Map[NamespaceKind, Set[ViewerNode]] =
    nodes.groupBy(_.kind)

  private def arrowsForSymbols(symbols: Set[ViewerNodeId]) =
    for
      arrow @ (a, b) <- arrows
      if (symbols contains a) && (symbols contains b)
    yield arrow

  /** Creates a diagram containing the given symbols and the arrows between them.
    */
  def subdiagram(symbols: Set[ViewerNodeId]): ViewerGraph =
    val foundSymbols = nsBySymbol.keySet.intersect(symbols)
    val foundNS = foundSymbols.map(nsBySymbol)
    ViewerGraph(arrowsForSymbols(foundSymbols), foundNS)

  def subdiagramByKinds(kinds: Set[NamespaceKind]): ViewerGraph =
    val foundKinds = nsByKind.filter((kind, _) => kinds.contains(kind))
    val foundNS = foundKinds.values.flatten.toSet
    ViewerGraph(arrowsForSymbols(foundNS.map(_.nodeId)), foundNS)

  // Note: doesn't handle loops.
  // How efficient is this compared to the tail rec version above?
  def unfold(symbols: Set[ViewerNodeId], related: ViewerNodeId => Set[ViewerNodeId]): Set[ViewerNodeId] =
    Set
      .unfold(symbols) { ss =>
        val ss2 = ss.flatMap(related)
        if ss2.isEmpty then None else Some((ss2, ss2))
      }
      .flatten

  private def allRelated(ss: Set[ViewerNodeId], r: ViewerNodeId => Set[ViewerNodeId]): ViewerGraph =
    subdiagram(unfold(ss, r) ++ ss)

  def parentsOfAll(symbols:  Set[ViewerNodeId]): ViewerGraph = allRelated(symbols, directParents)
  def childrenOfAll(symbols: Set[ViewerNodeId]): ViewerGraph = allRelated(symbols, directChildren)

  def parentsOf(symbol:  ViewerNodeId): ViewerGraph = allRelated(Set(symbol), directParents)
  def childrenOf(symbol: ViewerNodeId): ViewerGraph = allRelated(Set(symbol), directChildren)

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
    subdiagram(symbols.filter(_.toString.toLowerCase.contains(str.toLowerCase)))

  def filterBy(p: ViewerNode => Boolean): ViewerGraph =
    subdiagram(nodes.filter(p).map(_.nodeId))
end ViewerGraph

object ViewerGraph:
  given Commutative[ViewerGraph] with Identity[ViewerGraph] with
    def identity = ViewerGraph.empty
    def combine(l: => ViewerGraph, r: => ViewerGraph) = l ++ r

  // In Scala 3.2 the type annotation is needed.
  val empty: ViewerGraph = new ViewerGraph(Set.empty)
end ViewerGraph
