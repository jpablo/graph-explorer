package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.models.NodeId.isArrowId
import org.jpablo.graphexplorer.viewer.models.NodeId.isClusterId
import org.jpablo.graphexplorer.viewer.widgets.{InputType, Select as SelectInput}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.widgets.InputType.{color, range}

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
              div(
                cls := "divider",
                div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Selected Arrows (${arrowIds.size})"))
              ),
              EdgesAttributesView(
                state,
                attrs     = state.nodesAttributes(arrowIds),
                defaults  = Some(state.visibleGraph.map(_.root.edgeAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, true, false) =>
            div(
              div(
                cls := "divider",
                div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Selected Nodes (${nodeIds.size})"))
              ),
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
              div(
                cls := "divider",
                div(
                  cls := "divider-content",
                  h2(cls := "text-lg font-semibold", s"Selected Clusters (${clusterIds.size})")
                )
              ),
              GraphAttributesView(
                state     = state,
                attrsVar  = state.nodesAttributes(clusterIds),
                defaults  = Some(state.visibleGraph.map(_.root.attributes)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, false, false) =>
            GeneralAttributesView(state)

          case _ =>
            div(
              div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Filter"))),
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

def GeneralAttributesView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)

  val tabsData =
    List(
      "Nodes"  -> NodesAttributesView("DiagramAttributesView", state, state.nodeTargetAttributes, selection = false),
      "Arrows" -> EdgesAttributesView(state, state.edgeTargetAttributes, selection = false),
      "Groups" -> GraphAttributesView(state, state.graphTargetAttributes, selection = false)
    )
  div(
    div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Diagram Options"))),
    RootGraphOptions(state),
    div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Defaults"))),
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

def RootGraphOptions(state: ViewerState) =
  val builder = RowBuilder(state.graphTargetAttributes, None)
  AttributesView(
    id       = "root-graph-attributes",
    titleStr = "Root Graph Options",
    builder.buildRows(
      "Title",
      builder.simpleRow(
        Label,
        InputType.multiText,
        onReset     = Some(""),
        label       = Some("Title"),
        placeholder = Some("Enter diagram title")
      ),
      LabelLoc,
      LabelJust,
      "Layout",
      Layout,
      Rankdir,
      "Other",
      Splines,
      Concentrate -> InputType.checkbox,
      BgColor -> color,
      Pad     -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
      RankSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      NodeSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05))
    )
  ).amend(cls := "mb-8")
