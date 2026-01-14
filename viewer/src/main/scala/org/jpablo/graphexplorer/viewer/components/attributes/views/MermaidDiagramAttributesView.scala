package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Rankdir}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.multiText

/** Diagram-level attributes for Mermaid flowcharts.
  *
  * Provides UI for editing:
  *   - Title (diagram label)
  *   - Direction (TB, LR, BT, RL)
  */
def MermaidDiagramAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.diagramAttributesUpdates, state.graphLayout)
  import builder.{row, rows}

  val labelRow =
    row(Label, multiText(), onReset = Some(""), label = Some("Title"), placeholder = Some("Enter diagram title"))

  div(
    idAttr := "mermaid-diagram-attributes-view",
    div(cls := "attributes-title flex-none", h2("Mermaid Diagram")),
    VerticalAttributesView(
      id = "mermaid-diagram-attributes",
      rows = rows(
        labelRow,
        row(Rankdir, InputType.menuWithExtra(4)).copy(options = directionOptions)
      )
    ).amend(cls := "flex-grow")
  )
