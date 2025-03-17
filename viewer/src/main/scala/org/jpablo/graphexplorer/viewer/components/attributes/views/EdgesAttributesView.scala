package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{ArrowPreview, EdgeStylePreview}
import org.jpablo.graphexplorer.viewer.components.attributes.views.AttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def EdgesAttributesView(
    state:     ViewerState,
    updates:   Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(updates, defaults)

  val labelRow =
    if selection then
      state.selection.signal.map(sel =>
        if sel.size == 1 then
          builder.simpleRow(Label, InputType.multiText, onReset = Some(""), placeholder = Some(sel.head.value))
        else
          ""
      ).observe(using state.owner).now()
    else
      ""

  val edgeStyleRow: AttributeRow =
    builder
      .simpleRow(EdgeStyle, InputType.selectWithPreview)
      .copy(
        options =
          EdgeStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, Single(AttrValue(style.toString)), EdgeStylePreview(style))
      )

  val arrowHeadRow: AttributeRow =
    builder
      .simpleRow(ArrowHead, InputType.selectWithPreviewGrid)
      .copy(
        options =
          ArrowType.values.toSeq.map: arrowType =>
            RowOption(arrowType.toString, Single(AttrValue(arrowType.toString)), ArrowPreview(arrowType, 50))
      )

  val arrowTailRow: AttributeRow =
    builder
      .simpleRow(ArrowTail, InputType.selectWithPreviewGrid)
      .copy(
        options =
          ArrowType.values.toSeq.map: arrowType =>
            RowOption(arrowType.toString, Single(AttrValue(arrowType.toString)), ArrowPreview(arrowType, 50))
      )

  AttributesView(
    id       = "edge-attributes",
    titleStr = "Edge Attributes",
    state.layout,
    builder.buildRows(
      "Label",
      labelRow,
      if selection then XLabel else "",
      Decorate -> checkbox,
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize -> number(start = Some(1), end = Some(100), step = Some(1)),
      "Style",
      edgeStyleRow,
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      Color    -> color,
      arrowHeadRow,
      arrowTailRow,
      ArrowSize -> number(start = Some(0), end = Some(5), step = Some(0.1)),
      "Layout",
      Constraint -> checkbox
    ),
    if selection then builder.buildRows("Other", URL) else Seq.empty
  )
