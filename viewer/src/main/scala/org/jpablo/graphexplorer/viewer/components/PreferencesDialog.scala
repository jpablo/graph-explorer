package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{SimpleDialog, Toggle}

/** User preferences, gathered off the toolbar. A theme is a preference, not a
  * document action — reference apps (Excalidraw, tldraw, Linear) all keep it
  * behind a settings surface, and the toolbar research doc makes the argument.
  * Settings persist through the same Vars they always did (ViewerSettings).
  */
def PreferencesDialog(state: ViewerState): HtmlElement =
  SimpleDialog(
    state.preferencesDialogOpen,
    // On the modal box itself: room for the rows, and overflow-visible so the
    // theme picker's menu can drop past the box edge instead of being clipped
    // by the modal's default overflow-y auto.
    cls := "min-w-[26rem] overflow-visible",
    div(
      cls := "space-y-4",
      h3(cls := "text-lg font-semibold", "Preferences"),
      div(
        cls := "flex items-center justify-between gap-6",
        span(cls := "text-sm", "Theme"),
        ThemeSelect(state.currentTheme.signal, theme => state.currentTheme.set(Some(theme)))
      ),
      label(
        cls := "flex items-center justify-between gap-6 cursor-pointer",
        span(cls := "text-sm", "Ask for a label when creating a node"),
        Toggle(state.promptLabelBeforeNewNode)
      )
    )
  )
