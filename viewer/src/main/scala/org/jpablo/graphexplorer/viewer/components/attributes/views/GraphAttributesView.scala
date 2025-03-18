package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.previews.BorderStylePreview
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BoldStyle, BorderStyle, ClusterLabelLoc, CornerStyle, FillColor, FillStyle, FontColor, FontName, FontSize, InvisibleStyle, Label, LabelJust, PenColor, PenWidth, URL}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number, range}

def GraphAttributesView(
    state:     ViewerState,
    attrsVar:  Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrsVar, state.layout, defaults)
  val isSingleClusterSelected = state.selection.signal.map(_.size == 1)

  given owner: Owner = state.owner

  val labelRow =
    if selection then
      isSingleClusterSelected.map(single =>
        if single then
          builder.simpleRow(Label, InputType.multiText, onReset = Some(""), placeholder = Some("Enter group label"))
        else
          ""
      ).observe(using state.owner).now()
    else
      builder.simpleRow(Label, InputType.multiText, onReset = Some(""), placeholder = Some("Enter group label"))

  val borderStyleRow =
    builder
      .simpleRow(BorderStyle, InputType.selectWithPreview)
      .copy(
        options =
          BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style))
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
    id       = "graph-attributes",
    titleStr = "Cluster Attributes",
    state.layout,
    builder.buildRows(
      if selection then "Labels" else "",
      if selection then labelRow else "",
      if selection then ClusterLabelLoc else "",
      if selection then LabelJust else "",
      "Fonts",
      FontName,
      FontColor -> color,
      FontSize  -> number(start = Some(1), end = Some(100), step = Some(1)),
      "Style",
      fillStyleRow,
      fillColorRow,
      borderStyleRow,
      PenWidth  -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      PenColor  -> color,
      BoldStyle -> checkbox,
      CornerStyle
    ),
    if selection then
      builder.buildRows(
        InvisibleStyle -> checkbox,
        "Other",
        URL
      )
    else
      Seq.empty
  )
