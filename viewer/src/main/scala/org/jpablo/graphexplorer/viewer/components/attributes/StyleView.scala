package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.models.NodeId.isArrowId
import org.jpablo.graphexplorer.viewer.models.NodeId.isClusterId
import org.jpablo.graphexplorer.viewer.widgets.Select as SelectInput
import org.jpablo.graphexplorer.viewer.models.NodeId

def StyleView(state: ViewerState) =
  div(
    idAttr := "diagram-attributes",
    child <--
      state.diagramSelection.signal.map: selectedNodes =>
        val (arrowIds, notArrows) = selectedNodes.partition(isArrowId)
        val (clusterIds, nodeIds) = notArrows.partition(isClusterId)

        val elementTypes = Map(
          "edges"    -> (arrowIds, "Arrows"),
          "nodes"    -> (nodeIds, "Nodes"),
          "clusters" -> (clusterIds, "Clusters")
        )

        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) =>
            div(
              div(cls := "text-center pb-2", "Selected Arrows"),
              EdgesAttributesView(
                state,
                attrs     = state.nodesAttributes(arrowIds),
                defaults  = Some(state.visibleGraph.map(_.root.edgeAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, true, false) =>
            div(
              div(cls := "text-center pb-2", "Selected Nodes"),
              NodesAttributesView(
                "SelectionAttributes",
                state,
                attrsVar  = state.nodesAttributes(nodeIds),
                defaults  = Some(state.visibleGraph.map(_.root.nodeAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, false, true) =>
            div(
              div(cls := "text-center pb-2", "Selected Clusters"),
              GraphAttributesView(
                state = state,
                attrsVar = state.nodesAttributes(clusterIds),
                defaults  = Some(state.visibleGraph.map(_.root.clusterAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, false, false) =>
            DefaultAttributesView(state)

          case _ =>
            div(
              div(cls := "text-center pb-2", "Filter"),
              SelectInput(
                placeholderText = s"${selectedNodes.size} objects",
                options = elementTypes.collect {
                  case (key, (ids, description)) if ids.nonEmpty =>
                    s"$description (${ids.size})" -> key
                }.toList,
                onChange.mapToValue --> { value =>
                  for (ids, _) <- elementTypes.get(value) do
                    state.diagramSelection.set(ids)
                },
                cls := "w-full mb-4"
              )
            )
  )

def DefaultAttributesView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)

  val tabsData =
    List(
      "Nodes"  -> NodesAttributesView("DiagramAttributesView", state, state.nodeTargetAttributes, selection = false),
      "Arrows" -> EdgesAttributesView(state, state.edgeTargetAttributes, selection = false),
      "Groups" -> GraphAttributesView(state, state.graphTargetAttributes, selection = false)
    )
  div(
    div(cls := "text-center pb-2", "Defaults"),
    div(
      cls := "flex justify-center",
      div(
        role := "tablist",
        cls  := "tabs tabs-boxed tabs-xs w-[300px]",
        for (tabName, i) <- tabsData.map(_._1).zipWithIndex
        yield a(
          role := "tab",
          cls  := "tab flex-1",
          tabName,
          cls("tab-active") <-- tabVisible(i),
          onClick --> tabIndex.set(i)
        )
      )
    ),
    div(
      idAttr := "diagram-attributes-content",
      for (view, i) <- tabsData.map(_._2).zipWithIndex yield view.amend(cls("hidden") <-- tabVisible(i).not)
    )
  )
