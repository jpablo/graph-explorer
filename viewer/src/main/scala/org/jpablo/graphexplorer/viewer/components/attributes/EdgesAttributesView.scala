package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def EdgesAttributesView(attrs: Var[Map[String, AttrValue]]) =
  AttributesView(
    id    = "edge-attributes",
    title = "Edge Attributes",
    attrs = attrs,
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
      FontName,
      Constraint -> checkbox,
    )
  )
