package org.jpablo.graphexplorer.viewer.widgets

import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrEq, AttrValue}
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, RowOption, toRawText}
import org.jpablo.graphexplorer.viewer.domUtils.autocomplete
import org.jpablo.graphexplorer.viewer.color.ColorFormat
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.widgets
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom
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
    placeholderText: Option[String],
    options:         Seq[(String, String)],
    mods:            Mods*
) =
  select(
    cls := "select select-xs",
    placeholderText.map(option(_, disabled := true, selected := true)),
    options.map((name, id) => option(name, value := id)),
    mods
  )

/** Bare daisyUI select for call sites that build their own `option` children
  * ([[Select]] above is the (name, id)-pairs convenience). */
def SelectBox(mods: Mods*) =
  select(cls := "select", mods)

/** The select family's variant tokens — the one spelling, per WidgetPolicySpec.
  * Call sites compose these instead of writing `select-*` strings. */
object SelectVariant:
  val xs: Mods    = cls := "select-xs"
  val sm: Mods    = cls := "select-sm"
  val ghost: Mods = cls := "select-ghost"

/** The input family's variant tokens (see [[SelectVariant]]). */
object InputVariant:
  val xs: Mods    = cls := "input-xs"
  val sm: Mods    = cls := "input-sm"
  val ghost: Mods = cls := "input-ghost"

/** daisyUI's boxed input: a `label.input` that lays out icons/affixes around a
  * `grow` inner input (the library search, the command palette). */
def InputBox(mods: Mods*) =
  label(cls := "input", mods)

/** daisyUI range slider (the 3D layout knobs). The caller wires bounds,
  * step and binding; layout classes compose via mods. */
def RangeSlider(mods: Mods*) =
  input(tpe := "range", cls := "range range-xs", mods)

/** daisyUI toggle bound to a Boolean Var (the preferences switches). */
def Toggle(flag: Var[Boolean], mods: Mods*) =
  input(
    tpe := "checkbox",
    cls := "toggle toggle-sm",
    checked <-- flag,
    onChange.mapToChecked --> flag,
    mods
  )

/** The label dialogs' multi-line input (the v5 default border applies). */
def DialogTextArea(mods: Mods*) =
  textArea(cls := "textarea w-full", mods)

/** Single-line dialog input (the rename dialog). */
def DialogInput(mods: Mods*) =
  input(tpe := "text", cls := "input w-full", mods)

/** Class-driven daisyUI swap: the two icons cross-fade with a rotate as `active`
  * flips — no checkbox nested inside the caller's button, which the input-driven
  * swap idiom would require. */
def SwapIcon(active: Signal[Boolean], onIcon: String, offIcon: String) =
  span(
    cls := "swap swap-rotate",
    cls("swap-active") <-- active,
    span(cls := s"swap-on $onIcon"),
    span(cls := s"swap-off $offIcon")
  )

/** daisyUI `filter`: one radio chip per option, `None` = unfiltered; the
  * component's reset button restores All. Options are enumerated by the caller,
  * so a new value gets a chip without touching this widget. */
def FilterChips[A](
    groupName:  String,
    options:    Seq[A],
    labelOf:    A => String,
    selected:   Var[Option[A]],
    resetTitle: String = "All"
)(using CanEqual[A, A]) =
  form(
    cls := "filter flex-nowrap",
    input(
      cls   := "btn btn-sm btn-square",
      tpe   := "reset",
      value := "×",
      title := resetTitle,
      onClick --> (_ => selected.set(None))
    ),
    options.map: opt =>
      input(
        cls      := "btn btn-sm",
        tpe      := "radio",
        nameAttr := groupName,
        org.jpablo.graphexplorer.viewer.domUtils.ariaLabel := labelOf(opt),
        checked <-- selected.signal.map(_.contains(opt)),
        onChange.mapTo(Some(opt)) --> selected
      )
  )

/** A joined run of icon buttons acting as a radio group: exactly one is pressed
  * at a time, and pressing it again changes nothing. For mode switchers (the
  * library's cards/rows toggle) where the vocabulary is icons, not words —
  * unlike [[FilterChips]] there is no "none of these" state to reset to.
  */
def IconRadioGroup[A](
    options:  Seq[(A, String, String)], // (value, bootstrap icon, accessible label)
    selected: Var[A]
)(using CanEqual[A, A]) =
  div(
    cls := "join",
    options.map: (value, icon, label) =>
      button(
        typ   := "button",
        cls   := "join-item btn btn-sm btn-square",
        title := label,
        org.jpablo.graphexplorer.viewer.domUtils.ariaLabel := label,
        cls("btn-active") <-- selected.signal.map(_ == value),
        i(cls := icon),
        onClick --> (_ => selected.set(value))
      )
  )

