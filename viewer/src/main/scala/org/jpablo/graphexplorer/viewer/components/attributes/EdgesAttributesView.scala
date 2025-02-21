package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

def EdgesAttributesView(
    state:     ViewerState,
    attrs:     Var[Attributes],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrs, defaults)
  val isSingleEdgeSelected = state.diagramSelection.signal.map(_.size == 1)

  val labelRow =
    if selection then
      isSingleEdgeSelected.map(single =>
        if single then
          builder.simpleRow(Label, InputType.multiText, onReset = Some(""))
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
            RowOption(label, AttrValue(style.toString), EdgeStylePreview(style))
      )

  val arrowHeadRow: AttributeRow =
    builder
      .simpleRow(ArrowHead, InputType.selectWithPreviewGrid)
      .copy(
        options =
          ArrowType.values.filterNot(_ in ArrowType.synonyms).toSeq.map: arrowType =>
            RowOption(arrowType.toString, AttrValue(arrowType.toString), ArrowPreview(arrowType, 50))
      )

  val arrowTailRow: AttributeRow =
    builder
      .simpleRow(ArrowTail, InputType.selectWithPreviewGrid)
      .copy(
        options =
          ArrowType.values.filterNot(_ in ArrowType.synonyms).toSeq.map: arrowType =>
            RowOption(arrowType.toString, AttrValue(arrowType.toString), ArrowPreview(arrowType, 50))
      )

  AttributesView(
    id       = "edge-attributes",
    titleStr = "Edge Attributes",
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
      "Layout",
      Constraint -> checkbox
    ),
    if selection then builder.buildRows("Other", URL) else Seq.empty
  )
