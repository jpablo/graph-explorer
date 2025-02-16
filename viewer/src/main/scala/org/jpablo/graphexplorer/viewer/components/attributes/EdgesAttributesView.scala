package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

def EdgesAttributesView(
    state:     ViewerState,
    attrs:     Var[Attributes],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrs)
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
      FontSize -> number(),
      "Style",
      edgeStyleRow,
      Color    -> color,
      PenWidth -> number(),
      Dir,
      ArrowHead,
      ArrowTail,
      "Layout",
      Constraint -> checkbox,
      // FillColor -> color, // Not supported for now
      // Ordering,
      "Other",
      if selection then URL else ""
    )
  )
