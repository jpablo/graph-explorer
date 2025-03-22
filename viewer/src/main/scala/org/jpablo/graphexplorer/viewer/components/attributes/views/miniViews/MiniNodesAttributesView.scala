package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.*
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{BorderStylePreview, ShapePreview}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.{AttributesView, colorRowOptions}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, range}

def MiniNodesAttributesView(
    parent:    String,
    state:     ViewerState,
    updates:   Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  given owner: Owner = state.owner
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.layout, defaults)
  import builder.{row, rows}

  val labelRow = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  val shapesRowOpts = Shape.valuesWithLabel
    .filterNot((_, s) => s in Shape.synonyms).toSeq
    .map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), ShapePreview(style, 30))

  val fillStyleRow = row(FillStyle, checkbox)

  val borderStyleOptions = BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
    RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style))

  AttributesView(
    id = "node-attributes",
    rows(
      row(Shape, InputType.selectWithPreviewGrid).copy(options = shapesRowOpts),
      row(Color, InputType.selectWithPreviewGrid).copy(options = colorRowOptions),
      row(FillColor, InputType.selectWithPreviewGrid)
        .copy(
          options = colorRowOptions,
          hidden  = builder.invalidLayout(FillColor) || fillStyleRow.combineDefaultBoolean.not
        ),
      fillStyleRow,
      row(BorderStyle, InputType.selectWithPreview).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      CornerStyle,
      InvisibleStyle -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      row(NodeLabelLoc, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontColor, InputType.selectWithPreviewGrid).copy(
        options = colorRowOptions,
        hidden  = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),

      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    )
  )
