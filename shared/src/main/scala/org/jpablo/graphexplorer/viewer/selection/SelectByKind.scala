package org.jpablo.graphexplorer.viewer.selection

import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}

enum ElementKind derives CanEqual:
  case Nodes, Edges, Groups

  def id: String =
    this match
      case Nodes  => "nodes"
      case Edges  => "edges"
      case Groups => "groups"

  def label: String =
    this match
      case Nodes  => "Nodes"
      case Edges  => "Arrows"
      case Groups => "Groups"

object ElementKind:
  def fromId(id: String): Option[ElementKind] =
    id match
      case "nodes"  => Some(ElementKind.Nodes)
      case "edges"  => Some(ElementKind.Edges)
      case "groups" => Some(ElementKind.Groups)
      case _        => None

object SelectByKind:
  case class MenuOption(label: String, kind: ElementKind, count: Int)

  def optionsForGraph(graph: ViewerGraph): List[MenuOption] =
    val nodeCount  = graph.nodeIds.size
    val arrowCount = graph.arrowIds.size
    val groupCount = (graph.groupIds - ViewerGraphElements.defaultRootId).size
    List(
      menuOption(ElementKind.Nodes, nodeCount),
      menuOption(ElementKind.Edges, arrowCount),
      menuOption(ElementKind.Groups, groupCount)
    ).flatten

  def idsForGraph(graph: ViewerGraph, kind: ElementKind): ElementIds =
    kind match
      case ElementKind.Nodes  => ElementIds(graph.nodeIds)
      case ElementKind.Edges  => ElementIds(graph.arrowIds)
      case ElementKind.Groups => ElementIds(graph.groupIds - ViewerGraphElements.defaultRootId)

  def optionsForSelection(ids: IdsByKind): List[MenuOption] =
    List(
      menuOption(ElementKind.Nodes, ids.nodes.size),
      menuOption(ElementKind.Edges, ids.arrows.size),
      menuOption(ElementKind.Groups, ids.groups.size)
    ).flatten

  def idsForSelection(ids: IdsByKind, kind: ElementKind): ElementIds =
    kind match
      case ElementKind.Nodes  => ElementIds(ids.nodes)
      case ElementKind.Edges  => ElementIds(ids.arrows)
      case ElementKind.Groups => ElementIds(ids.groups)

  private def menuOption(kind: ElementKind, count: Int): Option[MenuOption] =
    if count > 0 then Some(MenuOption(s"${kind.label} ($count)", kind, count)) else None
