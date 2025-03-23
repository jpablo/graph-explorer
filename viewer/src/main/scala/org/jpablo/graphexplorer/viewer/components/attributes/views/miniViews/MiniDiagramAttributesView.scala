package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.AttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.ColorType
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BgColor, Concentrate, GraphType, Label, LabelJust, Layout, NodeSep, Pad, RankSep, Rankdir, RootGraphLabelLoc, Splines}
import org.jpablo.graphexplorer.viewer.models.AttrStatus
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, multiText, range}

/** Attributes for the root graph.
  *
  * The root graph is itself a group (cluster) but it has some specific attributes.
  */
def MiniDiagramAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.rootTargetAttributesUpdates(AttributeTarget.graph), state.layout, None)

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
      attr     = GraphType -> checkbox,
      inputVar = directedVar,
      default  = Signal.fromValue(true.toString),
      label    = Some("Directed")
    )

  val fillColorRowOpts =
    ColorType.x11BasicColors.toSeq
      .sortBy(_._2)(Ordering.String.reverse)
      .map: (name, hex) =>
        RowOption(
          name,
          Single(AttrValue(hex)),
          Some(() => div(cls := s"w-8 h-4 rounded border-1 border-solid", styleAttr := s"background-color: $hex"))
        )
  val bgColorColorRow = builder.row(BgColor, InputType.selectWithPreviewGrid).copy(options = fillColorRowOpts)

  AttributesView(
    id       = "mini-root-graph-attributes",
    builder.rows(
      builder.row(
        Label,
        multiText,
        onReset     = Some(""),
        label       = Some("Title"),
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
