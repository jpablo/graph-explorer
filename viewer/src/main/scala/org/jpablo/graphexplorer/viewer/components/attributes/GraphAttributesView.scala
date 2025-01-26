package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def GraphAttributesView(state: ViewerState) =
  AttributesView(
    id    = "graph-attributes",
    title = "Graph Attributes",
    attrs = state.graphTargetAttributes,
    defaults = None,
    buildRows(
      "Layout",
      Layout,
      Rankdir,
      Splines,
      
      "Labels",
      Label -> multiText,
      LabelLoc,
      
      "Fonts",
      FontName,
      FontColor -> color,
      FontSize -> number(),
      
      "Colors",
      BgColor -> color,
      
      "Spacing",
      Pad -> number(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
      RankSep -> number(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      NodeSep -> number(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      
      // "Other",
      // Overlap
//      Rotate  -> number
//      Orientation -> number
    )
  )
