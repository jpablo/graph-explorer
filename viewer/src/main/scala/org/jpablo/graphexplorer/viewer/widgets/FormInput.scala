package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement

def FormInput(
    labelText:       String,
    placeholderText: String,
    inputValue:      Var[String],
    inputType:       String = "string"
) =
  FormInputWrapper(
    labelText,
    placeholderText,
    input(
      tpe := inputType,
      controlled(value <-- inputValue.signal, onInput.mapToValue --> inputValue.set)
    )
  )

def FormInputWrapper(
    labelText:       String,
    placeholderText: String,
    mod:             ReactiveHtmlElement[dom.html.Element]
) =
  div(
    cls := "form-control w-full",
    label(cls := "label", span(cls := "label-text", labelText)),
    mod.amend(
      cls         := "input input-bordered w-full",
      placeholder := placeholderText
    )
  )