def Menu[A](
    options:        Signal[Seq[MenuEntry[A]]],
    onClickHandler: EventProcessor[MouseEvent, A] => Modifier[Anchor]
) =
  ul(
    tabIndex := 0,
    cls      := "menu menu-xs bg-base-100 rounded-box z-1 shadow-lg p-1 border border-base-300",
    children <-- options.map: opts =>
      for
        entry <- opts
      yield
        entry match
          case MenuOption(elem, value, description, shortcut) =>
            val nameMod = elem match
              case m: Modifier.Base => m
              case s: String        => span(s)
            li(
              a(
                // Menu rows take the theme's control radius, like every other
                // interactive row in the chrome.
                cls := "rounded-field",
                cls := "flex justify-between",
                title.maybe(description),
                nameMod,
                shortcut.map(_.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)).intersperse(span(" + "))),
                onClickHandler(onClick.mapTo(value))
              )
            )

          case Sep => li()
  )

def popupCardMenuButton(row: InputAttribute, rowOption: RowOption) =
  div(
    cls := "p-1",
    cls("bg-base-200") <-- row.isSelected(rowOption),
    title := rowOption.name,
    rowOption.elem.fold(span(rowOption.name))(elem => elem()),
    onClick.mapTo(rowOption.value) --> row.inputVar
  )

def PopupCard(row: InputAttribute, options: Seq[AttributeRow.RowOption], cardClass: Option[String] = None) =
  div(
    tabIndex := 0,
    cls      := "dropdown-content card card-xs popup-card",
    // extra style
    cardClass.map(cc => cls := cc),
    div(
      cls := "card-body p-0",
      ul(
        tabIndex := 0,
        cls      := "p-0 m-0 menu menu-horizontal [&:before]:hidden",
        for option <- options yield li(popupCardMenuButton(row, option))
      )
    )
  )

/** A menu with a horizontal layout, with the last item being a dropdown menu.
  */
def MenuWithExtraDropdown(row: InputAttribute, initial: Int, dir: MenuDirection, cardClass: Option[String] = None) =
  val initialOptions = row.options.take(initial)
  val extraOptions   = row.options.drop(initial)
  ul(
    tabIndex               := 0,
    cls                    := s"menu-with-extra-dropdown menu menu-horizontal bg-base-100 rounded-box p-0",
    cls("justify-between") := extraOptions.nonEmpty,
    for option <- initialOptions yield li(popupCardMenuButton(row, option)),
    li(
      cls := "justify-center",
      // ----- dropdown -----
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
            PopupCard(row, extraOptions, cardClass)
          )
      )
    )
  )

def DropdownWithCurrentValue(row: InputAttribute, dir: MenuDirection, cardClass: Option[String] = None, open: Boolean = false) =
  div(
    cls := s"menu dropdown dropdown-bottom p-0 m-0",
    cls("dropdown-open") := open,
    // TailwindCSS classes seem to have issues with dynamic strings, so we add the cases we need here.
    cls("dropdown-start") := dir == MenuDirection.start,
    cls("dropdown-end")   := dir == MenuDirection.end,
    if row.options.isEmpty then emptyMod
    else
      Seq(
        // current value button
        div(
          tabIndex := 0,
          role     := "button",
          cls      := "btn btn-ghost btn-xs p-1 ml-1",
          row.triggerGlyph match
            // Identity trigger (the Docs/Canva idiom): a glyph naming WHAT the row
            // colors, over a bar showing the CURRENT color — legible without hover.
            // The menu keeps its plain swatch grid; only the trigger needs identity.
            case Some(glyph) =>
              div(
                cls := "gx-color-trigger",
                glyph(),
                div(
                  cls := "gx-color-bar",
                  cls("gx-color-bar-none") <-- row.combineDefaultString.map(v => v == "none" || v.isEmpty),
                  styleAttr <-- row.combineDefaultString.map { v =>
                    if v == "none" || v.isEmpty then ""
                    else
                      scala.util
                        .Try(s"background-color: ${ColorFormat.toHex(ColorFormat.fromString(v)).value}")
                        .getOrElse("")
                  }
                )
              )
            case None => child <-- row.selectedOption
        ),
        PopupCard(row, row.options, cardClass)
      )
  )

