package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.InputType

def EdgesAttributesView(attrs: Var[Map[String, AttrValue]], selection: Boolean) =
  val common =
    buildRows(
      Style,
      ArrowHead,
      ArrowTail,
      Color     -> color,
      Constraint -> checkbox,
      Decorate  -> checkbox,
      Dir,
      FontColor -> color,
      FontName,
      FontSize  -> number,
      PenWidth  -> number,
      // FillColor -> color, // Not supported for now
      // Ordering,

    )
  val selectionRows = 
    buildRows(
      Label -> InputType.multiText,
      URL,
    )

  AttributesView(
    id    = "edge-attributes",
    title = "Edge Attributes",
    attrs = attrs,
    rows = if selection then selectionRows ++ common else common
  )
