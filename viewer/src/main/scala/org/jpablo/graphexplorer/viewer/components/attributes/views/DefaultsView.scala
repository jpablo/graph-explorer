package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.state.ViewerState

def DefaultsView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)

  val tabsData =
    List(
      "Nodes" -> NodesAttributesView(
        "DiagramAttributesView",
        state,
        state.rootTargetAttributesUpdates(AttributeTarget.node),
        selection = false
      ),
      "Arrows" -> EdgesAttributesView(
        state,
        state.rootTargetAttributesUpdates(AttributeTarget.edge),
        selection = false
      ),
      "Groups" -> GraphAttributesView(
        state,
        state.rootTargetAttributesUpdates(AttributeTarget.graph),
        selection = false
      )
    )

  div(
    idAttr := "style-view",
    div(
      div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Defaults"))),
      div(
        cls := "flex justify-center",
        div(
          role := "tablist",
          cls  := "tabs tabs-box tabs-xs w-[300px]",
          for (tabName, i) <- tabsData.map(_._1).zipWithIndex
            yield a(
              role := "tab",
              cls  := "tab flex-1",
              cls("tab-active") <-- tabVisible(i),
              onClick.mapTo(i) --> tabIndex,
              tabName
            )
        )
      ),
      div(
        idAttr := "diagram-defaults-view",
        for (view, i) <- tabsData.map(_._2).zipWithIndex yield view.amend(cls("hidden") <-- tabVisible(i).not)
      )
    )
  )
