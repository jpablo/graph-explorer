package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def GraphAttributesView(state: ViewerState) =
  AttributesView(
    id    = "graph-attributes",
    title = "Graph Attributes",
    attrs = state.graphElementAttributes,
    rows = buildRows(
      Layout,
      Rankdir,
      Label,
      LabelLoc,
      Splines,
      BgColor -> color,
      FontName,
      FontColor -> color,
      FontSize  -> number,
      Overlap,
      RankSep -> number,
      NodeSep -> number,
//      Rotate  -> number
//      Orientation -> number
    )
  )
