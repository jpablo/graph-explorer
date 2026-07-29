package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{Select, SelectVariant}

/** The daisyUI themes offered by the theme selector. Shared by the detail
  * toolbar and the library navbar, so the two lists cannot drift.
  */
val daisyThemes = Seq(
  "light",
  "dark",
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

/** The theme dropdown, identical on every page that offers it. Ghost, like the
  * editor's language select: a theme is a preference you set once, so it should
  * not draw a box around itself in a bar full of actions.
  */
def ThemeSelect(current: Signal[Option[String]], onSelect: String => Unit) =
  Select(
    placeholderText = Some("Select theme"),
    options = daisyThemes.map(theme => (theme, theme)),
    onChange.mapToValue --> { theme => onSelect(theme) },
    value <-- current.map(_.getOrElse("light")),
    SelectVariant.ghost,
    cls := "w-24 theme-select"
  )
