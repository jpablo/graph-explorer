package org.jpablo.graphexplorer.viewer.widgets

import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, RowOption}
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete
import org.jpablo.graphexplorer.viewer.formats.dot.ColorType
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.widgets
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom.MouseEvent

def SelectWithLabel(
    labelText:       String,
    placeholderText: String,
    options:         Seq[(String, String)],
    selectValue:     Var[Option[String]],
    mods:            Mods*
) =
  label(
    cls := "form-control ",
    div(cls := "label-text", span(cls := "label-text", labelText)),
    select(
      cls := "select",
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
    cls := "select select-xs",
    option(placeholderText, disabled := true, selected := true),
    options.map((name, id) => option(name, value := id)),
    mods
  )

def Menu[A](
    options:        Seq[(Modifier.Base | String, A)],
    onClickHandler: EventProcessor[MouseEvent, A] => Modifier[Anchor]
) =
  ul(
    tabIndex := 0,
    cls      := "menu bg-base-100 rounded-box z-1 shadow-lg",
    for
      (name, value) <- options
      nameMod = name match
        case m: Modifier.Base => m
        case s: String        => span(s)
    yield li(
      a(
        nameMod,
        onClickHandler(onClick.mapTo(value))
      )
    )
  )

/** A menu with a horizontal layout, with the last item being a dropdown menu.
  */
def MenuWithExtraDropdown(row: InputAttribute, initial: Int) =
  def menuButton(option: RowOption) =
    div(
      cls   := "px-2",
      title := option.name,
      option.elem.fold(span(option.name))(elem => elem()),
      onClick.mapTo(option.value) --> row.inputVar
    )

  val initialOptions = row.options.take(initial)
  val extraOptions   = row.options.drop(initial)
  ul(
    tabIndex := 0,
    cls      := "menu menu-horizontal bg-base-100 rounded-box p-0",
    for option <- initialOptions yield li(menuButton(option)),
    li(
      cls := "justify-center",
      div(
        cls := "dropdown p-0 m-0",
        if extraOptions.isEmpty then emptyMod
        else
          Seq(
            // "extra" button
            div(tabIndex := 0, role := "button", cls := "btn btn-ghost btn-xs px-1", i().threeDotsVertical),
            // popup card
            div(
              tabIndex := 0,
              cls      := "dropdown-content card card-xs bg-base-100 z-1 w-61 shadow-md",
              div(
                cls := "card-body p-0",
                ul(
                  tabIndex := 0,
                  cls      := "p-0 m-0 menu menu-horizontal [&:before]:hidden",
                  for option <- extraOptions yield li(menuButton(option))
                )
              )
            )
          )
      )
    )
  )

def Dropdown[A](
    title:          Modifier.Base,
    options:        Seq[(Modifier.Base | String, A)],
    onClickHandler: EventProcessor[MouseEvent, A] => Modifier[Anchor],
    icon:           Modifier.Base = i(cls := "bi bi-chevron-down"),
    join:           Boolean = false
) =
  DropdownHeader(title, icon, join, Menu(options, onClickHandler).amend(cls := "w-52"))

def DropdownHeader(
    title: Modifier.Base,
    icon:  Modifier.Base = i(cls := "bi bi-chevron-down"),
    join:  Boolean = false,
    body:  HtmlElement
) =
  div(
    cls := "dropdown",
    div(
      tabIndex         := 0,
      role             := "button",
      cls              := "whitespace-nowrap",
      cls("join-item") := join,
      title,
      icon
    ).asBtn.tiny,
    body.amend(cls := "dropdown-content")
  )

def SelectWithValue(
    row:  InputAttribute,
    mods: Mods*
) =
  // TODO: use the SelectionAttrValue.Missing state (perhaps with a placeholder)
  select(
    cls := "select select-xs",
    cls := s"cls-${row.attrId}",
    row.options.map(o => option(o.name, value := o.value.toString)),
    controlled(
      value <-- row.combineDefault.map((v, d) => v.getOrElse(d).toString),
      onChange.mapToValue.map(v => Single(AttrValue(v))) --> row.inputVar
    ),
    mods
  )

def SelectWithPreview(row: InputAttribute) =
  div(
    cls := "dropdown",
    div(
      tabIndex := 0,
      role     := "button",
      cls      := "btn btn-xs w-full flex justify-between items-center",
      div(
        cls := "flex items-center gap-2",
        div(
          cls := "w-4 flex justify-center items-center" // Fixed width container matching the dropdown options
        ),
        child.maybe <-- row.inputVar.signal.combineWith(row.default).map: (sv, d) =>
          val currentValue = sv.getOrElse(d).toString
          row.options
            .collectFirst:
              case row if row.value.toString == currentValue =>
                row.elem.fold(span(row.name))(p => span(p()))
      ),
      i(cls := "bi bi-chevron-down")
    ).asBtn.tiny,
    // ---- Dropdown menu ----
    ul(
      tabIndex := 0,
      cls      := "dropdown-content z-[1] menu p-2 shadow bg-base-100 rounded-box w-full border border-base-300",
      row.options.map { rowOption =>
        li(
          a(
            cls := "flex items-center gap-2",
            div(
              cls := "w-4 flex justify-center items-center", // Fixed width container for the checkmark
              child.maybe <-- row.inputVar.signal.combineWith(row.default).map((sv, d) =>
                if sv.getOrElse(d).toString == rowOption.value.toString then
                  Some(i(cls := "bi bi-check2"))
                else None
              )
            ),
            rowOption.elem.fold(span(rowOption.name))(p => span(p())),
            onClick.mapTo(rowOption.value) --> row.inputVar
          )
        )
      }
    )
  )

def SingleRowMenu(row: InputAttribute) =
  Menu(
    options = row.options.map(o => o.name -> o.value),
    onClickHandler = _ --> (action => println(action))
  ).amend(cls := "items-center")

def SelectWithPreviewGrid(row: InputAttribute) =
  div(
    cls      := "dropdown dropdown-bottom dropdown-end",
    tabIndex := 0,
    button(
      cls      := "btn btn-xs w-full flex justify-between items-center",
      tabIndex := 0,
      div(
        cls := "flex items-center justify-center w-full pr-6",
        child.maybe <-- row.combineDefault.map: (sv, d) =>
          row.options
            .collectFirst:
              case row if row.hasValue(sv.getOrElse(d).toString) =>
                row.elem.fold(span(row.name))(preview => preview())
      ),
      i(cls := "bi bi-chevron-down absolute right-2")
    ),
    // ---- Dropdown menu ----
    div(
      cls      := "dropdown-content card card-md w-48 shadow bg-base-100 border border-base-300",
      tabIndex := 0,
      div(
        cls := "card-body grid grid-cols-3 gap-2 overflow-y-auto max-h-64",
        row.options.zipWithIndex.map { (rowOption, index) =>
          div(
            cls             := s"tooltip ${if index < 3 then "tooltip-bottom" else "tooltip-top"}",
            dataAttr("tip") := rowOption.name,
            button(
              cls <-- row.combineDefault.map((sv, d) =>
                val active = if rowOption.hasValue(sv.getOrElse(d).toString) then "btn-active" else ""
                s"btn btn-ghost btn-sm flex flex-col items-center justify-center p-1 $active"
              ),
              rowOption.elem.fold(span(rowOption.name))(preview => preview()),
              onClick.mapTo(rowOption.value) --> row.inputVar
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
    cls         := "input  input-sm w-full",
    tpe         := inputType,
    placeholder := placeholderText,
    controlled(value <-- inputValue.signal, onInput.mapToValue --> inputValue.set)
  )

def InputWithValue(
    row:      InputAttribute,
    setFocus: Boolean = false
) =
  // hack
  val htmlRegex         = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  val extra =
    row.inputType match
      case InputType.number(start, end, step) =>
        Seq(
          cls      := s"input input-xs text-right",
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )
      case InputType.range(start, end, step) =>
        Seq(
          cls      := "range range-xs input-ghost",
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )
      case _ => Seq(cls := s"input input-xs input-ghost hover")

  val colorType = row.combineDefaultString.map(ColorType.fromString)

  // While we get a better color selector, approximate by removing the alpha channel
  val valueSignal = row.inputType match
    case InputType.color => colorType.map(ColorType.toHexNoAlpha)
    case _               => row.combineDefaultString

  // While we get a better color selector, use a text input for named colors
  val inputType = row.inputType match
    case _: InputType.number => Signal.fromValue("number")
    case _: InputType.range  => Signal.fromValue("range")
    case _                   => Signal.fromValue(row.inputType.toString)

  input(
    tpe <-- inputType,
    placeholder := row.placeholder,
    controlled(
      value <-- valueSignal,
      onInput.mapToValue.map(v => Single(AttrValue(if isHtml(v) then AttrEq(v, true) else v))) --> row.inputVar
    ),
    if setFocus then onMountFocus else emptyMod,
    extra
  )

def TextAreaWithValue(
    row:      InputAttribute,
    default:  String = "",
    setFocus: Boolean = false
) =
  val htmlRegex         = """<([a-zA-Z][a-zA-Z0-9]*)[^>]*>.*?</\1>""".r
  def isHtml(s: String) = htmlRegex.matches(s)

  // Note .replaceAll operates on regexes, so we need to escape the backslashes
  val rawText = row.inputVar
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
        Single(AttrValue(if isHtml(uiText) then AttrEq(dotText, true) else dotText))
    )

  textArea(
    cls         := "textarea textarea-xs min-h-[2rem] h-auto w-full p-1",
    placeholder := row.placeholder,
    value <-- rawText.signal,
    onInput.mapToValue --> rawText.set,
    if setFocus then onMountFocus else emptyMod
  )

def Checked(row: InputAttribute) =
  input(
    cls         := "toggle toggle-xs",
    tpe         := InputType.checkbox.toString,
    placeholder := row.placeholder,
    controlled(
      checked <-- row.combineDefaultBoolean,
      onInput.mapToChecked.map(b => Single(AttrValue(b.toString))) --> row.inputVar
    )
  )

def Toggle(text: String, mods: Mods*) =
  label( cls := "fieldset-label",
    input(tpe := InputType.checkbox.toString, cls := "toggle toggle-xs", mods),
    text
  )

def Search(mods: Mods*): Input =
  input(
    tpe := InputType.search.toString,
    cls := "input  input-xs input-primary",
    mods
  )

def LabeledCheckbox(
    id:         String,
    labelStr:   String,
    isChecked:  Var[Boolean],
    isDisabled: Signal[Boolean] = Signal.fromValue(false),
    toggle:     Boolean = true
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
