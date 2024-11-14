package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete

enum InputType:
  case select, text, color, number, checkbox, radio, file, hidden, password, range, submit, reset, button, image,
    datetime, datetimeLocal, date, month, time, week, url, email, search, tel

def Checkbox(mods: Modifier[ReactiveHtmlElement.Base]*): Input =
  input(tpe := InputType.checkbox.toString, cls := "checkbox", mods)

def Search(mods: Modifier[ReactiveHtmlElement.Base]*): Input =
  input(
    tpe := InputType.search.toString,
    cls := "input input-bordered input-xs input-primary w-full",
    mods
  )

def LabeledCheckbox(
    id:         String,
    labelStr:   String,
    isChecked:  Var[Boolean],
    isDisabled: Signal[Boolean] = Signal.fromValue(false),
    toggle:     Boolean = false
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
        tpe          := InputType.checkbox.toString,
        disabled <-- isDisabled,
        cls := (if toggle then "toggle toggle-xs" else "checkbox checkbox-xs"),
        controlled(checked <-- isChecked, onClick.mapToChecked --> isChecked)
      )
    )
  )
