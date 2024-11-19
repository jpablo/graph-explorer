package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState

import com.raquo.laminar.api.features.unitArrows

def DiagramAttributesView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)
  val tabsData =
    List(
      "Graph" -> GraphAttributesView(state),
      "Nodes" -> NodesAttributesView(state),
      "Edges" -> EdgesAttributesView(state)
    )
  div(
    idAttr := "diagram-attributes",
    div(
      role := "tablist",
      cls  := "tabs tabs-lifted tabs-xs",
      for (tabName, i) <- tabsData.map(_._1).zipWithIndex
      yield a(
        role := "tab",
        cls  := "tab",
        tabName,
        cls("tab-active") <-- tabVisible(i),
        onClick --> tabIndex.set(i)
      )
    ),
    div(
      idAttr := "diagram-attributes-content",
      for (view, i) <- tabsData.map(_._2).zipWithIndex yield view.amend(cls("hidden") <-- tabVisible(i).not)
    )
  )
