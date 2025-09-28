package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{
  ToolbarArrowsAttributesView,
  ToolbarGroupAttributesView,
  ToolbarNodesAttributesView
}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

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

def AttributesToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) = {
  import commands.all
  div(
    idAttr := "selection-toolbar",
    cls    := "navbar",
    child <--
      Signal.combine(
        state.selection.signal,
        state.visibleGraph.distinct
      ).map: (selectedNodes, visibleGraph) =>
        val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) =>
            ToolbarArrowsAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(arrowIds))
            )

          case (false, true, false) =>
            ToolbarNodesAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(nodeIds))
            )

          case (false, false, true) =>
            ToolbarGroupAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(clusterIds))
            )

          case (false, false, false) =>
            val visibleNodeIds  = visibleGraph.nodeIds
            val visibleArrowIds = visibleGraph.arrowIds
            val visibleGroupIds = visibleGraph.groupIds - ViewerGraphElements.defaultRootId
            val groupCount      = visibleGroupIds.size
            div(
              cls := "flex flex-row gap-2",
              Select(
                placeholderText = Some("Select by kind"),
                options = List(
                  (ElementKind.Nodes, visibleNodeIds.size),
                  (ElementKind.Edges, visibleArrowIds.size),
                  (ElementKind.Groups, groupCount)
                ).collect {
                  case (kind, count) if count > 0 => s"${kind.label} ($count)" -> kind.id
                },
                onChange.mapToValue --> { value =>
                  ElementKind.fromId(value) match
                    case Some(ElementKind.Nodes)  => state.selection.set1(visibleNodeIds)
                    case Some(ElementKind.Edges)  => state.selection.set1(visibleArrowIds)
                    case Some(ElementKind.Groups) => state.selection.set1(visibleGroupIds)
                    case None                     => ()
                }
              )
            )

          case _ =>
            val elementTypes = Map(
              ElementKind.Edges  -> (ElementIds(arrowIds), ElementKind.Edges.label),
              ElementKind.Nodes  -> (ElementIds(nodeIds), ElementKind.Nodes.label),
              ElementKind.Groups -> (ElementIds(clusterIds), ElementKind.Groups.label)
            )

            div(
              cls := "flex flex-row gap-2",
              Select(
                placeholderText = Some(s"Filter ${selectedNodes.size} objects"),
                options = elementTypes
                  .collect:
                    case (kind, (ids, _)) if ids.nonEmpty => s"${kind.label} (${ids.size})" -> kind.id
                  .toList,
                onChange.mapToValue --> { value =>
                  ElementKind.fromId(value).foreach: kind =>
                    for (ids, _) <- elementTypes.get(kind) do
                      state.selection.set(ids)
                }
              )
            )
  )
}
