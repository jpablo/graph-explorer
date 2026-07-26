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
      // Labels are the words a person would use, not Graphviz's. The attribute keeps its
      // identity; only the presentation changes, and every row's tooltip still names the
      // DOT attribute for anyone who came here knowing it.
      rows = rows(
        SectionHeader("Title"),
        labelRow,
        row(RootGraphLabelLoc, InputType.menuWithExtra(4), label = Some("Position")).copy(
          options = clusterVerticalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        row(LabelJust, InputType.menuWithExtra(4), label = Some("Align")).copy(
          options = horizontalAlignmentOptions,
          hidden = labelRelatedHidden
        ),
        SectionHeader("Layout"),
        // "Layout" inside a section called LAYOUT says nothing; this row picks the engine.
        row(Layout, InputType.select, label = Some("Engine")),
        row(Rankdir, InputType.menuWithExtra(4), label = Some("Direction")).copy(options = directionOptions),
        // Graphviz measures all three in inches.
        row(RankSep, range(start = Some(0.02), end = Some(2.0), step = Some(0.05)), label = Some("Rank gap"), unit = Some("in")),
        row(NodeSep, range(start = Some(0.02), end = Some(2.0), step = Some(0.05)), label = Some("Node gap"), unit = Some("in")),
        row(Pad, range(start = Some(0.0), end = Some(1.0), step = Some(0.05)), label = Some("Margin"), unit = Some("in")),
        SectionHeader("Edges"),
        // "Directed" describes the graph; the toggle draws arrowheads. Naming it after the
        // graph theory made you flip it twice to find out what it did.
        graphTypeRow.copy(label = "Arrowheads"),
        row(Splines, InputType.select, label = Some("Routing")),
        row(Concentrate, checkbox, label = Some("Merge parallel")),
        SectionHeader("Canvas"),
        row(BgColor, InputType.menuWithExtra(lightRows8.length, MenuDirection.end), label = Some("Background"))
          .copy(
            options = lightRows8 ++ colorOptions,
            hidden = builder.invalidLayout(BgColor)
          )
      )
    ).amend(cls := "flex-grow")
  )
