package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}

def NodesAttributesView(attrs: Var[Map[String, AttrValue]], selection: Boolean) =
  val common = 
    buildRows(
      Shape,
      Sides       -> number,
      Style,
      Color     -> color,
      FillColor -> color,
      FontColor -> color,
      FontName,
      FontSize  -> number,
      LabelLoc,
      Ordering,
      Orientation -> number,
      PenWidth    -> number,
      Peripheries -> number,
      Regular     -> checkbox,
    )
  val selectionRows = 
    buildRows(
      Label -> InputType.multiText,
      URL,
    )
  AttributesView(
    id    = "node-attributes",
    title = "Node Attributes",
    attrs = attrs,
    rows = if selection then selectionRows ++ common else common
  )

