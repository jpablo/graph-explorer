package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
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
  *
  * On the desktop, a diagram is a file, and "Rename" renames the FILE
  * (ADR 0001). The dialog starts with the file name. A name with no
  * extension keeps the extension of the file. The library moves the metadata
  * of the file to its new name, and it refuses a name that exists.
  */
def RenameDiagramDialog(state: ViewerState) =
  import scala.concurrent.ExecutionContext.Implicits.global

  val modalText: Var[String] = Var("")

  /** The view shows a file of the desktop, so a rename renames the file. */
  val renamesFile: Boolean =
    state.target match
      case ViewTarget.File(_) => Library.isDesktop
      case _                  => false

  def closeDialog() =
    state.renameDialogOpen.set(false)

  def saveAndClose(): Unit =
    // Blank input is not a rename — same outcome as Cancel (see above).
    val newName = modalText.now()
    if newName.trim.nonEmpty then
      if renamesFile then state.renameFile(newName.trim)
      else state.metadata.name.set(newName)
    closeDialog()

  /** The name to start with: the file name of a file, and else the stored name. */
  def currentName: String =
    state.target match
      case ViewTarget.File(_) if renamesFile =>
        state.documentSessionNow.fold(state.metadata.name.now()): s =>
          s.path.substring(s.path.lastIndexWhere(c => c == '/' || c == '\\') + 1)
      case _ => state.metadata.name.now()

  div(
    idAttr := "rename-project-dialog",
    // Prefill on every open, not once at build: the name may have changed since.
    state.renameDialogOpen.signal --> { open =>
      if open then modalText.set(currentName)
    },
    child(
      Dialog(onDismiss = () => closeDialog())(
        div(
          h3(cls := "font-bold text-md mb-2", if renamesFile then "Rename file" else "Rename diagram"),
          DialogInput(
            placeholder := (if renamesFile then "File name" else "Diagram name"),
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
