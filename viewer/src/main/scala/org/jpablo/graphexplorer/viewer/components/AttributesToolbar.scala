package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.ToolbarHtmlCellAttributesView
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{
  ToolbarArrowsAttributesView,
  ToolbarGroupAttributesView,
  ToolbarNodesAttributesView
}
import org.jpablo.graphexplorer.viewer.formats.dot.RecordTree
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.selection.{ElementKind, SelectByKind}
import org.jpablo.graphexplorer.viewer.state.{SelectedCell, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*

/** The CONTEXT STRIP: a second row that exists only while it has something to
  * say — attribute controls for the current selection, and a "N hidden" chip
  * while elements are hidden. When neither applies, the row is gone and the
  * canvas starts right under the toolbar (see the top-bar design study: an
  * always-on second bar reads as two half-empty bars).
  */
def AttributesToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  import commands.all

  val hiddenCount  = state.hiddenElements.signal.map(_.size)
  val hasSelection = state.selection.signal.map(_.nonEmpty)
  val stripVisible = hasSelection.combineWithFn(hiddenCount)(_ || _ > 0)

  div(
    idAttr := "selection-toolbar",
    cls    := "navbar",
    cls("hidden") <-- stripVisible.not,
    // What the controls apply to, named: "2 nodes", "3 objects".
    child.maybe <-- state.selection.signal.distinct.map: sel =>
      Option.when(sel.nonEmpty)(span(cls := "gx-selection-count", selectionSummary(sel))),
    // Keyed on the SELECTION alone. Keying it on the graph too rebuilt this whole subtree on
    // every attribute write — and the write comes from a control inside the subtree, so the
    // bar destroyed the control being used. A slider could not be dragged at all: the first
    // mousedown wrote a value, the value rebuilt the bar, and the drag continued against an
    // element no longer in the document. Dropdowns close for the same reason, daisyUI's being
    // `:focus-within` and a fresh subtree holding no focus.
    //
    // Nothing here needs the rebuild to stay current: the rows read `elementAttributesUpdates`,
    // a zoomLens Var over the live graph, so they track edits on their own.
    //
    // A flex row, not a plain block: the selection views were designed as
    // shrink-to-fit flex items of the navbar, and daisyUI's select resolves its
    // `100%` width cap against a definite block width — a stretched wrapper
    // inflated the filter select to the full 20rem cap.
    div(
      cls := "flex-1 min-w-0 flex items-center",
      // A selected record CELL is one selection level below the element
      // selection: while it exists, the strip shows the cell's structural
      // controls instead of the record's attribute rows.
      child <-- state.selection.signal.distinct
        .combineWithFn(state.selectedCellV.signal.distinct): (sel, cellOpt) =>
          cellOpt match
            case Some(cell) => recordCellView(state, commands, cell)
            case None       => selectionView(state, commands, sel)
    ),
    // Hidden elements are invisible by definition — this chip is their one
    // visible trace, and the way back. Lives here (not in the toolbar) because
    // it is context about THIS view, present exactly while it applies.
    div(
      cls := "shrink-0",
      cls("hidden") <-- hiddenCount.map(_ == 0),
      Button(
        text <-- hiddenCount.map(n => s"$n hidden · Show all"),
        onClick --> all.showAll.execute()
      ).tiny.soft.primary.toTooltip(all.showAll.labelWithShortcut)
    )
  )

private def selectionSummary(sel: ElementIds): String =
  val IdsByKind(clusterIds, nodeIds, arrowIds) = sel.classify
  def part(n: Int, noun: String) = Option.when(n > 0)(s"$n $noun${if n == 1 then "" else "s"}")
  (part(nodeIds.size, "node"), part(arrowIds.size, "arrow"), part(clusterIds.size, "group")) match
    case (Some(s), None, None) => s
    case (None, Some(s), None) => s
    case (None, None, Some(s)) => s
    case _                     => s"${sel.size} objects"

/** Structural controls for the selected CELL — of a record node or an
  * html-table label. Record insert labels follow the cell's actual flow (rows
  * vs columns are relative in record syntax: the top level flows with rankdir
  * and each `{}` flips); html tables are true grids, so they get the full
  * row/column set.
  */
private def recordCellView(state: ViewerState, commands: Commands, cell: SelectedCell) =
  import commands.all

  def cmdButton(command: Command[?], iconCls: String, label: String) =
    Button(
      i(cls := s"bi $iconCls"),
      label,
      onClick --> { _ =>
        command.execute()
        // Hand focus back to the canvas so the next keystroke (Escape, arrows,
        // Backspace) reaches the command dispatcher instead of this button.
        state.canvasContainerFocus.emit(true)
      }
    ).tiny.ghost.toTooltip(command.labelWithShortcut)

  val chip =
    span(
      cls := "gx-selection-count",
      "cell",
      // Only the chip tracks the graph: the strip itself must NOT rebuild on
      // graph changes (it would destroy the control being used), but a renamed
      // port has to show its new name.
      child.maybe <-- state.recordCells.selectedCellPortSignal.map(
        _.map(p => span(cls := "ml-1 font-mono text-xs opacity-60", s"<$p>"))
      )
    )

  if state.recordCells.selectedCellIsHtml then
    div(
      cls := "flex flex-row items-center gap-1 min-w-0",
      chip,
      cmdButton(all.editLabel, "bi-pencil", "Edit"),
      cmdButton(all.insertRowAbove, "bi-arrow-bar-up", "Row"),
      cmdButton(all.insertRowBelow, "bi-arrow-bar-down", "Row"),
      cmdButton(all.insertCellBefore, "bi-arrow-bar-left", "Col"),
      cmdButton(all.insertCellAfter, "bi-arrow-bar-right", "Col"),
      cmdButton(all.deleteTableRow, "bi-dash-square", "Row"),
      cmdButton(all.deleteTableCol, "bi-dash-square", "Col"),
      cmdButton(all.removeCell, "bi-eraser", "Clear"),
      // What html tables buy over records: the cell's OWN paint and alignment.
      ToolbarHtmlCellAttributesView(state, state.recordCells.cellAttributeUpdates(cell))
    )
  else
    val lr                    = RecordTree.parentIsLR(cell.path, state.recordCells.topLRNow())
    val (beforeLbl, afterLbl) = if lr then ("bi-arrow-bar-left", "bi-arrow-bar-right") else ("bi-arrow-bar-up", "bi-arrow-bar-down")
    div(
      cls := "flex flex-row items-center gap-1",
      chip,
      cmdButton(all.editLabel, "bi-pencil", "Edit"),
      cmdButton(all.insertCellBefore, beforeLbl, "Insert"),
      cmdButton(all.insertCellAfter, afterLbl, "Insert"),
      cmdButton(all.splitCell, if lr then "bi-hr" else "bi-vr", "Split"),
      cmdButton(all.removeCell, "bi-x-lg", "Remove")
    )

private def selectionView(state: ViewerState, commands: Commands, selectedNodes: ElementIds) =
  import commands.all
  val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

  (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
    case (true, false, false) =>
      ToolbarArrowsAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(arrowIds))
      )

    case (false, true, false) =>
      ToolbarNodesAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(nodeIds))
      )

    case (false, false, true) =>
      ToolbarGroupAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(clusterIds))
      )

    case (false, false, false) =>
      // Nothing selected: the strip is only visible for the hidden-elements
      // chip. Selecting by kind lives in the Select menu and ⌘K now.
      div()

    case _ =>
      // A folded group is selected as its proxy NODE, so classifying the raw
      // ids counts it under Nodes and the Groups option silently omits it —
      // "Select all" then "Groups" lost the very groups whose boxes are most
      // visible. Classify the MODEL spelling (resolveCollapsed), and hand back
      // the CANVAS spelling (renderedId), or the filter would select GroupIds
      // with no rendered element behind them.
      val classified = state.resolveCollapsed(selectedNodes).classify
      val options    = SelectByKind.optionsForSelection(classified)
      div(
        cls := "flex flex-row gap-2",
        Select(
          placeholderText = Some(s"Filter ${selectedNodes.size} objects"),
          options = options.map(option => option.label -> option.kind.id),
          onChange.mapToValue --> { value =>
            ElementKind.fromId(value).foreach: kind =>
              val picked = SelectByKind.idsForSelection(classified, kind)
              state.selection.set(ElementIds(picked.ids.map(state.renderedId)))
          }
        )
      )
