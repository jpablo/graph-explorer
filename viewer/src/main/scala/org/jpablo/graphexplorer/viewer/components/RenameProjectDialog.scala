package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, DialogInput, PrimaryAction, QuietAction}
import org.scalajs.dom.KeyValue

/** Renaming, in the app's own dialog vocabulary — the native `window.prompt`
  * was the one browser-chrome interruption left in the flow. Same contract as
  * the prompt it replaces: prefilled with the current name, Enter/Ok commits,
  * Escape/Cancel leaves the name untouched. A blank Ok also leaves it
  * untouched: no flow may produce a nameless project — the persistence sync
  * refuses to store a blank name over a real one (ProjectStorage's
  * guardedProjectName), so committing one here would only desync the
  * on-screen title from the stored name until the next reload.
  */
def RenameProjectDialog(state: ViewerState) =
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
