package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.{Command, Commands}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.models.NodeId
import scala.scalajs.js

def CommandsPanel(state: ViewerState, commands: Commands) =
  import state.owner

  val searchTerm = Var("")
  val searchHasFocus = Var(false)
  val focusSearch = EventBus[Boolean]()

  // Simple index for keyboard navigation
  val highlightedIndex = Var(-1)
  val scrollDown = Var(true)

  // Reset highlighted index when search term changes
  searchTerm.signal.foreach(_ => highlightedIndex.set(-1))
  // Reset highlighted index when selection changes
  state.diagramSelection.signal.foreach(_ => highlightedIndex.set(-1))

  def shouldShowCommand(term: String, selection: Set[NodeId])(cmd: Command) =
    cmd.title.toLowerCase.contains(term.toLowerCase) && cmd.isVisible(selection)

  // Global key handler for Cmd+K
  for
    e <- documentEvents(_.onKeyDown)
      .filter(e => e.key.toLowerCase == "k" && e.metaKey)
  do
    e.preventDefault()
    focusSearch.emit(true)

  val menuShouldBeVisible =
    Signal.combine(
      state.diagramSelection.signal.map(_.nonEmpty),
      searchHasFocus.signal,
      state.leftPanelVisible.signal
    ).map(_ || _ || _)

  // Create a simple flat list of all visible commands
  def getVisibleCommands(term: String, selection: Set[NodeId]): Map[String, List[Command]] =
    commands.menuSections.transform((_, cmds) => cmds.filter(shouldShowCommand(term, selection)))

  div(
    idAttr := "commands-panel",
    // Dynamic styling based on leftPanelVisible
    cls("shadow-md border border-base-300") <-- state.leftPanelVisible.signal.not,
    // Search box at the top with consistent styling
    label(
      cls := "flex items-center gap-1",
      input(
        typ         := "search",
        cls         := "input input-bordered input-xs w-full px-2",
        placeholder := "Enter command...",
        onFocus.mapTo(true) --> searchHasFocus,
        onBlur.mapTo(false) --> searchHasFocus,
        onInput.mapToValue --> searchTerm,
        focus <-- focusSearch.events,
        // Handle keyboard navigation directly in the input
        onKeyDown --> { e =>
          val term = searchTerm.now()
          val visibleCmds = getVisibleCommands(term, state.diagramSelection.now()).values.flatten.toSeq
          val cmdCount = visibleCmds.size

          if cmdCount > 0 then
            e.key match
              case "ArrowDown" =>
                e.preventDefault()
                Var.update(
                  scrollDown       -> { (_: Boolean) => true },
                  highlightedIndex -> { (idx: Int) => (idx + 1) % cmdCount }
                )
              case "ArrowUp" =>
                e.preventDefault()
                Var.update(
                  scrollDown       -> { (_: Boolean) => false },
                  highlightedIndex -> { (idx: Int) => ((idx - 1) % cmdCount + cmdCount) % cmdCount }
                )
              case "Enter" =>
                e.preventDefault()
                val idx = highlightedIndex.now()
                if idx >= 0 && idx < cmdCount then
                  visibleCmds(idx).action()
                  focusSearch.emit(false)
                  highlightedIndex.set(-1)
              case "Escape" =>
                e.preventDefault()
                focusSearch.emit(false)
                highlightedIndex.set(-1)
              case _ => ()
        }
      ),
      kbd(cls := "kbd kbd-sm opacity-60", "⌘"),
      kbd(cls := "kbd kbd-sm opacity-60", "K")
    ),
    // menu container
    div(
      idAttr := "commands-panel-menu-container",
      display <-- menuShouldBeVisible.map(if _ then "block" else "none"),
      ul(
        cls := "menu menu-sm rounded-box",
        children <-- searchTerm.signal.combineWith(state.diagramSelection.signal)
          .map: (term, selection) =>
            val allVisibleCmds = getVisibleCommands(term, selection)
            val flattenedCmds = allVisibleCmds.values.flatten.toList
            // Build the UI elements with sections
            for
              (title, visibleCmds) <- allVisibleCmds.toSeq
              // Only include section if it has visible commands
              if visibleCmds.nonEmpty
              // Create section title
              titleElement = li(cls := "menu-title", h1(title), hr())
              // Create command rows
              commandRows = visibleCmds.map { cmd =>
                // Find the index of this command in the flat list
                val cmdIndex = flattenedCmds.indexWhere(_.title == cmd.title)
                val isActive = highlightedIndex.signal.map(_ == cmdIndex)
                li(
                  a(
                    idAttr := s"cmd-${cmd.title.replace(" ", "-").toLowerCase}",
                    cls    := "flex justify-between",
                    cls("active") <-- isActive,
                    inContext { thisNode =>
                      isActive --> { isActive =>
                        if isActive then
                          thisNode.ref.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
                      }
                    },
                    span(cmd.title),
                    cmd.shortcut.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)),
                    onClick --> { _ =>
                      cmd.action()
                      focusSearch.emit(false)
                    }
                  )
                )
              }
              row <- titleElement +: commandRows
            yield row
      )
    )
  )
