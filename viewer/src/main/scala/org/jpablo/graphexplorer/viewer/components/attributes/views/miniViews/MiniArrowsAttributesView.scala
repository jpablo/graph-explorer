package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.{
  AttributesView,
  arrowStyleOptions,
  arrowTypeOptions,
  colorOptions,
  mediumRows4
}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def MiniArrowsAttributesView(
    state:    ViewerState,
    updates:  Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  given owner: Owner = state.owner
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  AttributesView(
    id = "edge-attributes",
    rows = rows(
      row(Color, InputType.menuWithExtra(mediumRows4.length)).copy(options = mediumRows4 ++ colorOptions),
      row(EdgeStyle, InputType.menuWithExtra(5, cardClass = Some("narrow-card"))).copy(options = arrowStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(ArrowHead, InputType.menuWithExtra(5, cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions),
      row(ArrowTail, InputType.menuWithExtra(5, cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions),
      ArrowSize  -> range(start = Some(0), end = Some(5), step = Some(0.1)),
      Constraint -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      row(FontColor, InputType.menuWithExtra(mediumRows4.length)).copy(
        options = mediumRows4 ++ colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    ),
    extra = rows(
      Constraint -> checkbox,
      Decorate   -> checkbox,
      XLabel,
      URL
    )
  )
