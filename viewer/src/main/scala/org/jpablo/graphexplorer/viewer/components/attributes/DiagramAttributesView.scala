package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState

import com.raquo.laminar.api.features.unitArrows

def DiagramAttributesView(state: ViewerState) =
  val tabIndex = Var(0)
  def tabVisible(i: Int) = tabIndex.signal.map(_ == i)
  div(
    idAttr := "diagram-attributes",
    div(
      role := "tablist",
      cls  := "tabs tabs-lifted tabs-xs",
      a(role := "tab", cls := "tab", "Graph", cls("tab-active") <-- tabVisible(0), onClick --> tabIndex.set(0)),
      a(role := "tab", cls := "tab", "Edges",  cls("tab-active") <-- tabVisible(1), onClick --> tabIndex.set(1)),
      a(role := "tab", cls := "tab", "Nodes",  cls("tab-active") <-- tabVisible(2), onClick --> tabIndex.set(2))
    ),
    div(
      idAttr := "diagram-attributes-content",
      GraphAttributesView(state).amend(cls("hidden") <-- tabVisible(0).not),
      EdgeAttributesView(state).amend(cls("hidden") <-- tabVisible(1).not),
      NodeAttributesView(state).amend(cls("hidden") <-- tabVisible(2).not)
    )
  )
