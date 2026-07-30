package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, DialogTextArea, PrimaryAction, QuietAction}
import org.scalajs.dom.KeyValue

/** The CELL-scoped sibling of [[EditLabelDialog]]: edits one field of a record
  * node. The text shown is the cell's UNescaped text (`RecordTree.displayText`);
  * saving re-escapes and splices it back into the record label at the cell's
  * path — the record syntax (`|`, `{}`, `<port>`) never leaks into the dialog.
  */
def EditCellDialog(state: ViewerState) =
  val dialogIsOpen =
    state.editingCellV.zoomLazy(_.isDefined)((cell, open) => if open then cell else None)

  val modalText: Var[String] = Var("")

  def closeDialog() =
    state.editingCellV.set(None)

  def saveAndClose(): Unit =
    state.editingCellV.now().foreach(cell => state.recordCells.setCellText(cell, modalText.now()))
    closeDialog()

  val textAreaElement = DialogTextArea(
    placeholder := "Enter cell text...",
    rows        := 3,
    controlled(value <-- modalText, onInput.mapToValue --> modalText),
    onMountFocus,
    // Enter -> Save and Close; Shift+Enter -> new line (becomes \n in the label)
    onKeyDown.filter(ev => ev.key == KeyValue.Enter && !ev.shiftKey).preventDefault --> saveAndClose(),
    onKeyDown.filter(_.key == KeyValue.Escape) --> closeDialog()
  )

  div(
    idAttr := "edit-cell-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        div(
          h3(
            cls := "font-bold text-md mb-2",
            "Edit Cell",
            // the cell's port, when named, is the one bit of record syntax worth showing
            child.maybe <-- state.editingCellV.signal.map(_ =>
              state.recordCells.selectedCellPort.map(p => span(cls := "ml-2 font-mono text-xs opacity-60", s"<$p>"))
            )
          ),
          textAreaElement,
          div(
            cls := "flex justify-between items-center mt-2",
            p(cls := "text-xs text-gray-500", "Press Shift+Enter to add a new line")
          )
        ),
        // Update modalText when the dialog opens
        state.editingCellV.signal.map(_.fold("")(state.recordCells.cellDisplayText)) --> modalText,
        // Restore focus to the canvas when the dialog closes
        dialogIsOpen.signal --> { open =>
          if !open then state.canvasContainerFocus.emit(true)
        }
      )(
        div(
          cls := "flex justify-end gap-2 mt-2",
          QuietAction("Cancel", onClick --> closeDialog()),
          PrimaryAction("Ok", onClick --> saveAndClose())
        )
      )
    ) <-- dialogIsOpen
  )
