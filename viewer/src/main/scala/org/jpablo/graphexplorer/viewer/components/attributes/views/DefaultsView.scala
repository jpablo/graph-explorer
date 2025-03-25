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
        defaultsView = true
      ),
      "Arrows" -> ArrowsAttributesView(
        state,
        state.rootTargetAttributesUpdates(AttributeTarget.edge),
        defaultsView = true
      ),
      "Groups" -> GraphAttributesView(
        state,
        state.rootTargetAttributesUpdates(AttributeTarget.graph),
        defaultsView = true
      )
    )

  div(
    idAttr := "defaults-view",
    div(cls := "attributes-title", h2("Defaults")),
    div(
      div(
        role := "tablist",
        cls  := "tabs tabs-border tabs-xs",
        for (tabName, i) <- tabsData.map(_._1).zipWithIndex
        yield a(
          role := "tab",
          cls  := "tab flex-1",
          cls("tab-active") <-- tabVisible(i),
          onClick.mapTo(i) --> tabIndex,
          tabName
        )
      ),
      div(
        idAttr := "defaults-view-content",
        for (view, i) <- tabsData.map(_._2).zipWithIndex
        yield view.amend(cls("hidden") <-- tabVisible(i).not)
      )
    )
  )
