package org.jpablo.graphexplorer.viewer.widgets

import org.jpablo.graphexplorer.viewer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete


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
  // hack
  val htmlRegex = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  input(
    cls         := "input input-bordered input-xs w-full",
    tpe         := inputType.toString,
    placeholder := placeholderText,
    controlled(
      value <-- inputValue.signal.map(_.getOrElse(default).toString),
      onInput.mapToValue.map { v =>
        Some(AttrValue(if isHtml(v) then AttrEq(v, true) else v))
      } --> inputValue.set
    ),
    if setFocus then onMountFocus else emptyMod
  )

def TextAreaWithValue(
    placeholderText: String,
    inputValue:      Var[Option[AttrValue]],
    default:         String = "",
    setFocus:        Boolean = false
) =
  // hack
  val htmlRegex = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)
  val rawText = inputValue
    .bimap(getThis = v =>
      v.getOrElse(default).toString.replaceAll("\\\\n", "\n")
    )(
      getParent = v =>
        val escaped = v.replaceAll("\n", "\\\\n")
        Some(AttrValue(if isHtml(v) then AttrEq(escaped, true) else escaped))
    )

  textArea(
    cls         := "input input-bordered input-xs w-full",
    placeholder := placeholderText,
    value <-- rawText.signal,
    onInput.mapToValue --> rawText.set,
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
