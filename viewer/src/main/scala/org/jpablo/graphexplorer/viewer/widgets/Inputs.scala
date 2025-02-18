package org.jpablo.graphexplorer.viewer.widgets

import org.jpablo.graphexplorer.viewer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.RowOption
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
    options:     Seq[RowOption],
    selectValue: Var[Option[AttrValue]],
    default:     Signal[String],
    mods:        Mods*
) =
  select(
    cls := "select select-bordered select-xs w-full",
    options.map(o => option(o.name, value := o.value.toString)),
    controlled(
      value <-- selectValue.signal.combineWith(default).map((sv, d) => sv.getOrElse(d).toString),
      onChange.mapToValue.map(v => Some(AttrValue(v))) --> selectValue
    ),
    mods
  )

def SelectWithPreview(
    options:     Seq[RowOption],
    selectValue: Var[Option[AttrValue]],
    default:     Signal[String]
) =
  div(
    cls      := "dropdown dropdown-hover w-full",
    tabIndex := 0,
    button(
      cls      := "btn btn-xs w-full flex justify-between items-center",
      tabIndex := 0,
      div(
        cls := "flex items-center gap-2",
        div(
          cls := "w-4 flex justify-center items-center"  // Fixed width container matching the dropdown options
        ),
        child.maybe <-- selectValue.signal.combineWith(default).map: (sv, d) =>
          val currentValue = sv.getOrElse(d).toString
          options
            .collectFirst:
              case row if row.value.toString == currentValue =>
                row.preview.fold(span(row.name))(p => span(p()))
      ),
      i(cls := "bi bi-chevron-down")
    ),
    // ---- Dropdown menu ----
    ul(
      cls      := "dropdown-content z-[1] menu p-2 shadow bg-base-100 rounded-box w-full",
      tabIndex := 0,
      options.map { row =>
        li(
          a(
            cls := "flex items-center gap-2",
            div(
              cls := "w-4 flex justify-center items-center",  // Fixed width container for the checkmark
              child.maybe <-- selectValue.signal.combineWith(default).map((sv, d) => 
                if sv.getOrElse(d).toString == row.value.toString then
                  Some(i(cls := "bi bi-check2"))
                else None
              )
            ),
            row.preview.fold(span(row.name))(p => span(p())),
            onClick.mapTo(Some(row.value)) --> selectValue
          )
        )
      }
    )
  )

def SelectWithPreviewGrid(
    options:     Seq[RowOption],
    selectValue: Var[Option[AttrValue]],
    default:     Signal[String]
) =
  div(
    cls      := "dropdown dropdown-hover dropdown-bottom dropdown-end w-full",
    tabIndex := 0,
    button(
      cls      := "btn btn-xs w-full flex justify-between items-center",
      tabIndex := 0,
      div(
        cls := "flex items-center justify-center w-full pr-6",
        child.maybe <-- selectValue.signal.combineWith(default).map: (sv, d) =>
          val currentValue = sv.getOrElse(d).toString
          options
            .collectFirst:
              case row if row.value.toString == currentValue =>
                row.preview.fold(span(row.name))(preview => span(preview()))
      ),
      i(cls := "bi bi-chevron-down absolute right-2")
    ),
    // ---- Dropdown menu ----
    div(
      cls := "dropdown-content card card-compact z-[1] w-64 p-2 shadow bg-base-100",
      tabIndex := 0,
      div(
        cls := "card-body grid grid-cols-3 gap-2 overflow-y-auto max-h-64",
        options.zipWithIndex.map { (row, index) =>
          div(
            cls := s"tooltip ${if index < 3 then "tooltip-bottom" else "tooltip-top"}",
            dataAttr("tip") := row.name,
            button(
              cls <-- selectValue.signal.combineWith(default).map((sv, d) =>
                val currentValue = sv.getOrElse(d).toString
                s"btn btn-ghost btn-sm flex flex-col items-center justify-center p-1 ${if currentValue == row.value.toString then "btn-active" else ""}"
              ),
              row.preview.fold(span(row.name))(p => span(p())),
              onClick.mapTo(Some(row.value)) --> selectValue
            )
          )
        }
      )
    )
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
    inputType:       String = "text",
    default:         Signal[String],
    setFocus:        Boolean = false,
    border:          Boolean = true
) =
  // hack
  val htmlRegex = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  input(
    cls         := "input input-xs w-full",
    cls("input-bordered") := border,
    tpe         := inputType,
    placeholder := placeholderText,
    controlled(
      value <-- inputValue.signal.combineWith(default).map((sv, d) => sv.getOrElse(d).toString),
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
  val htmlRegex = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  // Note .replaceAll operates on regexes, so we need to escape the backslashes
  val rawText = inputValue
    .bimap(
      // DOT -> UI
      getThis = dotText =>
        val uiText = dotText.getOrElse(default).toString
          .replaceAll(
            """\\\\""", // regex matching two backslashes
            """\\"""    // replaced by a single backslash
          )
          .replaceAll("""\\n""", "\n")
        uiText
    )(
      // UI -> DOT
      getParent = uiText =>
        val dotText = uiText
          // escape single slashes first; this will ignore newlines
          .replaceAll("""\\""", """\\\\""")
          // replace '\n' (single character) with two characters: ['\\', 'n']
          .replaceAll("\n", """\\n""")
        Some(AttrValue(if isHtml(uiText) then AttrEq(dotText, true) else dotText))
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
    default:         Signal[Boolean]
) =
  input(
    cls         := "checkbox checkbox-xs",
    tpe         := InputType.checkbox.toString,
    placeholder := placeholderText,
    controlled(
      checked <-- inputValue.signal.combineWith(default).map((sv, d) => sv.getOrElse(d)),
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
