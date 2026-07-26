package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.Command
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.AttributeUpdates
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Icons.bigXIcon
import org.jpablo.graphexplorer.viewer.widgets.InputType.checkbox
import org.jpablo.graphexplorer.viewer.widgets.*

def ToolbarGroupAttributesView(
    state:           ViewerState,
    resetAttributes: Command[Nothing],
    updates:         Var[AttributeUpdates]
) =
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(updates, state.graphLayout)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText(), onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  // Same clustering as the node and arrow bars: shape, paint, text, then the group's
  // own structural switches.
  HorizontalAttributesView(
    rows = rows(
      SectionHeader("Shape"),
      row(CornerStyle, InputType.dropdown).copy(options = graphCornerStyleOptions),
      SectionHeader("Paint"),
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
          row(BorderStyle, InputType.menuWithExtra(borderStyleOptions.length)).copy(options = borderStyleOptions),
          row(PenWidth, InputType.range(start = Some(0.0), end = Some(10.0), step = Some(0.1)))
        )
      ),
      // ---------- label stuff ------------
      SectionHeader("Text"),
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
          // A menu rather than a native <select>; see ToolbarNodesAttributesView.
          row(FontName, InputType.dropdown).copy(hidden = labelRelatedHidden),
          row(FontSize, InputType.range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
        ),
        hidden = labelRelatedHidden
      ),
      SectionHeader("Group"),
      InvisibleStyle -> checkbox,
      row(Cluster, checkbox),
      row(Rank, InputType.select),
      SectionHeader("Reset"),
      InputElement(
        Button(
          title := "Reset all attributes",
          span().bigXIcon,
          onClick --> resetAttributes.execute()
        ).tiny.ghost
      )
    )
  )
