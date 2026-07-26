package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.SectionHeader
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, multiText, range}

/** Attributes for the root graph.
  *
  * The root graph is itself a group (cluster) but it has some specific attributes.
  */
def DiagramAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.diagramAttributesUpdates, state.graphLayout)
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
    row(Label, multiText(), onReset = Some(""), label = Some("Title"), placeholder = Some("Enter diagram title"))

  val labelRelatedHidden = labelRow.combineDefaultString.map(_.isEmpty)
  div(
    idAttr := "diagram-attributes-view",
    div(cls := "attributes-title flex-none", h2("Diagram")),
    // Grouped by what you are trying to change, not by the order Graphviz lists its
    // attributes in. The sections are the panel's structure: keep new attributes inside
    // the group they belong to rather than appending them to the end.
    VerticalAttributesView(
      id = "root-graph-attributes",
      rows = rows(
        SectionHeader("Title"),
        labelRow,
        row(RootGraphLabelLoc, InputType.menuWithExtra(4)).copy(
          options = clusterVerticalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        row(LabelJust, InputType.menuWithExtra(4)).copy(
          options = horizontalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        SectionHeader("Layout"),
        Layout,
        row(Rankdir, InputType.menuWithExtra(4)).copy(options = directionOptions),
        RankSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
        NodeSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
        Pad     -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
        SectionHeader("Edges"),
        graphTypeRow,
        Splines,
        Concentrate -> checkbox,
        SectionHeader("Canvas"),
        row(BgColor, InputType.menuWithExtra(lightRows8.length, MenuDirection.end))
          .copy(
            options = lightRows8 ++ colorOptions,
            hidden = builder.invalidLayout(BgColor)
          )
      )
    ).amend(cls := "flex-grow")
  )
