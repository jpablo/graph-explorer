package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.*
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{BorderStylePreview, ShapePreview}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BoldStyle, BorderStyle, Color, CornerStyle, FillColor, FillStyle, FontColor, FontName, FontSize, InvisibleStyle, Label, NodeLabelLoc, Orientation, PenWidth, Peripheries, Regular, Shape, Sides, URL, XLabel}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number, range}

def NodesAttributesView(
    parent:    String,
    state:     ViewerState,
    updates:   Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(updates, state.layout, defaults)

  given owner: Owner = state.owner

  val labelRow =
    if selection then
      state.selection.signal.map(sel =>
        if sel.size == 1 then
          builder.simpleRow(Label, InputType.multiText, onReset = Some(""), placeholder = Some(sel.head.value))
        else
          ""
      ).observe().now()
    else
      ""

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

  val sidesRow =
    builder.simpleRow(
      attr      = Sides,
      inputType = number(start = Some(3), end = Some(10), step = Some(1)),
      hidden = Some(
        Signal.combine(
          builder.invalidLayout(Sides),
          shapeRow.inputVar.signal.map(_.exists(_.toString != Shape.polygon.toString))
        ).map(_ || _)
      )
    )

  val fillStyleRow =
    builder.simpleRow(FillStyle, checkbox)

  val fillColorRow =
    builder.simpleRow(
      attr = FillColor,
      inputType = color,
      hidden = Some(
        Signal.combine(
          builder.invalidLayout(FillColor),
          fillStyleRow.inputVar.signal.map(_.forall(_.toString == false.toString))
        ).map(_ || _)
      )
    )


  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
    state.layout,
    builder.buildRows(
      "Label",
      labelRow,
      NodeLabelLoc,
      if selection then XLabel else "",
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize -> number(start = Some(1), end = Some(100), step = Some(1)),
      "Shape",
      shapeRow,
      sidesRow,
      Regular     -> checkbox,
      Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
      Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
      "Style",
      fillStyleRow,
      fillColorRow,
      borderStyleRow,
      PenWidth  -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      Color     -> color,
      BoldStyle -> checkbox,
      CornerStyle
    ),
    if selection then
      builder.buildRows(
        InvisibleStyle -> checkbox,
        "Other",
        URL
      )
    else Seq.empty
  )
