package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.{Selection, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.intersperse
import org.scalajs.dom.KeyValue

import scala.scalajs.js

def CommandsPanel(state: ViewerState, commands: Commands) =
  import state.owner

  val searchTerm     = Var("")
  val searchHasFocus = Var(false)
  val focusSearch    = EventBus[Boolean]()

  // Simple index for keyboard navigation
  val highlightedIndex = Var(-1)
  val scrollDown       = Var(true)

  // Reset highlighted index when search term changes
  searchTerm.signal.foreach(_ => highlightedIndex.set(-1))
  // Reset highlighted index when selection changes
  state.selection.signal.foreach(_ => highlightedIndex.set(-1))

  def shouldShowCommand(term: String, selection: Selection)(cmd: Command[?]) =
    cmd.shortLabel.toLowerCase.contains(term.toLowerCase) && cmd.isVisible(selection)

  // Global key handler for Cmd+K
  for
    e <- documentEvents(_.onKeyDown)
      .filter(e => e.key.toLowerCase == "k" && e.metaKey)
  do
    e.preventDefault()
    focusSearch.emit(true)

  def getVisibleCommands(term: String, selection: Selection): Map[String, List[Command[?]]] =
    commands.byHeader.transform((_, cmds) => cmds.filter(shouldShowCommand(term, selection)))

  val rows: Signal[Seq[LI]] =
    searchTerm.signal.combineWithFn(state.selection.signal): (term, selection) =>
      val allVisibleCmds = getVisibleCommands(term, selection)
      val flattenedCmds  = allVisibleCmds.values.flatten.toList
      // Build the UI elements with sections
      for
        (cmdTitle, visibleCmds) <- allVisibleCmds.toSeq
        // Only include section if it has visible commands
        if visibleCmds.nonEmpty
        // Create section title
        titleElement = li(cls := "menu-title", h1(cmdTitle), hr())
        // Create command rows
        commandRows = visibleCmds.map { cmd =>
          // Find the index of this command in the flat list
          val cmdIndex = flattenedCmds.indexWhere(_.shortLabel == cmd.shortLabel)
          val isActive = highlightedIndex.signal.map(_ == cmdIndex)
          li(
            a(
              idAttr := s"cmd-${cmd.shortLabel.replace(" ", "-").toLowerCase}",
              cls    := "flex justify-between",
              title  := cmd.description.getOrElse(cmd.shortLabel),
              cls("menu-active") <-- isActive,
              inContext { thisNode =>
                isActive --> { isActive =>
                  if isActive then
                    thisNode.ref.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
                }
              },
              span(cmd.shortLabel),
              div(
                cmd.shortcut.map(_.toList.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)).intersperse(span(" + ")))
              ),
              onMouseDown.stopPropagation.preventDefault --> { _ =>
                cmd.execute()
                focusSearch.emit(true)
              }
            )
          )
        }
        row <- titleElement +: commandRows
      yield row

  div(
    cls := "dropdown",
    // Search box at the top with consistent styling
    label(
      cls := "input input-xs px-1 w-32 transition-all duration-200 ease-in-out mt-[-3px] no-outline",
      inContext { thisNode =>
        input(
          typ         := "search",
          cls         := "grow",
          placeholder := "Command...",
          onFocus.mapTo(true) --> searchHasFocus,
          onBlur.mapTo(false) --> searchHasFocus,
          onFocus --> thisNode.ref.classList.add("w-40"),
          onBlur --> thisNode.ref.classList.remove("w-40"),
          onInput.mapToValue --> searchTerm,
          focus <-- focusSearch.events,
          // Handle keyboard navigation directly in the input
          onKeyDown --> { e =>
            val term        = searchTerm.now()
            val visibleCmds = getVisibleCommands(term, state.selection.now()).values.flatten.toSeq
            val cmdCount    = visibleCmds.size

            if cmdCount > 0 then
              e.key match
                case KeyValue.ArrowDown =>
                  e.preventDefault()
                  Var.update(
                    scrollDown       -> { (_: Boolean) => true },
                    highlightedIndex -> { (idx: Int) => (idx + 1) % cmdCount }
                  )
                case KeyValue.ArrowUp =>
                  e.preventDefault()
                  Var.update(
                    scrollDown       -> { (_: Boolean) => false },
                    highlightedIndex -> { (idx: Int) => ((idx - 1) % cmdCount + cmdCount) % cmdCount }
                  )
                case KeyValue.Enter =>
                  e.preventDefault()
                  val idx = highlightedIndex.now()
                  if idx >= 0 && idx < cmdCount then
                    visibleCmds(idx).execute()
                    focusSearch.emit(false)
                    highlightedIndex.set(-1)
                case KeyValue.Escape =>
                  e.preventDefault()
                  focusSearch.emit(false)
                  highlightedIndex.set(-1)
                case _ => ()
          }
        )
      },
      kbd(cls := "kbd kbd-xs opacity-60 mr-[-5px]", "⌘"),
      kbd(cls := "kbd kbd-xs opacity-60", "K")
    ),

    // Dropdown content
    div(
      cls := "dropdown-content bg-base-100 rounded-box z-1 w-52 shadow-lg border-4 border-base-100",
      cls := "max-h-80 overflow-y-auto",
      ul(
        tabIndex := 0,
        cls      := "menu menu-xs p-1",
        children <-- rows
      )
    )
  )
