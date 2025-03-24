package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState

def StyleView(state: ViewerState) =
  div(
    idAttr := "style-view",
    div(
      div(cls := "attributes-title", h2("Diagram attributes")),
      DiagramAttributesView(state)
    )
  )
