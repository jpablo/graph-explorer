package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.VerticalAttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributeUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def ArrowsAttributesView(
    state:        ViewerState,
    updates:      Var[AttributeUpdates],
    defaults:     Option[Signal[Attributes]] = None,
    defaultsView: Boolean
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(updates, state.graphLayout, defaults)
  import builder.{row, rows}
  val labelRow =
    row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection || Signal.fromValue(defaultsView))

  val extraMenuDir     = MenuDirection.end
  val initialMenuItems = 7

  VerticalAttributesView(
    id = "edge-attributes",
    showHeaders = false,
    rows = rows(
      row(Color, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),
      row(EdgeStyle, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = arrowStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(ArrowHead, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = arrowTypeOptions),
      row(ArrowTail, InputType.menuWithExtra(initialMenuItems, extraMenuDir)).copy(options = arrowTypeOptions),
      ArrowSize -> range(start = Some(0), end = Some(5), step = Some(0.1)),
      labelRow,
      "Text Format",
      row(FontColor, InputType.menuWithExtra(mediumRows7.length, extraMenuDir)).copy(options = mediumRows7 ++ colorOptions),
      row(FontName, InputType.select),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1)))
      // --- extra
    ),
    extra = rows(
      Constraint -> checkbox,
      Decorate   -> checkbox,
      if defaultsView then "" else XLabel,
      if defaultsView then "" else URL
    )
  )
