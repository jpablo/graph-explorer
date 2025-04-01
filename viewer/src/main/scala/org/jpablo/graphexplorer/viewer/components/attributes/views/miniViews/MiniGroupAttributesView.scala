package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, range}

def MiniGroupAttributesView(
    state:    ViewerState,
    attrsVar: Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(attrsVar, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  AttributesView(
    id = "mini-graph-attributes",
    rows = rows(
      row(CornerStyle, InputType.menuWithExtra(4)).copy(options = graphCornerStyleOptions),
      row(FillColor, InputType.menuWithExtra(lightRows4.length))
        .copy(
          options = lightRows4 ++ colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.menuWithExtra(4)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(PenColor, InputType.menuWithExtra(mediumRows4.length)).copy(options = mediumRows4 ++ colorOptions),
      // ---------- label stuff ------------
      labelRow,
      row(ClusterLabelLoc, InputType.menuWithExtra(4)).copy(
        options = clusterVerticalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(LabelJust, InputType.menuWithExtra(4)).copy(
        options = horizontalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.menuWithExtra(mediumRows4.length)).copy(
        options = mediumRows4 ++ colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    ),
    extra = rows(
      InvisibleStyle -> checkbox
    )
  )
