package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.Mods

def SelectWithLabel(
    labelText:       String,
    placeholderText: String,
    options:         Seq[(String, String)],
    selectValue:     Var[Option[String]],
    mods:            Mods*
) =
  label(
    cls := "form-control w-full max-w-xs",
    div(cls := "label-text", span(cls := "label-text", labelText)),
    select(
      cls := "select select-bordered",
      option(placeholderText, disabled := true, selected := true),
      options.map((name, id) => option(name, value := id)),
      value <-- selectValue.signal.map(_.getOrElse("")),
      onChange.mapToValue.map(Some(_)) --> selectValue,
      mods
    )
  )

def Select(
    placeholderText: String,
    options:         Seq[(String, String)],
    mods:            Mods*
) =
  select(
    cls := "select select-bordered select-xs max-w-xs",
    option(placeholderText, disabled := true, selected := true),
    options.map((name, id) => option(name, value := id)),
    mods
  )

def SelectWithValue(
    options:     Seq[(String, String)],
    selectValue: Var[Option[String]],
    default:     String,
    mods:        Mods*
) =
  select(
    cls := "select select-bordered select-sm w-full",
    options.map((name, id) => option(name, value := id)),
    value <-- selectValue.signal.map(_.getOrElse(default)),
    onChange.mapToValue.map(Some(_)) --> selectValue,
    mods
  )

def BasicInput(
    placeholderText: String,
    inputValue:      Var[String],
    inputType:       String = "text"
) =
  input(
    cls         := "input input-bordered input-sm w-full",
    tpe         := inputType,
    placeholder := placeholderText,
    controlled(value <-- inputValue.signal, onInput.mapToValue --> inputValue.set)
  )

def InputWithValue(
    placeholderText: String,
    inputValue:      Var[Option[String]],
    inputType:       InputType = InputType.text,
    default:         String = ""
) =
  input(
    cls         := "input input-bordered input-sm w-full",
    tpe         := inputType.toString,
    placeholder := placeholderText,
    controlled(
      value <-- inputValue.signal.map(_.getOrElse(default)),
      onInput.mapToValue.map(Some(_)) --> inputValue.set
    )
  )

def Checked(
    placeholderText: String,
    inputValue:      Var[Option[Boolean]],
    default:         Boolean = false
) =
  input(
    cls         := "checkbox checkbox-sm",
    tpe         := InputType.checkbox.toString,
    placeholder := placeholderText,
    controlled(
      checked <-- inputValue.signal.map(_.getOrElse(default)),
      onInput.mapToChecked.map(Some(_)) --> inputValue.set
    )
  )
