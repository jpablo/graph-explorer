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

  val shapeRow = row(Shape, InputType.menuWithExtra(7, extraMenuDir)).copy(options = shapesOptions)

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
      row(CornerStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = cornerStyleOptions),
      row(FillColor, InputType.menuWithExtra(lightRows7.length, extraMenuDir))
        .copy(
          options = lightRows7 ++ colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(Color, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),
      // ---------- label stuff ------------//
      labelRow,
      row(NodeLabelLoc, InputType.menuWithExtra(initialMenuItems)).copy(options = nodeLabelVerticalAlignOptions),
      if defaultsView then "" else XLabel,
      row(FontColor, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),
      row(FontName, InputType.select),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1)))
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
