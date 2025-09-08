package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Button, Dialog, primary, tiny}
import org.scalajs.dom.KeyValue

/** Dialog to collect a label before creating a new node. It mirrors the EditLabelDialog UX but triggers node creation on save. */
def NewNodeLabelDialog(state: ViewerState) =
  val dialogIsOpen =
    state.pendingNewNodeV.zoomLazy(_.isDefined)((pending, open) => if open then pending else None)

  val modalText: Var[String] = Var("")

  def closeDialog(): Unit =
    state.pendingNewNodeV.set(None)

  def saveAndCreate(): Unit =
    state.pendingNewNodeV.now().foreach: pending =>
      val text   = modalText.now()
      val attrs  = pending.attributes ++ Attributes.of(Label -> TextUtils.escape(text))
      val dir    = pending.direction
      // Create node with provided label, then select/focus continues as usual
      state.addNodeWithSmartConnection(attrs, dir)
    closeDialog()

  val textAreaElement = textArea(
    cls         := "textarea textarea-bordered w-full",
    placeholder := "Enter label text...",
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
    idAttr := "new-node-label-dialog",
    child(
      Dialog(
        mods = cls("modal-open"),
        // useCapture to prevent the Escape key to reach DaisyUI
        onKeyDown.useCapture.filter(_.key == KeyValue.Escape) --> dialogIsOpen.set(false),
        tabIndex := 0
      )(
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
        // Reset modal text when the dialog opens
        state.pendingNewNodeV.signal.map(_ => "") --> modalText,
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

