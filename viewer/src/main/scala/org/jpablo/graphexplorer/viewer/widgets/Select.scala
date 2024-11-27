package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

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
    options:     Seq[(String, AttrValue)],
    selectValue: Var[Option[AttrValue]],
    default:     String,
    mods:        Mods*
) =
  select(
    cls := "select select-bordered select-xs w-full",
    options.map((name, id) => option(name, value := id.toString)),
    value <-- selectValue.signal.map(_.getOrElse(default).toString),
    onChange.mapToValue.map(v => Some(AttrValue(v))) --> selectValue,
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
    inputValue:      Var[Option[AttrValue]],
    inputType:       InputType = InputType.text,
    default:         String = "",
    setFocus:        Boolean = false
) =
  input(
    cls         := "input input-bordered input-xs w-full",
    tpe         := inputType.toString,
    placeholder := placeholderText,
    controlled(
      // double slash (\\n)
      value <-- inputValue.signal.map(_.getOrElse(default).toString),
      onInput.mapToValue.map(v => Some(AttrValue(v))) --> inputValue.set
    ),
    if setFocus then onMountFocus else emptyMod
  )

def Checked(
    placeholderText: String,
    inputValue:      Var[Option[Boolean]],
    default:         Boolean = false
) =
  input(
    cls         := "checkbox checkbox-xs",
    tpe         := InputType.checkbox.toString,
    placeholder := placeholderText,
    controlled(
      checked <-- inputValue.signal.map(_.getOrElse(default)),
      onInput.mapToChecked.map(Some(_)) --> inputValue.set
    )
  )
