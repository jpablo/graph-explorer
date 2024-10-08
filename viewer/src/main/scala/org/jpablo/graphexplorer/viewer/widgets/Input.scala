package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete

def Checkbox(mods: Modifier[ReactiveHtmlElement.Base]*): Input =
  input(tpe := "checkbox", cls := "checkbox", mods)

def Search(mods: Modifier[ReactiveHtmlElement.Base]*): Input =
  input(
    tpe := "search",
    cls := "input input-bordered input-xs input-primary w-full",
    mods
  )

def LabeledCheckbox(
    id:           String,
    labelStr:     String,
    isChecked:    Var[Boolean],
    isDisabled:   Signal[Boolean] = Signal.fromValue(false),
    toggle:       Boolean = false
) =
  div(
    cls := "form-control",
    label(
      forId := id,
      cls   := "label cursor-pointer",
      span(cls := "label-text pr-1", labelStr),
      input(
        idAttr       := id,
        autocomplete := "off",
        tpe          := "checkbox",
        disabled <-- isDisabled,
        cls := (if toggle then "toggle toggle-xs" else "checkbox checkbox-xs"),
        controlled(checked <-- isChecked, onClick.mapToChecked --> isChecked)
      )
    )
  )
