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
  val arrowsById = data.arrows

  val nodeById = data.nodes

  val groupsById = data.groups

  val nodes = nodeById.values.toSet
  val arrows = arrowsById.values.toSet

  lazy val summary =
    ViewerGraph.Summary(
      nodes  = nodeById.size,
      arrows = arrows.size
    )

  lazy val allNodeIds: Set[NodeId] =
    nodes.map(_.id) ++ arrows.flatMap(a => Set(a.source, a.target))

  lazy val allArrowIds: Set[NodeId] = arrows.map(_.id)

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
    this.copy(data = data.removeNodes(toRemove))

  def addEdge(source: NodeId, target: NodeId): ViewerGraph =
    val seqs = data.arrowSequences(source, target)
    val newSeq = seqs.max
    val arrow = Arrow(source, target, seq = newSeq + 1)
    this.modify(_.data.arrows).using(_ + (arrow.id -> arrow))

  def addNodeAndEdgeFrom(source: NodeId): ViewerGraph =
    val newNode = node(randomId())
    val newArrow = Arrow(source, newNode.id)
    this
      .modifyAll(_.data.nodes).using(_ + (newNode.id -> newNode))
      .modify(_.data.arrows).using(_ + (newArrow.id -> newArrow))

  def addRandomNode(): ViewerGraph =
    val newNode = node(randomId())
    this.modify(_.data.nodes).using(_ + (newNode.id -> newNode))

  val root: ViewerGroup = data.root

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

  def updateAttributes(nodeIds: Set[NodeId], attrs: Attributes): ViewerGraph =
    this
      .modify(_.data.nodes.eachWhere(_.id in nodeIds).attrs).using(_ ++ attrs)
      .modify(_.data.arrows.eachWhere(_.id in nodeIds).attrs).using(_ ++ attrs)

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
    new ViewerGraph(ViewerGraphData(arrowsById, groupsById, nodeById, Map.empty))

  // In Scala 3.2 the type annotation is needed.
  val empty: ViewerGraph = basic(Set.empty, Set.empty)

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
