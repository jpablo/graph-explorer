package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
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

  val directedVar = buildDirectedVar(state.graphType)

  val graphTypeRow =
    RowBuilder.inputRow(
      attr = GraphType -> checkbox,
      inputVar = directedVar,
      default = Signal.fromValue(true.toString),
      label = Some("Directed")
    )

  val labelRow =
    row(Label, multiText, onReset = Some(""), label = Some("Title"), placeholder = Some("Enter diagram title"))

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty)
  div(
    idAttr := "diagram-attributes-view",
    div(cls := "attributes-title", h2("Diagram")),
    AttributesView(
      id = "root-graph-attributes",
      showHeaders = false,
      rows = rows(
        "Title",
        labelRow,
        row(RootGraphLabelLoc, InputType.menuWithExtra(4)).copy(
          options = clusterVerticalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        row(LabelJust, InputType.menuWithExtra(4)).copy(
          options = horizontalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        "Layout",
        Layout,
        row(Rankdir, InputType.menuWithExtra(4)).copy(options = directionOptions),
        graphTypeRow,
        "Other",
        Splines,
        Concentrate -> checkbox,
        row(BgColor, InputType.menuWithExtra(4))
          .copy(
            options = colorOptions,
            hidden = builder.invalidLayout(BgColor)
          ),
        Pad     -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
        RankSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
        NodeSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05))
      )
    ).amend(cls := "mb-8")
  )
