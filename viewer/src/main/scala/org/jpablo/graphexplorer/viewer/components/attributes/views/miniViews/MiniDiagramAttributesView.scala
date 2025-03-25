package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.{AttributesView, buildDirectedVar, colorOptions}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, *}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, multiText, range}

/** Attributes for the root graph.
  *
  * The root graph is itself a group (cluster) but it has some specific attributes.
  */
def MiniDiagramAttributesView(state: ViewerState) =
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

  val bgColorColorRow = row(BgColor, InputType.selectWithPreviewGrid).copy(options = colorOptions)

  AttributesView(
    id = "mini-root-graph-attributes",
    rows = rows(
      row(
        Label,
        multiText,
        onReset = Some(""),
        label = Some("Title"),
        placeholder = Some("Enter diagram title")
      ),
      RootGraphLabelLoc,
      LabelJust,
      Layout,
      Rankdir,
      graphTypeRow,
      Splines,
      Concentrate -> checkbox,
      bgColorColorRow,
      Pad     -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
      RankSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      NodeSep -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05))
    )
  )
