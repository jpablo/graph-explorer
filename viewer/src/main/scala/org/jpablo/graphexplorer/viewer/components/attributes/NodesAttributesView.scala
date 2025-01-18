package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}

def NodesAttributesView(attrs: Var[Map[String, AttrValue]]) =
  AttributesView(
    id    = "node-attributes",
    title = "Node Attributes",
    attrs = attrs,
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
