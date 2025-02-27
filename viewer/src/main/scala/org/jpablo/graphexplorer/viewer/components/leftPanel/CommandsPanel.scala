package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.{Command, Commands}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom

def CommandsPanel(state: ViewerState, commands: Commands) =
  import state.owner

  val searchTerm = Var("")
  val searchHasFocus = Var(false)
  val searchInputElement = Var[Option[dom.HTMLInputElement]](None)

  // Simple index for keyboard navigation
  val highlightedIndex = Var(-1)

  // Reset highlighted index when search term changes
  searchTerm.signal.foreach(_ => highlightedIndex.set(-1))
  // Reset highlighted index when selection changes
  state.diagramSelection.signal.foreach(_ => highlightedIndex.set(-1))

  def showCmd(term: String, selection: Set[NodeId])(cmd: Command) =
    cmd.title.toLowerCase.contains(term.toLowerCase) && cmd.isVisible(selection)

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

  // Create a simple flat list of all visible commands
  def getVisibleCommands(term: String, selection: Set[NodeId]) =
    for
      (_, cmds) <- commands.menuSections.toSeq
      cmd       <- cmds
      if showCmd(term, selection)(cmd)
    yield cmd

  div(
    idAttr := "commands-panel",
    cls    := "bg-base-100/90 rounded-box",
    // Dynamic styling based on leftPanelVisible
    cls("shadow-md border border-base-300") <-- state.leftPanelVisible.signal.not,
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
        onInput.mapToValue --> searchTerm,
        // Handle keyboard navigation directly in the input
        onKeyDown --> { e =>
          val term = searchTerm.now()
          val visibleCmds = getVisibleCommands(term, state.diagramSelection.now())
          val cmdCount = visibleCmds.size

          if cmdCount > 0 then
            e.key match
              case "ArrowDown" =>
                e.preventDefault()
                highlightedIndex.update(idx => (idx + 1) min (cmdCount - 1))
              case "ArrowUp" =>
                e.preventDefault()
                highlightedIndex.update(idx => (idx - 1) max 0)
              case "Enter" =>
                e.preventDefault()
                val idx = highlightedIndex.now()
                if idx >= 0 && idx < cmdCount then
                  visibleCmds(idx).action()
                  searchInputElement.now().foreach(_.blur())
                  highlightedIndex.set(-1)
              case "Escape" =>
                e.preventDefault()
                searchInputElement.now().foreach(_.blur())
                highlightedIndex.set(-1)
              case _ => ()
        }
      ),
      kbd(cls := "kbd kbd-sm opacity-60", "⌘"),
      kbd(cls := "kbd kbd-sm opacity-60", "K")
    ),
    // menu container
    div(
      idAttr := "selection-sidebar-menu-container",
      display <-- menuShouldBeVisible.map(if _ then "block" else "none"),
      ul(
        cls := "menu menu-sm rounded-box",
        children <-- searchTerm.signal.combineWith(state.diagramSelection.signal)
          .map: (term, selection) =>
            val allVisibleCmds = getVisibleCommands(term, selection)
            // Build the UI elements with sections
            val allRows =
              for
                (title, cmds) <- commands.menuSections.toSeq
                // Filter commands in this section
                visibleCmds = cmds.filter(showCmd(term, selection))
                // Only include section if it has visible commands
                if visibleCmds.nonEmpty
                // Create section title
                titleElement = li(cls := "menu-title", h1(title), hr())
                // Create command rows
                commandRows = visibleCmds.map { cmd =>
                  // Find the index of this command in the flat list
                  val cmdIndex = allVisibleCmds.indexWhere(_.title == cmd.title)
                  li(
                    a(
                      idAttr := s"cmd-${cmd.title.replace(" ", "-").toLowerCase}",
                      cls    := "flex justify-between",
                      cls("active") <-- highlightedIndex.signal.map(_ == cmdIndex),
                      span(cmd.title),
                      cmd.shortcut.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)),
                      onClick --> { _ =>
                        cmd.action()
                        searchInputElement.now().foreach(_.blur())
                      },
                      // When hovered, update the highlighted index
                      onMouseOver.mapTo(cmdIndex) --> highlightedIndex
                    )
                  )
                }
              yield titleElement +: commandRows

            allRows.flatten
      )
    )
  )
