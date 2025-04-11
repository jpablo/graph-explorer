package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{AttributeUpdates, Attributes}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, range}

def ToolbarGroupAttributesView(
    state:    ViewerState,
    attrsVar: Var[AttributeUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(attrsVar, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  HorizontalAttributesView(
    rows = rows(
      row(CornerStyle, InputType.currentValueWithSelector()).copy(options = graphCornerStyleOptions),
      row(FillColor, InputType.currentValueWithSelector())
        .copy(
          options = /*lightRows4 ++ */colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.currentValueWithSelector()).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(PenColor, InputType.currentValueWithSelector()).copy(options = /*mediumRows4 ++ */colorOptions),
      // ---------- label stuff ------------
//      labelRow,
      row(ClusterLabelLoc, InputType.currentValueWithSelector()).copy(
        options = clusterVerticalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(LabelJust, InputType.currentValueWithSelector()).copy(
        options = horizontalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.currentValueWithSelector()).copy(
        options = /*mediumRows4 ++ */colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    ),
    extra = rows(
      InvisibleStyle -> checkbox
    )
  )
