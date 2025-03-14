package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.numberToLetterId
import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec

/** Represents a graph that can be visualized in the viewer.
  */
case class ViewerGraph(
    elements: ViewerGraphElements = ViewerGraphElements.minimal,
    id:       String = ViewerGraphElements.defaultRootId.value,
    tpe:      String = "digraph",
    counter:  Int = 0
) extends AttributesOps, TraversalOps, GroupsOps derives CanEqual:

  // --- mutable stuff ----
  private var nodeCounter = counter

  private def nextNodeId(): NodeId =
    @tailrec
    def nextAvailable(): NodeId =
      nodeCounter += 1
      val id = NodeId(numberToLetterId(nodeCounter))
      if id in nodes then nextAvailable() else id
    nextAvailable()
  // --- end mutable stuff ----

  val rootId = elements.rootId
  val rootGroup = elements.rootGroup

  val nodes = elements.nodes
  val arrows = elements.arrows
  val groups = elements.groups
  val memberships = elements.memberships

  val nodeIds = nodes.keySet
  val arrowIds = arrows.keySet
  val groupIds = groups.keySet

  lazy val nodesSeq = nodes.toSeq
  val arrowsSet = arrows.values.toSet

  val modifyElements = this.modify(_.elements)
  protected[graph] val modifyNodes = this.modify(_.elements.nodes)
  protected val modifyArrows = this.modify(_.elements.arrows)
  protected val modifyMemberships = this.modify(_.elements.memberships)

  def getNode(id: NodeId): Option[ViewerNode] =
    nodes.get(id)

  def membership(id: ElementId): Option[GroupId] =
    memberships.get(id)

  def summary =
    ViewerGraph.Summary(nodes = nodes.size, arrows = arrowsSet.size)

  /** allNodeIds that are not in the target of any arrow
    */
  def roots: Set[NodeId] =
    nodeIds -- arrowsSet.map(_.target)

  /** Creates a diagram containing the given symbols and the arrows between them.
    *
    * It ignores groups and memberships.
    */
  def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes = nodes.filter((id, _) => id in ids)
    val foundNodeIds = foundNodes.keySet
    val relevantArrows = arrows.filter((_, a) => (a.source in foundNodeIds) && (a.target in foundNodeIds))
    ViewerGraph(
      ViewerGraphElements(
        rootId = rootId,
        nodes  = foundNodes,
        arrows = relevantArrows,
        groups = Map(rootId -> rootGroup)
      )
    )

  def removeElements(elementIds: ElementIds): ViewerGraph =
    val classified = elementIds.classify
    val groupIdsToRemove = classified.clusters

    val updatedMemberships = memberships.flatMap: (elementId, groupId) =>
      // case 1: remove a nested group
      if elementId.asGroupId.exists(_ in groupIdsToRemove) then
        None
      // case 2: remove a node from a group
      else if groupId in groupIdsToRemove then
        // If group is deleted, add element to group's container if it exists
        memberships.get(groupId).map(containerId => elementId -> containerId)
      else
        Some(elementId -> groupId) // Keep unchanged

    val nodeIdsToRemove = classified.nodes
    val arrowIdsToRemove = classified.arrows

    val updatedArrows = arrows.filterNot { (arrowId, arrow) =>
      (arrowId in arrowIdsToRemove) || (arrow.source in nodeIdsToRemove) || (arrow.target in nodeIdsToRemove)
    }

    modifyElements.using(_.copy(
      nodes       = nodes -- nodeIdsToRemove,
      arrows      = updatedArrows,
      memberships = updatedMemberships,
      groups      = groups -- groupIdsToRemove
    ))

  private def maxArrowSequence(source: NodeId, target: NodeId): Int =
    val seqs = arrows.values
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList
    if seqs.isEmpty then 0 else seqs.max

  def addArrow(source: NodeId, target: NodeId): (ViewerGraph, Arrow) =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    (modifyArrows.using(_ + (arrow.id -> arrow)), arrow)

  def addNodeWithId(nodeId: NodeId, groupId: Option[GroupId] = None): ViewerGraph =
    modifyElements.using(
      _.copy(
        nodes       = nodes + (nodeId -> ViewerNode(nodeId, Attributes(Map(AttributeId("label") -> AttrValue(""))))),
        memberships = groupId.fold(memberships)(g => memberships + (nodeId -> g))
      )
    )

  def addNode(groupId: Option[GroupId] = None): (ViewerGraph, NodeId) =
    val nodeId = nextNodeId()
    (addNodeWithId(nodeId, groupId), nodeId)

  def addNodeAndArrowFrom(source: NodeId): (ViewerGraph, NodeId, ArrowId) =
    val nodeId = nextNodeId()
    val sourceGroup = membership(source)
    val (newGraph, arrow) = addNodeWithId(nodeId, sourceGroup).addArrow(source, nodeId)
    (newGraph, nodeId, arrow.id)

//  lazy val toTrees: Tree[ViewerNode] =
//    val paths =
//      for ns <- nodes.toList yield (ns.id.toString.split("/").init.toList, ns.label, ns)
//    Tree.fromPaths(paths, ".")

  /** Creates a new subdiagram with all the symbols containing the given String.
    */
  def filterByNodeId(str: String): ViewerGraph =
    val ids = nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase))
    subgraph(ids)

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

  private def numberToLetterId(n: Int): String =
    if n <= 0 then throw IllegalArgumentException("Node ID number must be positive")
    else
      def toBase26(n: Int): List[Int] =
        if n == 0 then Nil
        else
          val adjusted = n - 1
          val quotient = adjusted / 26
          val remainder = adjusted % 26
          remainder :: (if quotient > 0 then toBase26(quotient) else Nil)

      toBase26(n).reverse.map(i => (i + 97).toChar).mkString

  def basic(arrows: (NodeId, NodeId)*): ViewerGraph =
    ViewerGraph(
      ViewerGraphElements(arrows = arrows.map((a, b) => Arrow(a, b)).map(a => a.id -> a).toMap)
    )

  val minimal: ViewerGraph = ViewerGraph()

  case class Summary(
      nodes:  Int,
      arrows: Int
  )

end ViewerGraph
