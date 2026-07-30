package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{Dropdown, MenuEntry}
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.MenuOption

/** The daisyUI themes offered by the theme selector. Shared by the detail
  * toolbar and the library navbar, so the two lists cannot drift.
  *
  * The first block are the app-native themes (style.css defines them via
  * `@plugin "daisyui/theme"`, style.scss carries their beyond-token
  * treatments); the rest are daisyUI's built-ins.
  */
val daisyThemes = Seq(
  "light",
  "dark",
  "workbench",
  "drafting",
  "signal",
  "abyss",
  "acid",
  "aqua",
  "autumn",
  "black",
  "bumblebee",
  "business",
  "caramellatte",
  "cmyk",
  "coffee",
  "corporate",
  "cupcake",
  "cyberpunk",
  "dim",
  "dracula",
  "emerald",
  "fantasy",
  "forest",
  "garden",
  "halloween",
  "lemonade",
  "lofi",
  "luxury",
  "night",
  "nord",
  "pastel",
  "retro",
  "silk",
  "sunset",
  "synthwave",
  "valentine",
  "winter",
  "wireframe"
)

/** A theme's palette at a glance: a chip painted in the theme's OWN tokens.
  * daisyUI scopes `data-theme` per element, so the chip's base-100 background
  * (the light/dark tell) and its content/primary/secondary/accent bars are read
  * from the real theme — nothing is hardcoded, and a new custom theme gets an
  * accurate swatch for free.
  */
private def themeSwatch(theme: String): HtmlElement =
  span(
    dataAttr("theme") := theme,
    cls := "flex items-center gap-0.5 rounded-selector border border-base-300 bg-base-100 p-0.5 shrink-0",
    span(cls := "w-1 h-3 rounded-full bg-base-content"),
    span(cls := "w-1 h-3 rounded-full bg-primary"),
    span(cls := "w-1 h-3 rounded-full bg-secondary"),
    span(cls := "w-1 h-3 rounded-full bg-accent")
  )

private def themeRow(theme: String, current: Signal[Option[String]]): HtmlElement =
  span(
    cls := "flex items-center gap-2 w-full",
    themeSwatch(theme),
    span(theme),
    span(
      cls := "ml-auto",
      child.maybe <-- current.map(c => Option.when(c.getOrElse("light") == theme)(i(cls := "bi bi-check-lg")))
    )
  )

/** The theme dropdown, identical on every page that offers it. Each row (and
  * the trigger) carries a swatch chip so a theme's darkness and palette are
  * visible before committing to it. Ghost, like the editor's language select: a
  * theme is a preference you set once, so it should not draw a box around
  * itself in a bar full of actions.
  */
def ThemeSelect(current: Signal[Option[String]], onSelect: String => Unit) =
  val currentName = current.map(_.getOrElse("light"))
  Dropdown(
    title = span(
      cls := "flex items-center gap-1.5 font-normal",
      child <-- currentName.map(themeSwatch),
      span(child.text <-- currentName)
    ),
    options = Signal.fromValue(
      daisyThemes.map[MenuEntry[String]](theme =>
        MenuOption(
          elem = themeRow(theme, current),
          value = theme,
          description = Some(s"Switch to the $theme theme"),
          shortcut = None
        )
      )
    ),
    // The menu stays open on selection ON PURPOSE: themes are something you
    // audition, and closing after each pick turns comparison into re-opening
    // drudgery. daisyUI's focus-within keeps it up until a click lands outside.
    onClickHandler = ep => ep --> { theme => onSelect(theme) },
    menuCls = "w-48 max-h-96 overflow-y-auto flex-nowrap"
  ).amend(cls := "dropdown-end theme-select")
