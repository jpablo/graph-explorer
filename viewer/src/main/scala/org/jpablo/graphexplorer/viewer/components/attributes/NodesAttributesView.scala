package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}

def NodesAttributesView(state: ViewerState) =
  AttributesView(
    id    = "node-attributes",
    title = "Node Attributes",
    attrs = state.nodeTargetAttributes,
    rows = buildRows(
      Shape,
      Style,
      Color     -> color,
      FillColor -> color,
      LabelLoc,
      FontSize  -> number,
      FontColor -> color,
      FontName,
      Ordering,
      Orientation -> number,
      PenWidth    -> number,
      Peripheries -> number,
      Sides       -> number,
      Regular     -> checkbox
    )
  )
