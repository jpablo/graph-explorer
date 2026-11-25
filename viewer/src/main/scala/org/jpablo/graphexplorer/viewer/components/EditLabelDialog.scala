package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.toRawText
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.components.labelEditor.DiagramLabelEditor
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, PrimaryAction, QuietAction}

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

  // The dialog shows only while an element is in `editingElementV`. The
  // empty node id is for the closed dialog, which shows no preview.
  val editedElement: Signal[ElementId] =
    state.editingElementV.signal.map(_.getOrElse(NodeId("")))

  def labelEditor = DiagramLabelEditor(
    state    = state,
    text     = modalText,
    graph    = state.fullGraph,
    element  = editedElement,
    onSave   = () => saveAndClose(),
    onCancel = () => closeDialog(),
    hint     = "Enter label text..."
  )

  div(
    idAttr := "edit-label-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", "Edit Label"),
          labelEditor
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
