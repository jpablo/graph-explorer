package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{color, range}

/** Attributes for the root graph.
 *
 * The root graph is itself a group (cluster) but it has some specific attributes.
  */
def RootGraphAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.rootTargetAttributesUpdates(AttributeTarget.graph), None)
  AttributesView(
    id       = "root-graph-attributes",
    titleStr = "Root Graph Options",
    builder.buildRows(
      "Title",
      builder.simpleRow(
        Label,
        InputType.multiText,
        onReset     = Some(""),
        label       = Some("Title"),
        placeholder = Some("Enter diagram title")
      ),
      RootGraphLabelLoc,
      LabelJust,
      "Layout",
      Layout,
      Rankdir,
      "Other",
      Splines,
      Concentrate -> InputType.checkbox,
      BgColor     -> color,
      Pad         -> range(start = Some(0.0), end = Some(1.0), step = Some(0.05)),
      RankSep     -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05)),
      NodeSep     -> range(start = Some(0.02), end = Some(2.0), step = Some(0.05))
    )
  ).amend(cls := "mb-8")
