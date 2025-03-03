package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.style.CommonSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.*
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number, range}

def NodesAttributesView(
    parent:    String,
    state:     ViewerState,
    attrsVar:  Var[Attributes],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrsVar, defaults)

  given owner: Owner = state.owner

  val labelRow =
    if selection then
      state.diagramSelection.signal.map(sel =>
        if sel.size == 1 then
          builder.simpleRow(Label, InputType.multiText, onReset = Some(""), placeholder = Some(sel.head.value))
        else
          ""
      ).observe().now()
    else
      ""

  val defaultSubAttrs: Signal[StyleSubAttributes] =
    defaults
      .map(_.map(attrs => StyleSubAttributes.from(attrs).getOrElse(StyleSubAttributes.empty)))
      .getOrElse(Signal.fromValue(StyleSubAttributes.empty))

  val commonSubAttrs = CommonSubAttributes(attrsVar, defaultSubAttrs)
  import commonSubAttrs.*

  val borderStyleRow =
    builder
      .inputRow(BorderStyle -> InputType.selectWithPreview, borderStyle.getVar, borderStyle.getDefault)
      .copy(
        options =
          BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, AttrValue(style.toString), BorderStylePreview(style))
      )

  val shapeModeStyleRow =
    builder
      .inputRow(CornerStyle -> InputType.select, shapeModeStyle.getVar, shapeModeStyle.getDefault)

  val shapeRow: AttributeRow =
    builder
      .simpleRow(Shape, InputType.selectWithPreviewGrid)
      .copy(
        options =
          Shape.valuesWithLabel.filterNot((l, s) => s in Shape.synonyms).toSeq.map: (label, style) =>
            RowOption(label, AttrValue(style.toString), ShapePreview(style, 30))
      )

  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
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
      Sides       -> number(start = Some(3), end = Some(10), step = Some(1)),
      Regular     -> checkbox,
      Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
      Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
      "Style",
      builder.inputRow(FillStyle -> InputType.checkbox, fillStyle.getVar, fillStyle.getDefault),
      FillColor -> color,
      borderStyleRow,
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      Color    -> color,
      builder.inputRow(BoldStyle -> InputType.checkbox, boldStyle.getVar, boldStyle.getDefault),
      shapeModeStyleRow
    ),
    if selection then
      builder.buildRows(
        builder.inputRow(InvisibleStyle -> InputType.checkbox, invisibleStyle.getVar, invisibleStyle.getDefault),
        "Other",
        URL
      )
    else Seq.empty
  )
