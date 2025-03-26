package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, number, range}

def NodesAttributesView(
    parent:       String,
    state:        ViewerState,
    updates:      Var[AttributesUpdates],
    defaults:     Option[Signal[Attributes]] = None,
    defaultsView: Boolean
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.layout, defaults)
  import builder.{row, rows}

  val labelRow =
    row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection || Signal.fromValue(defaultsView))

  val extraMenuDir     = MenuDirection.end
  val initialMenuItems = 7

  val shapeRow = row(Shape, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = shapesOptions)

  val sidesRow =
    row(
      attr = Sides,
      inputType = number(start = Some(3), end = Some(10), step = Some(1)),
      hidden = Some(
        builder.invalidLayout(Sides) || shapeRow.inputVar.signal.map(_.exists(_.toString != Shape.polygon.toString))
      )
    )

  AttributesView(
    id = "node-attributes",
    rows = rows(
      shapeRow,
      row(Color, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = colorOptions),
      row(FillColor, InputType.menuWithExtra(initialMenuItems, extraMenuDir))
        .copy(
          options = colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(CornerStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = cornerStyleOptions),
      // ---------- label stuff ------------//
      labelRow,
      row(NodeLabelLoc, InputType.menuWithExtra(initialMenuItems)).copy(options = nodeLabelVerticalAlignOptions),
      if defaultsView then XLabel else "",
      row(FontColor, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = colorOptions),
      FontName -> InputType.select,
      FontSize -> range(start = Some(1), end = Some(100), step = Some(1))
    ),
    extra = rows(
      if defaultsView then "" else InvisibleStyle -> checkbox,
      sidesRow,
      Regular     -> checkbox,
      Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
      Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
      if defaultsView then "" else URL
    )
  )
