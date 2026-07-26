package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.Command
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttributeUpdates
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Icons.bigXIcon
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.*

def ToolbarArrowsAttributesView(
    state:           ViewerState,
    resetAttributes: Command[Nothing],
    updates:         Var[AttributeUpdates]
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.graphLayout)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText(), onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  // Same clustering as the node bar: paint, then the ends, then text.
  HorizontalAttributesView(
    rows = rows(
      SectionHeader("Paint"),
      row(Color, InputType.currentValueWithSelector())
        .copy(
          options = mediumRows11 ++ colorOptions,
          missingRowOption = Some(missingColorHandler)
        ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "arrow-border-attributes",
          row(EdgeStyle, InputType.menuWithExtra(arrowStyleOptions.length)).copy(options = arrowStyleOptions),
          row(PenWidth, range(start = Some(0.0), end = Some(10.0), step = Some(0.1)))
        )
      ),
      SectionHeader("Ends"),
      row(ArrowTail, InputType.currentValueWithSelector(cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions(angle = 180)),
      row(ArrowHead, InputType.currentValueWithSelector(cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions(angle = 0)),
      ArrowSize  -> InputType.number(start = Some(0), end = Some(5), step = Some(0.1)),
      Constraint -> checkbox,
      // ---------- label stuff ------------
      SectionHeader("Text"),
      labelRow,
      row(FontColor, InputType.currentValueWithSelector(MenuDirection.end)).copy(
        options = mediumRows11 ++ colorOptions,
        hidden = labelRelatedHidden,
        missingRowOption = Some(missingColorHandler)
      ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "arrow-font-attributes",
          // A menu rather than a native <select>; see ToolbarNodesAttributesView.
          row(FontName, InputType.dropdown).copy(hidden = labelRelatedHidden),
          row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
        )
      ),
      // --- Advanced or extra attributes ---
      InputElement(
        VerticalCardWithButton(
          id = "extra-arrow-attributes",
          i(cls := "bi-three-dots", title := "More attributes"),
          rows(
            Decorate -> checkbox,
            row(XLabel, InputType.text),
            row(URL, InputType.text),
            // Menus, not native selects: these live in a card, and a select's picker takes
            // focus out of it. See ToolbarNodesAttributesView.
            TailPort -> InputType.dropdown,
            HeadPort -> InputType.dropdown
          )
        )
      ),
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
