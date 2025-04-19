package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.domUtils.dialog
import org.scalajs.dom.KeyValue

def SimpleDialog(open: Var[Boolean], contents: Mods*) =
  Dialog(
    mods = cls("modal-open") <-- open.signal,
    onKeyDown.filter(_.key == KeyValue.Escape) --> open.set(false),
    tabIndex := 0,
    focus <-- open.signal.changes
  )(contents)(
    action =
      Button("close", onClick --> open.set(false)).tiny
  )

def Dialog(mods: Mods*)(contents: Mods*)(action: Mods*) =
  dialog(
    cls := "modal",
    mods,
    div(
      cls := "modal-box",
      contents,
      div(cls := "modal-action", form(method := "dialog", action))
    )
  )
