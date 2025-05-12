package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.Command
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.InputElement
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{AttributeUpdates, Attributes}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.checkbox
import org.jpablo.graphexplorer.viewer.widgets.{Button, ghost, tiny}
import org.jpablo.graphexplorer.viewer.widgets.Icons.bigXIcon
import com.raquo.laminar.api.features.unitArrows

def ToolbarGroupAttributesView(
    state:           ViewerState,
    resetAttributes: Command[Nothing],
    updates:         Var[AttributeUpdates],
    defaults:        Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(updates, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText(), onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not
  val noDefaults         = Signal.fromValue(defaults.isEmpty)

  HorizontalAttributesView(
    rows = rows(
      InputElement(
        Button(
          title := "Reset attributes",
          span().bigXIcon,
          onClick --> resetAttributes.execute()
        ).tiny.ghost
      ),
      row(CornerStyle, InputType.dropdown).copy(options = graphCornerStyleOptions),
      row(FillColor, InputType.currentValueWithSelector())
        .copy(
          options = lightRows11 ++ colorOptions,
          hidden = builder.invalidLayout(FillColor),
          missingRowOption = Some(missingColorHandler)
        ),
      row(PenColor, InputType.currentValueWithSelector())
        .copy(
          options = mediumRows11 ++ colorOptions,
          missingRowOption = Some(missingColorHandler)
        ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "group-border-attributes",
          row(EdgeStyle, InputType.menuWithExtra(borderStyleOptions.length)).copy(options = borderStyleOptions),
          row(PenWidth, InputType.range(start = Some(0.0), end = Some(10.0), step = Some(0.1)))
        )
      ),
      // ---------- label stuff ------------
      row(ClusterLabelLoc, InputType.dropdown).copy(
        options = clusterVerticalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(LabelJust, InputType.dropdown).copy(
        options = horizontalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.currentValueWithSelector(MenuDirection.end)).copy(
        options = mediumRows11 ++ colorOptions,
        hidden = labelRelatedHidden,
        missingRowOption = Some(missingColorHandler)
      ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "group-font-attributes",
          row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
          row(FontSize, InputType.range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
        ),
        hidden = labelRelatedHidden
      ),
      InvisibleStyle -> checkbox,
      row(Cluster, checkbox, hidden = Some(noDefaults)),
      row(Rank, InputType.select, hidden = Some(noDefaults))
    )
  )
