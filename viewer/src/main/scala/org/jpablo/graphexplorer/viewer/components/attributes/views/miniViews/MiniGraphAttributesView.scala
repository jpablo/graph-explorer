package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.syntaxSignalOfBoolean
import org.jpablo.graphexplorer.viewer.components.attributes.previews.{BorderStylePreview, CornerPreview}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.{AttributesView, colorOptions}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{Attributes, AttributesUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, range}

def MiniGraphAttributesView(
    state:    ViewerState,
    attrsVar: Var[AttributesUpdates],
    defaults: Option[Signal[Attributes]] = None
) =
  given owner: Owner = state.owner
  val multiSelection = state.selection.signal.map(_.size != 1)
  val builder        = RowBuilder(attrsVar, state.layout, defaults)
  import builder.{row, rows}
  val isSingleClusterSelected = state.selection.signal.map(_.size == 1)

  val labelRow           = row(Label, InputType.multiText, onReset = Some("")).copy(hidden = multiSelection)
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty) && multiSelection.not

  val borderStyleOptions = BorderStyle.valuesWithLabel
    .toSeq.map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), BorderStylePreview(style, 20))

  val validCornerStyle = Set(CornerStyle.normal, CornerStyle.rounded)
  val cornerStyleOptions = CornerStyle.valuesWithLabel.filter((_, s) => validCornerStyle(s))
    .toSeq.map: (label, style) =>
      RowOption(label, Single(AttrValue(style.toString)), CornerPreview(style))

  val vAlignIcons = Map(
    GroupLabelLoc.t -> "bi-align-top",
    GroupLabelLoc.b -> "bi-align-bottom"
  )
  val hAlignIcons = Map(
    LabelJust.l -> "bi-align-start",
    LabelJust.c -> "bi-align-center",
    LabelJust.r -> "bi-align-end"
  )
  val verticalAlignmentOptions =
    ClusterLabelLoc.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${vAlignIcons(style)}")))

  val horizontalAlignmentOptions =
    LabelJust.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${hAlignIcons(style)}")))

  AttributesView(
    id = "mini-graph-attributes",
    rows(
      row(PenColor, InputType.menuWithExtra(4)).copy(options = colorOptions),
      row(FillColor, InputType.menuWithExtra(4))
        .copy(
          options = colorOptions,
          hidden = builder.invalidLayout(FillColor)
        ),
      row(BorderStyle, InputType.menuWithExtra(4)).copy(options = borderStyleOptions),
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      row(CornerStyle, InputType.menuWithExtra(4)).copy(options = cornerStyleOptions),
      InvisibleStyle -> checkbox,
      // ---------- label stuff ------------
      labelRow,
      row(ClusterLabelLoc, InputType.menuWithExtra(4)).copy(
        options = verticalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(LabelJust, InputType.menuWithExtra(4)).copy(
        options = horizontalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(FontColor, InputType.menuWithExtra(4)).copy(
        options = colorOptions,
        hidden = labelRelatedHidden
      ),
      row(FontName, InputType.select).copy(hidden = labelRelatedHidden),
      row(FontSize, range(start = Some(1), end = Some(100), step = Some(1))).copy(hidden = labelRelatedHidden)
    )
  )
