package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.InputElement
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.{AttributeUpdates, Attributes}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def ToolbarArrowsAttributesView(
    state:    ViewerState,
    updates:  Var[AttributeUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText(), onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  HorizontalAttributesView(
    rows = rows(
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
      row(ArrowHead, InputType.currentValueWithSelector(cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions),
      row(ArrowTail, InputType.currentValueWithSelector(cardClass = Some("narrow-card"))).copy(options = arrowTypeOptions),
      ArrowSize  -> InputType.number(start = Some(0), end = Some(5), step = Some(0.1)),
      Constraint -> checkbox,
      // ---------- label stuff ------------
      row(FontColor, InputType.currentValueWithSelector(MenuDirection.end)).copy(
        options = mediumRows11 ++ colorOptions,
        hidden = labelRelatedHidden,
        missingRowOption = Some(missingColorHandler)
      ),
      InputElement(
        VerticalCardWithPreview(
          builder,
          id = "arrow-font-attributes",
          row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
          row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
        )
      ),
      // --- Advanced or extra attributes ---
      InputElement(
        VerticalCardWithButton(
          id = "extra-arrow-attributes",
          "extra",
          rows(
            Decorate -> checkbox,
            row(XLabel, InputType.text, hidden = Some(Signal.fromValue(defaults.isEmpty))),
            row(URL, InputType.text, hidden = Some(Signal.fromValue(defaults.isEmpty))),
            TailPort -> InputType.select,
            HeadPort -> InputType.select,
          )
        )
      )
    )
  )
