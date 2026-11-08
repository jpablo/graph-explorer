package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, DialogInput, PrimaryAction, QuietAction}
import org.scalajs.dom.KeyValue

/** The dialog that renames a diagram, in the dialog style of the app. Before,
  * the native `window.prompt` was the last browser interruption in the flow.
  *
  * The dialog keeps the contract of the prompt. It starts with the current
  * name. Enter or Ok saves the name. Escape or Cancel keeps the name. Ok with
  * a blank name also keeps the name, because no flow can make a diagram with
  * no name. The persistence sync does not store a blank name over a real name
  * (`BrowserLibrary.guardedDiagramName`). Thus a blank name here only makes the
  * title on the screen different from the stored name until the next reload.
  */
def RenameDiagramDialog(state: ViewerState) =
  val modalText: Var[String] = Var("")

  def closeDialog() =
    state.renameDialogOpen.set(false)

  def saveAndClose(): Unit =
    // Blank input is not a rename — same outcome as Cancel (see above).
    val newName = modalText.now()
    if newName.trim.nonEmpty then state.project.name.set(newName)
    closeDialog()

  div(
    idAttr := "rename-project-dialog",
    // Prefill on every open, not once at build: the name may have changed since.
    state.renameDialogOpen.signal --> { open =>
      if open then modalText.set(state.project.name.now())
    },
    child(
      Dialog(onDismiss = () => closeDialog())(
        div(
          h3(cls := "font-bold text-md mb-2", "Rename diagram"),
          DialogInput(
            placeholder := "Diagram name",
            controlled(value <-- modalText, onInput.mapToValue --> modalText),
            onMountFocus,
            onKeyDown.filter(_.key == KeyValue.Enter).preventDefault --> saveAndClose(),
            onKeyDown.filter(_.key == KeyValue.Escape) --> closeDialog()
          )
        )
      )(
        div(
          cls := "flex justify-end gap-2 mt-2",
          QuietAction("Cancel", onClick --> closeDialog()),
          PrimaryAction("Ok", onClick --> saveAndClose())
        )
      )
    ) <-- state.renameDialogOpen.signal
  )
