package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputElement, RowOption}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, AttributeUpdates}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.number

private val cellAlignIcons = Map(
  HtmlCellAlign.left   -> "bi-align-start",
  HtmlCellAlign.center -> "bi-align-center",
  HtmlCellAlign.right  -> "bi-align-end",
  HtmlCellAlign.text   -> "bi-justify"
)

private val cellVAlignIcons = Map(
  HtmlCellVAlign.top    -> "bi-align-top",
  HtmlCellVAlign.middle -> "bi-align-middle",
  HtmlCellVAlign.bottom -> "bi-align-bottom"
)

private val cellAlignOptions =
  CellAlign.valuesWithLabel.toSeq.map: (label, v) =>
    RowOption(label, AttrStatus.Single(AttrValue(v.toString)), Some(() => i(cls := s"bi ${cellAlignIcons(v)}")))

private val cellVAlignOptions =
  CellVAlign.valuesWithLabel.toSeq.map: (label, v) =>
    RowOption(label, AttrStatus.Single(AttrValue(v.toString)), Some(() => i(cls := s"bi ${cellVAlignIcons(v)}")))

/** Attribute controls for the selected HTML `<td>`.
  *
  * These attributes live inside the label markup, but `updates` presents them
  * as the same `AttributeUpdates` map element attributes use, so the rows here
  * are ordinary rows — colour pickers, dropdowns, number inputs and their reset
  * dots all work unchanged. This is what html tables buy over records: styling
  * per CELL rather than per node.
  */
def ToolbarHtmlCellAttributesView(state: ViewerState, updates: Var[AttributeUpdates]) =
  val builder = RowBuilder(updates, state.graphLayout)
  import builder.{row, rows}

  HorizontalAttributesView(
    rows = rows(
      row(CellBgColor, InputType.currentValueWithSelector()).copy(
        options = lightRows11 ++ colorOptions,
        missingRowOption = Some(missingColorHandler),
        triggerGlyph = Some(() => i(cls := "bi bi-paint-bucket"))
      ),
      row(CellColor, InputType.currentValueWithSelector()).copy(
        options = mediumRows11 ++ colorOptions,
        missingRowOption = Some(missingColorHandler),
        triggerGlyph = Some(() => i(cls := "bi bi-square"))
      ),
      row(CellAlign, InputType.dropdown).copy(options = cellAlignOptions),
      row(CellVAlign, InputType.dropdown).copy(options = cellVAlignOptions),
      // The rest are typed rather than picked, and rarely — they live behind the
      // overflow glyph, like the node bar's advanced attributes.
      InputElement(
        VerticalCardWithButton(
          id = "html-cell-extra-attributes",
          i(cls := "bi-three-dots", title := "More cell attributes"),
          rows(
            row(CellColSpan, number(start = Some(1), end = Some(20), step = Some(1))),
            row(CellRowSpan, number(start = Some(1), end = Some(20), step = Some(1))),
            row(CellBorder, number(start = Some(0), end = Some(10), step = Some(1))),
            row(CellPadding, number(start = Some(0), end = Some(20), step = Some(1))),
            // Renaming a port follows the edges that reference it (see
            // ViewerGraph.renamePort) — they would otherwise fall back to the
            // whole node when the cell they name disappears.
            row(CellPort, InputType.text)
          )
        )
      )
    )
  )
