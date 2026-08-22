package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{SaveResult, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.{Action, Dialog, PrimaryAction, QuietAction}

import scala.concurrent.ExecutionContext.Implicits.global

/** Leaving a loose file that has an unsaved edit (§7.4).
  *
  * Three answers, because two would force a loss: Cancel keeps the person here,
  * Discard leaves and drops the edit, and Save writes the file first.
  *
  * §7.4 warns against doing this work in `pagehide`: IPC completion during
  * teardown is not guaranteed, so a save started there may never finish. This
  * dialog runs BEFORE the navigation, and the navigation waits for the save.
  */
def UnsavedChangesDialog(state: ViewerState, router: Router): Div =
  def leave(route: Route): Unit =
    state.pendingLeave.set(None)
    // Force, because the guard would ask the same question again and the
    // person has already answered it.
    router.forceNavigateTo(route)

  div(
    child <-- state.pendingLeave.signal.map:
      case None => emptyNode
      case Some(route) =>
        Dialog(onDismiss = () => state.pendingLeave.set(None))(
          div(
            h3(cls := "font-bold text-md mb-2", "Save this file?"),
            p("This file has changes that are not written to disk.")
          )
        )(
          div(
            cls := "flex gap-2",
            QuietAction("Cancel", onClick --> state.pendingLeave.set(None)),
            Action("Discard", onClick --> leave(route)),
            PrimaryAction(
              "Save",
              onClick --> state
                .save()
                .foreach:
                  case SaveResult.Saved => leave(route)
                  // The navigation is abandoned, not retried. A conflict or a
                  // failure means the edit is still the only copy, and leaving
                  // now would lose it — which is what this dialog prevents.
                  case SaveResult.Conflict(message) =>
                    state.pendingLeave.set(None)
                    state.errorBus.emit(message)
                  case SaveResult.Failed(message) =>
                    state.pendingLeave.set(None)
                    state.errorBus.emit(s"Save failed: $message")
                  case SaveResult.Unsupported(message) =>
                    state.pendingLeave.set(None)
                    state.infoBus.emit(message)
            )
          )
        )
  )
