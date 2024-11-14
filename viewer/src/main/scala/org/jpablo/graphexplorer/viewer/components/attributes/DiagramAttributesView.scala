package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState

def DiagramAttributesView(state: ViewerState) =
  div(
    idAttr := "diagram-attributes",
    GraphAttributesView(state),
    EdgeAttributesView(state),
    NodeAttributesView(state),
  )
