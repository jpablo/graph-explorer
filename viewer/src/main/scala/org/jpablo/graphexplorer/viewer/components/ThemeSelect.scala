package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{Dropdown, MenuEntry}
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.MenuOption

/** The theme applied when nothing is stored — the app's own drafting-paper
  * direction rather than daisyUI's plain `light`. Also what a stored theme we
  * no longer offer falls back to; see [[resolveTheme]]. `style.css` marks the
  * same theme `default: true` and index.html seeds `data-theme` with it, so the
  * first paint doesn't flash a different palette before Scala.js boots.
  */
val defaultTheme = "drafting"

/** The themes offered by the theme selector. Shared by the detail toolbar and
  * the library navbar, so the two lists cannot drift.
  *
  * A curated set, not everything daisyUI ships: 35 built-ins meant a long menu
  * where most rows were a slightly different pastel, and the ones worth picking
  * were buried. What survives is one entry per visual direction —
  *
  *   - `light` / `dark`   the neutral baselines people expect to find
  *   - `workbench` / `drafting` / `signal`
  *                        app-native (style.css defines them via
  *                        `@plugin "daisyui/theme"`, style.scss carries their
  *                        beyond-token treatments: canvas surface, chrome
  *                        typeface, hard shadows)
  *   - `cupcake`          soft pastel light
  *   - `nord`             muted cool light
  *   - `retro`            warm cream light
  *   - `night`            navy dark
  *   - `synthwave`        neon dark
  *
  * Two more were cut after comparing what they actually paint rather than what
  * their names suggest: `corporate` is white-on-blue, which is `signal` almost
  * exactly (and `signal` is ours, with style.scss treatments behind it), and
  * `dracula` is a dark purple base with a pink primary, which `synthwave` does
  * more distinctly. Judge a candidate by its resolved `--color-base-100` /
  * `--color-primary`, not by its name.
  *
  * Keep this in step with the `themes:` list in style.css — daisyUI only
  * compiles CSS for the built-ins named there, so adding a row here without
  * adding it there yields a theme that selects but does not paint.
  */
val daisyThemes = Seq(
  "light",
  "dark",
  "workbench",
  "drafting",
  "signal",
  "cupcake",
  "nord",
  "retro",
  "night",
  "synthwave"
)

/** The theme to actually apply for a stored preference. A theme that has been
  * dropped from [[daisyThemes]] no longer has CSS compiled for it, so honoring
  * an old preference for one would leave the app with no palette at all —
  * every call site resolves through here instead of reading the raw Option.
  */
def resolveTheme(stored: Option[String]): String =
  stored.filter(daisyThemes.contains).getOrElse(defaultTheme)

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
      child.maybe <-- current.map(c => Option.when(resolveTheme(c) == theme)(i(cls := "bi bi-check-lg")))
    )
  )

/** The theme dropdown, identical on every page that offers it. Each row (and
  * the trigger) carries a swatch chip so a theme's darkness and palette are
  * visible before committing to it. Ghost, like the editor's language select: a
  * theme is a preference you set once, so it should not draw a box around
  * itself in a bar full of actions.
  */
def ThemeSelect(current: Signal[Option[String]], onSelect: String => Unit) =
  val currentName = current.map(resolveTheme)
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
