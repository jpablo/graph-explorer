package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

//type Arrows = mutable.LinkedHashMap[NodeId, Arrow]
type Arrows = Map[NodeId, Arrow]
//type Memberships = mutable.LinkedHashMap[NodeId, Option[NodeId]]
type Memberships = Map[NodeId, Option[NodeId]]

case class ViewerGraphData(
    arrows:      Arrows,
    groups:      Map[NodeId, ViewerGroup],
    nodes:       Map[NodeId, ViewerNode],
    memberships: Memberships
):
//  println("ViewerGraphData")
  assert(memberships.nonEmpty, "At least one membership is required (the root node)")
  // fail fast if no root node is provided
  def rootNodeId: NodeId =
//    println("ViewerGraphData")
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  assert(rootNodeId in groups, s"Root node $rootNodeId not found in groups: $groups")

  // ---- Arrow ops ----
  var arrowValues: Iterable[Arrow] = arrows.values
  var arrowsSet: Set[Arrow] = arrowValues.toSet
  var arrowsMap: Map[NodeId, Arrow] = arrows

//  var arrowValues: Iterable[Arrow] = mutable.Iterable.empty
//  var arrowsSet: Set[Arrow] = Set.empty
//  var arrowsMap: Map[NodeId, Arrow] = Map.empty
//
//  def syncArrows() =
////    println(s"Syncing arrows")
//    arrowValues = arrows.values
//    arrowsSet   = arrowValues.toSet
//    arrowsMap   = arrows.toMap

//  syncArrows()

  def filterArrows(f: ((NodeId, Arrow)) => Boolean) =
//    println(s"Filtering arrows")
    arrows.filter(f)

//  def modifyArrows(f: Arrows => Arrows): ViewerGraphData =
////    println(s"Modifying arrows")
//    f(arrows)
//    syncArrows()
//    this

  def addArrow(a: Arrow): ViewerGraphData =
    copy(arrows = arrows.updated(a.id, a))

  def concatArrows(other: Arrows) =
    arrows ++ other
//  def concatArrows(other: Arrows) =
//    arrows ++= other
//    syncArrows()
//    this
  // -----------------

  // ---- Membership ops ----

  def addMembership(nodeId: NodeId, groupId: Option[NodeId]): ViewerGraphData =
    copy(memberships = memberships.updated(nodeId, groupId))
  // ------------------------

  val root: ViewerGroup =
//    println("ViewerGraphData")
    groups(rootNodeId)

  val nodesSet = nodes.values.toSet

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    val a2 =
      arrows -- arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target))
    // val a2 =
    //   arrows --= arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target))
    copy(
      nodes  = nodes -- ids,
      arrows = a2,
      groups = groups -- ids,
//      memberships = memberships.subtractAll(ids)
      memberships = memberships.removedAll(ids)
    )

  def arrowSequences(source: NodeId, target: NodeId): List[Int] =
    arrowValues
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList

  def maxArrowSequence(source: NodeId, target: NodeId): Int = {
    val seqs = arrowSequences(source, target)
    if seqs.isEmpty then 0 else seqs.max
  }
