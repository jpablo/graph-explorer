package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{
  BgColor,
  Concentrate,
  GraphType,
  GroupLabelLoc,
  Label,
  LabelJust,
  Layout,
  NodeSep,
  Pad,
  RankSep,
  Rankdir,
  RootGraphLabelLoc,
  Splines
}
import org.jpablo.graphexplorer.viewer.models.AttrStatus
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, multiText, range}

/** Attributes for the root graph.
  *
  * The root graph is itself a group (cluster) but it has some specific attributes.
  */
def DiagramAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.rootTargetAttributesUpdates(AttributeTarget.graph), state.layout, None)
  import builder.{row, rows}

  val directedVar: Var[AttrStatus[AttrValue]] =
    state.graphType.zoomLazy(tpe =>
      AttrStatus.Single(AttrValue((tpe == GraphType.digraph).toString))
    ): (_, status) =>
      status match
        case AttrStatus.Single(value) => if value.isTrue then GraphType.digraph else GraphType.graph
        case AttrStatus.Multiple      => GraphType.default
        case AttrStatus.Missing       => GraphType.default

  val graphTypeRow =
    RowBuilder.inputRow(
      attr = GraphType -> checkbox,
      inputVar = directedVar,
      default = Signal.fromValue(true.toString),
      label = Some("Directed")
    )

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
    RootGraphLabelLoc.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${vAlignIcons(style)}")))

  val horizontalAlignmentOptions =
    LabelJust.valuesWithLabel
      .toSeq.map: (label, style) =>
        RowOption(label, Single(AttrValue(style.toString)), Some(() => i(cls := s"bi ${hAlignIcons(style)}")))

  val labelRow =
    row(Label, multiText, onReset = Some(""), label = Some("Title"), placeholder = Some("Enter diagram title"))
  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty)

  AttributesView(
    id = "root-graph-attributes",
    rows(
      "Title",
      labelRow,
      row(RootGraphLabelLoc, InputType.menuWithExtra(4)).copy(
        options = verticalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      row(LabelJust, InputType.menuWithExtra(4)).copy(
        options = horizontalAlignmentOptions,
        hidden = labelRelatedHidden
      ),
      "Layout",
      Layout,
      Rankdir,
      graphTypeRow,
      "Other",
      Splines,
      Concentrate -> checkbox,
      row(BgColor, InputType.menuWithExtra(4))
        .copy(
          options = colorRowOptions,
          hidden = builder.invalidLayout(BgColor)
        ),

      Pad     -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
      RankSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      NodeSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05))
    )
  ).amend(cls := "mb-8")
