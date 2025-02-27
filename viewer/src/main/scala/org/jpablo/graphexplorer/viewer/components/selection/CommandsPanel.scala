package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.models.NodeId
import com.raquo.laminar.api.features.unitArrows

def CommandsPanel(state: ViewerState, commands: Commands) =
  import state.owner

  val searchTerm = Var("")
  val searchHasFocus = Var(false)
  val searchInputElement = Var[Option[dom.HTMLInputElement]](None)

  // Global key handler for Cmd+K
  documentEvents(_.onKeyDown)
    .filter(e => e.key.toLowerCase == "k" && e.metaKey)
    .foreach { e =>
      e.preventDefault()
      searchInputElement.now().foreach(_.focus())
    }

  val menuShouldBeVisible =
    Signal.combine(
      state.diagramSelection.signal.map(_.nonEmpty),
      searchHasFocus.signal,
      state.leftPanelVisible.signal
    ).map(_ || _ || _)

  div(
    idAttr := "selection-sidebar",
    // Dynamic styling based on leftPanelVisible
    cls <-- state.leftPanelVisible.signal.map(visible =>
      if visible then "bg-base-100/90 rounded-box border"
      else "shadow-md bg-base-100/90 rounded-box border border-base-300"
    ),
    // Search box at the top with consistent styling
    label(
      cls := "flex items-center gap-1",
      input(
        onMountCallback(ctx => searchInputElement.set(Some(ctx.thisNode.ref))),
        onUnmountCallback(_ => searchInputElement.set(None)),
        typ         := "search",
        cls         := "input input-bordered input-xs w-full px-2",
        placeholder := "Enter command...",
        onFocus.mapTo(true) --> searchHasFocus,
        onBlur.mapTo(false) --> searchHasFocus,
        onInput.mapToValue --> searchTerm
      ),
      kbd(cls := "kbd kbd-sm opacity-60", "⌘"),
      kbd(cls := "kbd kbd-sm opacity-60", "K")
    ),
    // menu container
    div(
      idAttr := "selection-sidebar-menu-container",
      display <-- menuShouldBeVisible.map(if _ then "block" else "none"),
      ul(
        cls := "menu menu-sm rounded-box bg-transparent",
        children <-- searchTerm.signal.combineWith(state.diagramSelection.signal).map: (term, selection: Set[NodeId]) =>
          for
            (title, commands) <- commands.menuSections.toSeq
            titleElement = li(cls := "menu-title", h1(title), hr())
            rows =
              for
                cmd <- commands
                if cmd.title.toLowerCase.contains(term.toLowerCase) && cmd.isVisible(selection)
              yield li(
                a(
                  cls := "flex justify-between",
                  span(cmd.title),
                  cmd.shortcut.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)),
                  onClick --> cmd.action()
                )
              )
            row <- if rows.nonEmpty then titleElement +: rows else Nil
          yield row
      )
    )
  )
