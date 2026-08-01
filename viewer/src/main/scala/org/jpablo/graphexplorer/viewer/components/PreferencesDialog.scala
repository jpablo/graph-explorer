package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{SimpleDialog, Toggle}

/** User preferences, gathered off the toolbar. A theme is a preference, not a
  * document action — reference apps (Excalidraw, tldraw, Linear) all keep it
  * behind a settings surface, and the toolbar research doc makes the argument.
  *
  * Stated in terms of the individual settings rather than a [[ViewerState]], so
  * the library page — which has no ViewerState, only the persisted
  * `ViewerSettings` Var — can mount the SAME dialog instead of scattering a
  * theme picker across the chrome. Both spellings write through Vars that
  * already persist, so neither page has to know how the other saves.
  */
def PreferencesDialog(
    open:                     Var[Boolean],
    currentTheme:             Signal[Option[String]],
    onSelectTheme:            String => Unit,
    promptLabelBeforeNewNode: Var[Boolean]
): HtmlElement =
  SimpleDialog(
    open,
    // On the modal box itself: room for the rows, and overflow-visible so the
    // theme picker's menu can drop past the box edge instead of being clipped
    // by the modal's default overflow-y auto.
    cls := "min-w-[26rem] overflow-visible",
    div(
      cls := "space-y-4",
      // `showModal()` focuses the first focusable descendant unless something
      // carries `autofocus` — and that first descendant is the theme dropdown's
      // trigger, which daisyUI opens on `:focus-within`. So the dialog used to
      // open with the theme menu already down, covering the row beneath it.
      // Claiming focus for the heading fixes it here WITHOUT touching Dialog:
      // the label and rename dialogs depend on that same first-focusable rule to
      // put the caret in their text input, so moving focus for everyone would
      // break opening one and typing straight into it.
      h3(cls := "text-lg font-semibold", tabIndex := -1, autoFocus := true, "Preferences"),
      div(
        cls := "flex items-center justify-between gap-6",
        span(cls := "text-sm", "Theme"),
        ThemeSelect(currentTheme, onSelectTheme)
      ),
      label(
        cls := "flex items-center justify-between gap-6 cursor-pointer",
        span(cls := "text-sm", "Ask for a label when creating a node"),
        Toggle(promptLabelBeforeNewNode)
      )
    )
  )

/** The detail page's spelling: its settings live on the ViewerState, which
  * persists them through ViewerSettings all the same.
  */
def PreferencesDialog(state: ViewerState): HtmlElement =
  PreferencesDialog(
    open = state.preferencesDialogOpen,
    currentTheme = state.currentTheme.signal,
    onSelectTheme = theme => state.currentTheme.set(Some(theme)),
    promptLabelBeforeNewNode = state.promptLabelBeforeNewNode
  )
