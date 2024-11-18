package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def EdgesAttributesView(state: ViewerState) =
  AttributesView(
    id    = "edge-attributes",
    title = "Edge Attributes",
    attrs = state.edgeElementAttributes,
    rows = buildRows(
      Style,
      ArrowHead,
      ArrowTail,
      Dir,
      PenWidth  -> number,
      Color     -> color,
      Decorate  -> checkbox,
      FontSize  -> number,
      FontColor -> color,
      FontName
    )
  )
