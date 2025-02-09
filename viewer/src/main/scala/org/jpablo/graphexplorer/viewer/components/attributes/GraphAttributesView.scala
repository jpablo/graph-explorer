package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.models.Attributes

def GraphAttributesView(state: ViewerState, attrsVar: Var[Attributes], selection: Boolean) =
  val builder = RowBuilder(attrsVar)
  AttributesView(
    id    = "graph-attributes",
    titleStr = "Cluster Attributes",
    if selection then
      Seq.empty
    else
      builder.buildRows(
        "Layout",
        Layout,
        Rankdir,
        Splines,
      ),

    builder.buildRows(
      "Labels",
      Label -> multiText,
      LabelLoc,
      LabelJust,
      "Fonts",
      FontName,
      FontColor -> color,
      FontSize -> number(),

      "Background",
      BgColor -> color,
      "Border",
      if selection then PenColor -> color else "",
      PenWidth -> number(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
    ),
    if selection then
      Seq.empty
    else
      builder.buildRows(
        "Spacing",
        Pad -> number(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
        RankSep -> number(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
        NodeSep -> number(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      )
  )
