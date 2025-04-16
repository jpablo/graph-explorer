package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.rightPanel.{ArrowsList, NodesList}
import org.jpablo.graphexplorer.viewer.state.ViewerState

def ElementsView(state: ViewerState) =
  val tabIndex           = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)
  val summary            = state.fullGraph.map(_.summary)

  val tabsData =
    List(
      "Nodes"  -> NodesList(state),
      "Arrows" -> ArrowsList(state)
    )

  div(
    idAttr := "elements-view",
    div(
      role := "tablist",
      cls  := "tabs tabs-border tabs-xs",
      for (tabName, i) <- tabsData.map(_._1).zipWithIndex
      yield a(
        role := "tab",
        cls  := "tab flex-1",
        cls("tab-active") <-- tabVisible(i),
        onClick.mapTo(i) --> tabIndex,
        child <-- summary.map(s => s"$tabName (${if i == 0 then s.nodes else s.arrows})")
      )
    ),
    div(
      idAttr := "elements-view-content",
      for (view, i) <- tabsData.map(_._2).zipWithIndex
      yield view.amend(cls("hidden") <-- tabVisible(i).not)
    )
  )
