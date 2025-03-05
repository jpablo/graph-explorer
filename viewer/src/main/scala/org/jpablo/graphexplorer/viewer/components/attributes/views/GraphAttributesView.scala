package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.previews.BorderStylePreview
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.style.{CommonSubAttributes, StyleSubAttributes}
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.*
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.CornerStyle.diagonals
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{color, number, range}


def GraphAttributesView(
    state:     ViewerState,
    attrsVar:  Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrsVar, defaults)
  val isSingleClusterSelected = state.diagramSelection.signal.map(_.size == 1)

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

  val defaultSubAttrs: Signal[StyleSubAttributes] =
    defaults
      .map(_.map(attrs => StyleSubAttributes.from(attrs).getOrElse(StyleSubAttributes.missing)))
      .getOrElse(Signal.fromValue(StyleSubAttributes.missing))


  val commonSubAttrs = CommonSubAttributes(attrsVar, defaultSubAttrs)
  import commonSubAttrs.*

  val borderStyleRow =
    builder
      .inputRow(BorderStyle -> InputType.selectWithPreview, borderStyle.getVar, borderStyle.getDefault)
      .copy(
        options =
          BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style))
      )

  val shapeModeStyleRow =
    builder
      .inputRow(CornerStyle -> InputType.select, cornerStyle.getVar, cornerStyle.getDefault)
      .copy(
        options =
          CornerStyle.valuesWithLabel.filterNot(_._2 == diagonals).toSeq.map: (label, style) =>
            RowOption(label, Single(AttrValue(style.toString)), None)
      )

  AttributesView(
    id       = "graph-attributes",
    titleStr = "Cluster Attributes",
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
      builder.inputRow(FillStyle -> InputType.checkbox, fillStyle.getVar, fillStyle.getDefault),
      FillColor -> color,
      borderStyleRow,
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      PenColor -> color,
      builder.inputRow(BoldStyle -> InputType.checkbox, boldStyle.getVar, boldStyle.getDefault),
      shapeModeStyleRow
    ),
    if selection then
      builder.buildRows(
        builder.inputRow(InvisibleStyle -> InputType.checkbox, invisibleStyle.getVar, invisibleStyle.getDefault),
        "Other",
        URL
      )
    else
      Seq.empty
  )
