package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.graph.ExportViewerGraphElements.{ExportArrow, ExportViewerNode}
import upickle.default.*

/** Simplified representation of the graph elements used in the viewer.
  *
  * Useful for exporting the graph structure without the full details of the nodes and arrows.
  */
case class ExportViewerGraphElements(
    nodes:       Iterable[ExportViewerNode] = Iterable.empty,
    arrows:      Iterable[ExportArrow] = Iterable.empty,
    memberships: Map[String, GroupId] = Map.empty,
    groups:      Iterable[GroupId] = Iterable.empty
) derives ReadWriter

object ExportViewerGraphElements:

  // Using a simplified representation for nodes and arrows because in the original
  // case classes the sealed trait adds an unnecessary $type json field
  case class ExportViewerNode(id: NodeId, attributes: Attributes = Attributes.empty) derives ReadWriter
  case class ExportArrow(source: NodeId, target: NodeId, attributes: Attributes = Attributes.empty) derives ReadWriter

  def fromViewerGraphElements(elems: ViewerGraphElements) =
    ExportViewerGraphElements(
      nodes = elems.nodes.values.map(n => ExportViewerNode(n.id, n.attributes)),
      arrows = elems.arrows.values.map(a => ExportArrow(a.source, a.target, a.attributes)),
      memberships = elems.memberships.map((k, v) => k.value -> v),
      groups = elems.groups.keys
    )
