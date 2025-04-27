package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.domUtils.dialog
import org.scalajs.dom.KeyValue

def SimpleDialog(open: Var[Boolean], contents: Mods*) =
  div(
    child(
      Dialog(
        mods = cls("modal-open"),
        // useCapture to prevent the Escape key to reach DaisyUI
        onKeyDown.useCapture.filter(_.key == KeyValue.Escape) --> open.set(false),
        tabIndex := 0
      )(contents)(
        action =
          Button("close", onClick --> open.set(false)).tiny
      )
    ) <-- open
  )

def Dialog(mods: Mods*)(contents: Mods*)(action: Mods*) =
  dialog(
    cls := "modal",
    onMountFocus,
    mods,
    div(
      cls := "modal-box",
      contents,
      div(cls := "modal-action", form(method := "dialog", action))
    )
  )
