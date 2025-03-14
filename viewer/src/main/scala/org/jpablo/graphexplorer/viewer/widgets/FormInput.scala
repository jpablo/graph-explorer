package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*

def FormInput(
    labelText:       String,
    placeholderText: String,
    inputValue:      Var[String],
    inputType:       String = "string"
) =
  div(
    cls := "form-control w-full",
    label(cls := "label", span(cls := "label-text", labelText)),
    input(
      cls         := "input  w-full",
      tpe         := inputType,
      placeholder := placeholderText,
      controlled(value <-- inputValue.signal, onInput.mapToValue --> inputValue.set)
    )
  )
