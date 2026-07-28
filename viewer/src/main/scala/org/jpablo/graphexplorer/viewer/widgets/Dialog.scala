package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.domUtils.{dialog, onDialogClose}
import org.scalajs.dom.KeyValue

/** daisyUI modal on a NATIVE `<dialog>`: `showModal()` on mount, so the browser
  * provides the focus trap and the `::backdrop`. Callers mount it with
  * `child(...) <-- open`, and `onDismiss` must drop whatever state keeps it
  * mounted.
  *
  * Dismissal is wired EXPLICITLY — Escape below, action buttons via their own
  * onClick — rather than observed through the element's `close` event: the
  * embedded Chromium used by the desktop app provably never fires
  * `close`/`cancel` (verified against a pristine `<dialog>`), and a dismissal
  * built on it strands the dialog closed-but-mounted, eating the next open.
  * Escape preventDefaults the native close request so there is exactly ONE
  * dismissal path; the `close` listener is a belt for any programmatic
  * `.close()` in browsers that do fire it.
  *
  * (The previous incarnation force-added `modal-open` and intercepted Escape
  * in the capture phase; `showModal()` replaces both the class and the
  * hand-rolled focus handling.)
  */
def Dialog(onDismiss: () => Unit, mods: Mods*)(contents: Mods*)(action: Mods*) =
  dialog(
    cls := "modal",
    // `child(...) <-- open` REUSES this element across open/close cycles, and
    // removal from the DOM does not clear a dialog's `open` state — so a
    // remount would find it already open and `showModal()` would THROW,
    // aborting Laminar's mount lifecycle halfway ("DynamicOwner already
    // active" on every open after the first). Close before showing, and close
    // again on unmount, so every mount starts from a clean dialog.
    onMountCallback { ctx =>
      val el = ctx.thisNode.ref
      if el.open then el.close()
      el.showModal()
    },
    onUnmountCallback(_.ref.close()),
    onKeyDown.filter(_.key == KeyValue.Escape).preventDefault --> onDismiss(),
    onDialogClose --> onDismiss(),
    mods,
    div(
      cls := "modal-box",
      contents,
      div(cls := "modal-action", form(method := "dialog", action))
    )
  )

def SimpleDialog(open: Var[Boolean], contents: Mods*) =
  div(
    child(
      Dialog(onDismiss = () => open.set(false))(contents)(
        // Dismissal, so it recedes -- and "Close", to match the sentence case every other
        // button in the app uses. The Help and About dialogs are the two call sites.
        QuietAction("Close", onClick --> open.set(false))
      )
    ) <-- open
  )
