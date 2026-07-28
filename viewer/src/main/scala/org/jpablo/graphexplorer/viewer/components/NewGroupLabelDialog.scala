package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, PrimaryAction, QuietAction}
import org.scalajs.dom.KeyValue

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
      val label = TextUtils.escape(text)
      // Create group with provided label, then select it
      state.createGroupWithLabel(pending.elementIds, label)
    closeDialog()

  val textAreaElement = textArea(
    cls         := "textarea w-full",
    placeholder := "Enter group label...",
    rows        := 3,
    controlled(value <-- modalText, onInput.mapToValue --> modalText),
    onMountFocus,
    // Enter -> Save and Create
    // Shift+Enter -> Add a new line
    onKeyDown.filter(ev => ev.key == KeyValue.Enter && !ev.shiftKey).preventDefault --> saveAndCreate(),
    // Escape -> Close without creating
    onKeyDown.filter(_.key == KeyValue.Escape) --> closeDialog()
  )

  div(
    idAttr := "new-group-label-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", "New Group Label"),
          textAreaElement,
          div(
            cls := "flex justify-between items-center mt-2",
            p(cls := "text-xs text-gray-500", "Press Shift+Enter to add a new line")
          )
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