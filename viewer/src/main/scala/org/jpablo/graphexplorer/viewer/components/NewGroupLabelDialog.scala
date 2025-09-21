package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Button, Dialog, primary, tiny}
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
    cls         := "textarea textarea-bordered w-full",
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
      Dialog(
        mods = cls("modal-open"),
        // useCapture to prevent the Escape key to reach DaisyUI
        onKeyDown.useCapture.filter(_.key == KeyValue.Escape) --> dialogIsOpen.set(false),
        tabIndex := 0
      )(
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
          Button("Cancel", onClick --> closeDialog()).tiny,
          Button("Ok", onClick --> saveAndCreate()).tiny.primary
        )
      )
    ) <-- dialogIsOpen
  )