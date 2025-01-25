package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.models.Attributes

def EdgesAttributesView(attrs: Var[Attributes], selection: Boolean) =
  AttributesView(
    id    = "edge-attributes",
    title = "Edge Attributes",
    attrs = attrs,
    buildRows(
      "Label",
      if selection then Label -> InputType.multiText else "",
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
