package org.jpablo.graphexplorer.viewer.widgets

import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, RowOption}
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete
import org.jpablo.graphexplorer.viewer.color.ColorFormat
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.widgets
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom.MouseEvent
import org.jpablo.graphexplorer.viewer.utils.intersperse
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.*

enum MenuEntry[+A] derives CanEqual:
  case MenuOption(
      elem:        Modifier.Base | String,
      value:       A,
      description: Option[String] = None,
      shortcut:    Option[List[String]] = None
  )

  case Sep

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
    options:        Signal[Seq[MenuEntry[A]]],
    onClickHandler: EventProcessor[MouseEvent, A] => Modifier[Anchor]
) =
  ul(
    tabIndex := 0,
    cls      := "menu menu-xs bg-base-100 rounded-lg z-1 shadow-lg p-1 border border-base-300",
    children <-- options.map: opts =>
      for
        entry <- opts
      yield entry match
        case MenuOption(elem, value, description, shortcut) =>
          val nameMod = elem match
            case m: Modifier.Base => m
            case s: String        => span(s)
          li(
            a(
              cls := "rounded-md",
              cls := "flex justify-between",
              title.maybe(description),
              nameMod,
              shortcut.map(_.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)).intersperse(span(" + "))),
              onClickHandler(onClick.mapTo(value))
            )
          )

        case Sep => li()
  )

/** A menu with a horizontal layout, with the last item being a dropdown menu.
  */
def MenuWithExtraDropdown(row: InputAttribute, initial: Int, dir: MenuDirection, cardClass: Option[String] = None) =
  def menuButton(rowOption: RowOption) =
    div(
      cls := "p-1",
      cls("bg-base-200") <-- row.isSelected(rowOption),
      title := rowOption.name,
      rowOption.elem.fold(span(rowOption.name))(elem => elem()),
      onClick.mapTo(rowOption.value) --> row.inputVar
    )

  val initialOptions = row.options.take(initial)
  val extraOptions   = row.options.drop(initial)
  ul(
    tabIndex               := 0,
    cls                    := s"menu-with-extra-dropdown menu menu-horizontal bg-base-100 rounded-box p-0",
    cls("justify-between") := extraOptions.nonEmpty,
    for option <- initialOptions yield li(menuButton(option)),
    li(
      cls := "justify-center",
      div(
        cls := s"dropdown dropdown-bottom p-0 m-0",
        // TailwindCSS classes seem to have issues with dynamic strings, so we add the cases we need here.
        cls("dropdown-start") := dir == MenuDirection.start,
        cls("dropdown-end")   := dir == MenuDirection.end,
        if extraOptions.isEmpty then emptyMod
        else
          Seq(
            // "more" button
            div(tabIndex := 0, role := "button", cls := "btn btn-ghost btn-xs px-1", i().threeDotsVertical),
            // popup card
            div(
              tabIndex := 0,
              cls      := "dropdown-content card card-xs bg-base-100 z-1 w-82 max-h-100 overflow-y-auto shadow-md border border-base-200",
              // extra style
              cardClass.map(cc => cls := cc),
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

def CurrentValueWithSelector(row: InputAttribute, dir: MenuDirection, cardClass: Option[String] = None) =
  def menuButton(rowOption: RowOption) =
    div(
      cls := "p-1",
      cls("bg-base-200") <-- row.isSelected(rowOption),
      title := rowOption.name,
      rowOption.elem.fold(span(rowOption.name))(elem => elem()),
      onClick.mapTo(rowOption.value) --> row.inputVar
    )

  val selectedOption: Signal[ReactiveElement.Base] =
    row.combineDefaultString.map: attrValueStr =>
      val selected = row.options.find(o => o.value.toString == attrValueStr).flatMap(_.elem).map(_())
      if selected.isEmpty then
        pprint.log(attrValueStr)
        pprint.log(row.options.map(o => o.value.toString))
      selected.getOrElse(span("?"))

  div(
    cls := s"dropdown dropdown-bottom p-0 m-0",
    // TailwindCSS classes seem to have issues with dynamic strings, so we add the cases we need here.
    cls("dropdown-start") := dir == MenuDirection.start,
    cls("dropdown-end")   := dir == MenuDirection.end,
    if row.options.isEmpty then emptyMod
    else
      Seq(
        // current value button
        div(tabIndex := 0, role := "button", cls := "btn btn-ghost btn-xs px-1", child <-- selectedOption),
        // popup card
        div(
          tabIndex := 0,
          cls      := "dropdown-content card card-xs bg-base-100 z-1 w-82 max-h-100 overflow-y-auto shadow-md border border-base-200",
          // extra style
          cardClass.map(cc => cls := cc),
          div(
            cls := "card-body p-0",
            ul(
              tabIndex := 0,
              cls      := "p-0 m-0 menu menu-horizontal [&:before]:hidden",
              for option <- row.options yield li(menuButton(option))
            )
          )
        )
      )
  )

def Dropdown[A](
    title:          Modifier.Base,
    options:        Signal[Seq[MenuEntry[A]]],
    onClickHandler: EventProcessor[MouseEvent, A] => Modifier[Anchor],
    icon:           Modifier.Base = i(cls := "bi bi-chevron-down"),
    join:           Boolean = false,
    menuCls:        String = "w-48"
) =
  DropdownHeader(title, icon, join, Menu(options, onClickHandler).amend(cls := menuCls))

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
      cls              := "btn mt-[-3px] whitespace-nowrap btn-ghost",
      cls("join-item") := join,
      title,
      icon
    ).tiny,
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

  val colorType = row.combineDefaultString.map(ColorFormat.fromString)

  // While we get a better color selector, approximate by removing the alpha channel
  val valueSignal = row.inputType match
    case InputType.color => colorType.map(c => ColorFormat.toHexNoAlpha(c).value)
    case _               => row.combineDefaultString

  // While we get a better color selector, use a text input for named colors
  val inputType = row.inputType match
    case _: InputType.number => Signal.fromValue("number")
    case _: InputType.range  => Signal.fromValue("range")
    case _                   => Signal.fromValue(row.inputType.toString)

  input(
    tpe <-- inputType,
    placeholder := row.placeholder,
    value <-- valueSignal,
    onInput.mapToValue.map(v => Single(AttrValue(if isHtml(v) then AttrEq(v, true) else v)))(_.throttle(50)) --> row.inputVar,
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
      getThis = dotText => TextUtils.unescape(dotText.getOrElse(default).toString)
    )(
      // UI -> DOT
      getParent = uiText =>
        val dotText = TextUtils.escape(uiText)
        Single(AttrValue(if isHtml(uiText) then AttrEq(dotText, true) else dotText))
    )

  textArea(
    cls         := "textarea textarea-xs min-h-[2rem] h-auto w-full p-1",
    placeholder := row.placeholder,
    value <-- rawText.signal,
    onInput.mapToValue(_.debounce(300)) --> rawText.set,
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
