package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods

/** The alert family's tones — the one spelling of `alert-*`. */
enum AlertTone(val cls: String):
  case Success extends AlertTone("alert-success")
  case Error   extends AlertTone("alert-error")
  case Info    extends AlertTone("alert-info")

/** daisyUI alert with its ARIA role. Layout/spacing stays with the caller. */
def AlertBox(tone: AlertTone, mods: Mods*) =
  div(role := "alert", cls := s"alert ${tone.cls}", mods)

/** Corner container for transient notifications: daisyUI `toast` owns the
  * fixed positioning and stacking (bottom-end, above the canvas). */
def ToastCorner(mods: Mods*) =
  div(cls := "toast toast-end z-50", mods)
