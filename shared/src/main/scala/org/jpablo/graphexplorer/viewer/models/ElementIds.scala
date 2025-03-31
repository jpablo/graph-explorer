package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.extensions.notIn
import upickle.default.*

import scala.annotation.targetName

case class IdsByKind(
    clusters: Set[GroupId] = Set.empty,
    nodes:    Set[NodeId] = Set.empty,
    arrows:   Set[ArrowId] = Set.empty
)

case class ElementIds(ids: Set[? <: ElementId] = Set.empty) extends AnyVal:

  // TODO: Find a way to avoid this cast
  private def upcast = ids.asInstanceOf[Set[ElementId]]

  def isEmpty: Boolean      = ids.isEmpty
  def nonEmpty: Boolean     = ids.nonEmpty
  def size: Int             = ids.size
  def head: ElementId       = ids.head
  def toggle(id: ElementId) = if id notIn this then this + id else this - id

  infix def intersect(that: ElementIds) = ElementIds(upcast intersect that.upcast)

  def contains(id: ElementId): Boolean =
    upcast.contains(id)

  def filter(p: ElementId => Boolean): ElementIds =
    ElementIds(ids.filter(p))

  def +(that:  ElementId): ElementIds  = ElementIds(upcast + that)
  def -(that:  ElementId): ElementIds  = ElementIds(upcast - that)
  def ++(that: ElementIds): ElementIds = ElementIds(upcast ++ that.upcast)
  def --(that: ElementIds): ElementIds = ElementIds(upcast -- that.upcast)
  @targetName("addSet")
  def ++(that: Set[? <: ElementId]): ElementIds = ElementIds(upcast ++ that)
  @targetName("removeSet")
  def --(that: Set[? <: ElementId]): ElementIds = ElementIds(upcast -- that)

  def nodeIds  = ids.collect { case id: NodeId => id }
  def arrowIds = ids.collect { case id: ArrowId => id }
  def groupIds = ids.collect { case id: GroupId => id }

  def memberIds: Set[GroupMemberId] = ids.collect { case id: (GroupId | NodeId) => id }

  def classify: IdsByKind =
    ids.foldLeft(IdsByKind()): (acc, eId) =>
      eId match
        case id: GroupId => acc.copy(clusters = acc.clusters + id)
        case id: NodeId  => acc.copy(nodes = acc.nodes + id)
        case id: ArrowId => acc.copy(arrows = acc.arrows + id)

object ElementIds:
  def from(ids: ElementId*): ElementIds = ElementIds(ids.toSet)

  given rw: ReadWriter[ElementIds] = readwriter[Set[ElementId]].bimap[ElementIds](_.upcast, ElementIds(_))
