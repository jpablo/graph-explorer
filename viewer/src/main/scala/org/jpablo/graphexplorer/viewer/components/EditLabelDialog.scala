package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.toRawText
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, DialogTextArea, PrimaryAction, QuietAction}
import org.scalajs.dom.KeyValue

def EditLabelDialog(state: ViewerState) =
  val dialogIsOpen =
    state.editingElementV.zoomLazy(_.isDefined)((elem, open) => if open then elem else None)

  val modalText: Var[String] = Var("")

  def elementLabelVar(elementId: ElementId): Var[String] =
    val attrUpdates = state.elementAttributesUpdates(ElementIds(Set(elementId)))
    toRawText(RowBuilder.simpleInputVar(Label.attrId, attrUpdates), "")

  def closeDialog() =
    state.editingElementV.set(None)

  def saveAndClose(): Unit =
    state.editingElementV.now().foreach(elementLabelVar(_).set(modalText.now()))
    closeDialog()

  val textAreaElement = DialogTextArea(
    placeholder := "Enter label text...",
    rows        := 3,                                   // Give it a bit more initial space
    controlled(value <-- modalText, onInput.mapToValue --> modalText),
    onMountFocus,
    // Enter -> Save and Close
    // Shift+Enter -> Add a new line
    onKeyDown.filter(ev => ev.key == KeyValue.Enter && !ev.shiftKey).preventDefault --> saveAndClose(),
    // Escape -> Close without saving
    onKeyDown.filter(_.key == KeyValue.Escape) --> closeDialog()
  )

  div(
    idAttr := "edit-label-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", "Edit Label"),
          textAreaElement,
          div(
            cls := "flex justify-between items-center mt-2",
            p(cls := "text-xs text-gray-500", "Press Shift+Enter to add a new line")
          )
        ),
        // --- State Synchronization ---
        // Update modalText when the dialog opens
        state.editingElementV.signal.map(_.fold("")(elementLabelVar(_).now())) --> modalText,
        // Makes sure the focus is restored to CanvasContainer after the dialog is closed
        dialogIsOpen.signal --> { open =>
          if !open then
            state.canvasContainerFocus.emit(true)
        }
      )(
        // --- actions ---
        div(
          cls := "flex justify-end gap-2 mt-2", // Added gap and margin-top
          // Cancel recedes: it was a filled, outlined button standing beside the
          // primary, so the dialog offered two equally weighted ways out.
          QuietAction("Cancel", onClick --> closeDialog()),
          PrimaryAction("Ok", onClick --> saveAndClose())
        )
      )
    ) <-- dialogIsOpen
  )
