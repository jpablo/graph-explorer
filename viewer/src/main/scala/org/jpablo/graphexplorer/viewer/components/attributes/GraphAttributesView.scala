package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.models.Attributes

def GraphAttributesView(state: ViewerState, attrsVar: Var[Attributes], selection: Boolean) =
  AttributesView(
    id    = "graph-attributes",
    titleStr = "Cluster Attributes",
    attrs = attrsVar,
    defaults = None,
    if selection then Seq.empty 
    else
      buildRows(
        "Layout",
        Layout,
        Rankdir,
        Splines,
      ),
      
    buildRows(
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
