package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.airstream.core.Signal

def EdgesAttributesView(state: ViewerState, attrs: Var[Attributes], defaults: Option[Signal[Attributes]] = None, selection: Boolean) =
  val builder = RowBuilder(attrs)
  AttributesView(
    id    = "edge-attributes",
    titleStr = "Edge Attributes",
    builder.buildRows(
      "Label",
      if selection then Label -> InputType.multiText else "",
      if selection then XLabel else "",
      Decorate  -> checkbox,
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize  -> number(),
      "Style",
      Style,
      Color     -> color,
      PenWidth  -> number(),
      Dir,
      ArrowHead,
      ArrowTail,
      "Layout",
      Constraint -> checkbox,
      // FillColor -> color, // Not supported for now
      // Ordering,
      "Other",
      if selection then URL else ""
    )
  )
