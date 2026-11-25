package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.labelEditor.{DiagramLabelEditor, NewLabelIds}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, PrimaryAction, QuietAction}

/** Dialog to collect a label before creating a new group. It mirrors the NewNodeLabelDialog UX but triggers group creation on save. */
def NewGroupLabelDialog(state: ViewerState) =
  val dialogIsOpen =
    state.pendingNewGroupV.zoomLazy(_.isDefined)((pending, open) => if open then pending else None)

  val modalText: Var[String] = Var("")

  def closeDialog(): Unit =
    state.pendingNewGroupV.set(None)

  def saveAndCreate(): Unit =
    state.pendingNewGroupV.now().foreach: pending =>
      val text = modalText.now()
      // The rule of the Edit Label dialog: HTML-like markup stays HTML.
      val label = AttributeRow.labelValue(text).toString
      // Create group with provided label, then select it
      state.createGroupWithLabel(pending.elementIds, label)
    closeDialog()

  def labelEditor = DiagramLabelEditor(
    state    = state,
    text     = modalText,
    graph    = state.fullGraph,
    // The id of the new group is not in the diagram.
    element  = state.fullGraph.map(NewLabelIds.group),
    onSave   = () => saveAndCreate(),
    onCancel = () => closeDialog(),
    hint     = "Enter group label..."
  )

  div(
    idAttr := "new-group-label-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", "New Group Label"),
          labelEditor
        ),
        // --- State Synchronization ---
        // Reset modal text when the dialog opens
        state.pendingNewGroupV.signal.map(_ => "") --> modalText,
        // Make sure the focus is restored to CanvasContainer after the dialog is closed
        dialogIsOpen.signal --> { open =>
          if !open then
            state.canvasContainerFocus.emit(true)
        }
      )(
        // --- actions ---
        div(
          cls := "flex justify-end gap-2 mt-2",
          // Cancel recedes: it was a filled, outlined button standing beside the
          // primary, so the dialog offered two equally weighted ways out.
          QuietAction("Cancel", onClick --> closeDialog()),
          PrimaryAction("Ok", onClick --> saveAndCreate())
        )
      )
    ) <-- dialogIsOpen
  )