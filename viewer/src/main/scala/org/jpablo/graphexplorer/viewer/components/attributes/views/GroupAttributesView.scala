package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.range

def GroupAttributesView(
    state:        ViewerState,
    attrsVar:     Var[AttributesUpdates],
    defaults:     Option[Signal[Attributes]] = None,
    defaultsView: Boolean
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(attrsVar, state.layout, defaults)
  import builder.{row, rows}

  val defaultsViewS = Signal.fromValue(defaultsView)

  val extraMenuDir = MenuDirection.end
  val initialMenuItems = 7

  val labelRow =
    row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection || defaultsViewS)

  AttributesView(
    id = "graph-attributes",
    rows = rows(
      row(CornerStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = graphCornerStyleOptions),
      row(FillColor, InputType.menuWithExtra(lightRows7.length, extraMenuDir))
        .copy(
          options = lightRows7 ++ colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.menuWithExtra(initialMenuItems)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(PenColor, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),

      labelRow,
      row(FontColor, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),
      row(FontName, InputType.select),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))),

      if defaultsView then "" else URL
    )
  )
