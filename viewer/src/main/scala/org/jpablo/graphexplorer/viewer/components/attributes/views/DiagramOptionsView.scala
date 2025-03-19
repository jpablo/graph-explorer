package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.RootGraphAttributesView
import org.jpablo.graphexplorer.viewer.state.ViewerState

def DiagramOptionsView(state: ViewerState) =
  div(
    div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", "Diagram Options"))),
    RootGraphAttributesView(state),
  )
