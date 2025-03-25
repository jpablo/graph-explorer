package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, range}

def MiniNodesAttributesView(
    parent:   String,
    state:    ViewerState,
    updates:  Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.layout, defaults)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  AttributesView(
    id = "mini-node-attributes",
    rows(
      row(Shape, InputType.menuWithExtra(4)).copy(options = shapesOptions),
      row(Color, InputType.menuWithExtra(4)).copy(options = colorOptions),
      row(FillColor, InputType.menuWithExtra(4))
        .copy(
          options = colorOptions,
          hidden = builder.invalidLayout(FillColor) // || fillStyleRow.combineDefaultBoolean.not
        ),
      row(BorderStyle, InputType.menuWithExtra(4)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.1), end = Some(4), step = Some(0.25)),
      row(CornerStyle, InputType.menuWithExtra(4)).copy(options = cornerStyleOptions),
      InvisibleStyle -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      row(NodeLabelLoc, InputType.menuWithExtra(4)).copy(
        options = nodeLabelVerticalAlignOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.menuWithExtra(4)).copy(
        options = colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    )
  )
