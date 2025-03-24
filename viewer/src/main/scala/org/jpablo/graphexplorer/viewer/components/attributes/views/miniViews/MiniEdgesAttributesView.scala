package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{ArrowPreview, EdgeStylePreview}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.views.{AttributesView, colorRowOptions}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.*

def MiniEdgesAttributesView(
    state:     ViewerState,
    updates:   Var[AttributesUpdates],
    defaults:  Option[Signal[Attributes]] = None,
) =
  given owner: Owner = state.owner
  val multiSelection = state.selection.signal.map(_.size != 1)

  val builder = RowBuilder(updates, state.layout, defaults)
  import builder.{row, rows}

  val labelRow           = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  val edgeStyleOptions =
    EdgeStyle.valuesWithLabel.toSeq.map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), EdgeStylePreview(style, 20))

  val arrowHeadOptions =
    ArrowType.values.toSeq.map: arrowType =>
      RowOption(arrowType.toString, Single(AttrValue(arrowType.toString)), ArrowPreview(arrowType, 20))

  val arrowTailOptions =
    ArrowType.values.toSeq.map: arrowType =>
      RowOption(arrowType.toString, Single(AttrValue(arrowType.toString)), ArrowPreview(arrowType, 20))

  AttributesView(
    id = "edge-attributes",
    rows(
      row(Color, InputType.menuWithExtra(4)).copy(options = colorRowOptions),
      row(EdgeStyle, InputType.menuWithExtra(4)).copy(options = edgeStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(ArrowHead, InputType.menuWithExtra(4)).copy(options = arrowHeadOptions),
      row(ArrowTail, InputType.menuWithExtra(4)).copy(options = arrowTailOptions),
      ArrowSize  -> range(start = Some(0), end = Some(5), step = Some(0.1)),
      Constraint -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      row(FontColor, InputType.menuWithExtra(4)).copy(options = colorRowOptions, hidden = labelRelatedHidden),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    )
  )