def DropdownForRow(row: InputAttribute) =
  Dropdown(
    // Names the TRIGGER. The row's caption beside it is a sibling div rather than a
    // `<label>` (see AttributesViewRow), and the element this returns is a role-less
    // wrapper, so a name left out there reaches nothing — the button announces as
    // "button", with the attribute it edits nowhere in its name.
    title = aria.label := row.label,
    options = Signal.fromValue(
      row.options.map(o =>
        MenuOption(
          elem = o.elem.getOrElse(() => span(o.name))(),
          value = o.value,
          description = Some(o.name),
          shortcut = None
        )
      )
    ),
    onClickHandler = _ --> (attrValue => row.inputVar.set(attrValue)),
    icon = child <-- row.selectedOption,
    menuCls = "items-center"
  ).amend(cls := "dropdown-center")

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
      cls              := "btn whitespace-nowrap btn-ghost p-1 ml-1",
      cls("join-item") := join,
      title,
      icon
    ).tiny,
    // The panel has to be focusable, or it closes the moment you touch anything in it that
    // is not itself focusable -- a row label, a card's padding, the gap between controls.
    // daisyUI opens on `:focus-within`, so "open" means focus is somewhere in this subtree;
    // a click on an inert child hands focus to <body> and the panel disappears mid-gesture.
    // PopupCard already did this; DropdownHeader was the one that did not.
    body.amend(cls := "dropdown-content", tabIndex := 0)
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

def isHtml(s: String) = org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels.isHtml(s)

def TextAreaWithValue(
    row:      InputAttribute,
    default:  String = "",
    setFocus: Boolean = false
) =

  val rawText = toRawText(row.inputVar, default)

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

/** "This attribute is set, and this puts it back." The ONE marker for undoing a single
  * attribute, wherever an attribute is edited: a bare control in the toolbar, a row in a
  * popup card, a row in the side panel.
  *
  * There used to be two — this dot on toolbar controls and a small `×` in every panel and
  * card row — which left `×` meaning both "reset this one" and, at the end of the bar and
  * three sizes larger, "reset ALL of them". One symbol for a one-attribute undo and a
  * wipe-everything is the wrong thing to be ambiguous about, so the dot took the singular
  * job and `×` keeps only the plural one.
  *
  * A dot rather than a glyph because it does two things at once: it REPORTS that the
  * attribute differs from its default (the row's bold label says the same thing, but a
  * bare toolbar control has no label to bolden), and it undoes it. Sizing follows from
  * that: 8px of ink, because a status light that shouts is a status light you stop
  * reading — but a 16px target around it, because 8px is not a thing anyone can hit.
  */
def ResetMarker(row: InputAttribute): Button =
  button(
    cls        := "attr-changed",
    typ        := "button",
    title      := s"Reset ${row.label}",
    aria.label := s"Reset ${row.label}",
    // stopPropagation: in the toolbar this sits INSIDE the control it resets, and a click
    // that reached the control behind it would open the dropdown it belongs to.
    onClick.stopPropagation --> { (ev: MouseEvent) =>
      // The marker exists only while the row is changed, so resetting deletes the very
      // element the click just focused and focus lands on <body>. Inside a dropdown that
      // shuts the card mid-edit: daisyUI keeps a panel open on `:focus-within`, so undoing
      // one attribute took the panel with it.
      //
      // The panel is focusable for exactly this reason (see DropdownHeader) — but it has to
      // be focused BEFORE the write, not after. A closed panel is `display: none`, and a
      // display:none element cannot take focus; wait until the marker is gone and the panel
      // is already closed, and there is nothing left to focus. Move first, then write.
      // Outside a dropdown `closest` finds nothing and this is a no-op.
      Option(ev.currentTarget.asInstanceOf[dom.Element].closest(".dropdown-content"))
        .foreach(_.asInstanceOf[dom.HTMLElement].focus())
      row.inputVar.set(Missing)
    }
  )

def InputLabelWithResetButton(row: InputAttribute): Div =
  val multipleValues = row.inputVar.signal.map(_ == Multiple)
  div(
    cls := "flex items-center justify-start text-nowrap",
    // The label column is fixed width, so a long name truncates — hover has to give it
    // back. It also names the DOT attribute behind the row: the panel deliberately uses
    // plainer words than Graphviz does, and someone who came here knowing `concentrate`
    // still needs a way to find it.
    div(title := s"${row.label} — ${row.attrId}", cls("font-bold") <-- row.isChanged, row.label),
    div(
      cls("w-6 flex items-center justify-center") <-- multipleValues.combineWithFn(row.isChanged)(_ || _),
      child(span(title := s"Multiple values", i(cls := "bi bi-exclamation-triangle text-warning"))) <-- multipleValues,
      child(ResetMarker(row)) <-- row.isChanged
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
  // daisyUI 5: a `label.label` wraps the text and the control directly — the
  // `form-control` wrapper div and the `label-text` class died with v4 (both
  // were dead CSS here since the v5 upgrade).
  label(
    forId := id,
    cls   := "label cursor-pointer",
    span(cls := "pr-1", labelStr),
    input(
      idAttr       := id,
      autocomplete := "off",
      tpe          := InputType.checkbox.toString,
      disabled <-- isDisabled,
      cls := (if toggle then "toggle toggle-xs" else "checkbox checkbox-xs"),
      controlled(checked <-- isChecked, onClick.mapToChecked --> isChecked)
    )
  )
