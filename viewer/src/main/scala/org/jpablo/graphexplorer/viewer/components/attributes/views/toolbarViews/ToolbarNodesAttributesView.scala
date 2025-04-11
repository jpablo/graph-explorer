package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{AttrValueWithStatus, AttributeUpdates, Attributes}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, number, range}

def ToolbarNodesAttributesView(
    parent:   String,
    state:    ViewerState,
    updates:  Var[AttributeUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.graphLayout, defaults)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  val shapeRow = row(Shape, InputType.currentValueWithSelector(cardClass = Some("narrow-card"))).copy(options = shapesOptions)
  val sidesRow =
    row(
      attr = Sides,
      inputType = number(start = Some(3), end = Some(10), step = Some(1)),
      hidden = Some(
        builder.invalidLayout(Sides) || shapeRow.inputVar.signal.map(_.exists(_.toString != Shape.polygon.toString))
      )
    )

  HorizontalAttributesView(
    rows = rows(
      shapeRow,
      row(CornerStyle, InputType.dropdown).copy(options = cornerStyleOptions),
      row(FillColor, InputType.currentValueWithSelector())
        .copy(
          options = /*lightRows4 ++ */ colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.dropdown).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.1), end = Some(4), step = Some(0.25)),
      row(Color, InputType.currentValueWithSelector()).copy(options = /*mediumRows4 ++ */ colorOptions),
//      labelRow,
      row(NodeLabelLoc, InputType.dropdown).copy(
        options = nodeLabelVerticalAlignOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.currentValueWithSelector()).copy(
        options = /*mediumRows4 ++ */ colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    ),
    extra = rows(
      InvisibleStyle -> checkbox,
      sidesRow,
      Regular     -> checkbox,
      Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
      Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
      URL
    )
  )
