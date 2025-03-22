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
  val builder = RowBuilder(updates, state.layout, defaults)

  given owner: Owner = state.owner

  val multiSelection = state.selection.signal.map(_.size != 1)

  val labelRow = builder
    .simpleRow(Label, InputType.multiText, onReset = Some(""))
    .copy(hidden = multiSelection)

  val labelEmpty = labelRow.combineDefaultString.map(_.isEmpty)
  val labelRelatedHidden = labelEmpty && multiSelection.not

  val borderStyleRow =
    builder
      .simpleRow(BorderStyle, InputType.selectWithPreview)
      .copy(
        options =
          BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style))
      )

  val shapesRowOpts =
    Shape.valuesWithLabel
      .filterNot((_, s) => s in Shape.synonyms).toSeq
      .map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), ShapePreview(style, 30))

  val shapeRow: AttributeRow.InputAttribute =
    builder
      .simpleRow(Shape, InputType.selectWithPreviewGrid)
      .copy(options = shapesRowOpts)

  val fillStyleRow = builder.simpleRow(FillStyle, checkbox)
  val fillColorRow = builder.simpleRow(FillColor, InputType.selectWithPreviewGrid)
    .copy(
      options = colorRowOptions,
      hidden  = builder.invalidLayout(FillColor) || fillStyleRow.combineDefaultBoolean.not
    )

  AttributesView(
    id = "node-attributes",
    builder.buildRows(
      shapeRow,
      builder.simpleRow(Color, InputType.selectWithPreviewGrid).copy(options = colorRowOptions),
      fillColorRow,
      fillStyleRow,
      borderStyleRow,
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      CornerStyle,
      InvisibleStyle -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      builder.simpleRow(NodeLabelLoc, InputType.select).copy(hidden = labelRelatedHidden),
      builder.simpleRow(FontColor, InputType.selectWithPreviewGrid).copy(
        options = colorRowOptions,
        hidden  = labelRelatedHidden
      ),
      builder.simpleRow(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      builder.simpleRow(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden =
        labelRelatedHidden
      )
    )
  )
