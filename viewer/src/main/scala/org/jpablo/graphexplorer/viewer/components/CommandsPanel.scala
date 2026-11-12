package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{InputBox, InputVariant}
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.{Selection, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{Platform, intersperse}
import org.scalajs.dom.KeyValue

import scala.scalajs.js

/** The ⌘K palette.
  *
  * @param isMac true on an Apple platform. It selects the command key of the
  *              palette shortcut (`Platform.commandKey`). A test can give
  *              either platform.
  */
def CommandsPanel(state: ViewerState, commands: Commands, isMac: Boolean = Platform.isMac) =
  val searchTerm     = Var("")
  val searchHasFocus = Var(false)
  val focusSearch    = EventBus[Boolean]()

  // Simple index for keyboard navigation
  val highlightedIndex = Var(-1)
  val scrollDown       = Var(true)

  def shouldShowCommand(term: String, selection: Selection)(cmd: Command[?]) =
    cmd.shortLabel.toLowerCase.contains(term.toLowerCase) && cmd.isVisible(selection)

  /** The sections that have a visible command, in the order of `byHeader`. */
  def visibleSections(term: String, selection: Selection): Seq[(String, List[Command[?]])] =
    commands.byHeader.toSeq
      .map((title, cmds) => title -> cmds.filter(shouldShowCommand(term, selection)))
      .filter((_, cmds) => cmds.nonEmpty)

  /** The visible sections now. The rows and the keys read this one value.
    * The index of a command is its position in the flat list of these
    * sections: the highlight shows on the row at that index, and Enter runs
    * the command at that index. A second computation in the key handler could
    * disagree with the rows, because some commands read more state than the
    * term and the selection (for example the selected record cell).
    */
  val sections: Signal[Seq[(String, List[Command[?]])]] =
    searchTerm.signal.combineWithFn(state.selection.signal)(visibleSections)

  def commandRow(cmd: Command[?], cmdIndex: Int): LI =
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

  val rows: Signal[Seq[LI]] =
    sections.map: sections =>
      // The index of the first row of each section. A command in two sections
      // has two rows, and each row has its own index. Before, a row found its
      // index by its label, so each copy had the index of the first copy.
      val firstIndexes = sections.scanLeft(0)((index, section) => index + section._2.size)
      sections.zip(firstIndexes).flatMap:
        case ((cmdTitle, visibleCmds), first) =>
          li(cls := "menu-title", h1(cmdTitle), hr()) +:
            visibleCmds.zipWithIndex.map((cmd, i) => commandRow(cmd, first + i))

  div(
    cls := "dropdown",
    // Mount-scoped bindings (previously owner-bound foreach subscriptions that
    // outlived the panel — including a document-level Cmd+K listener leaked per visit):
    // reset the highlighted index when the search term or selection changes,
    searchTerm.signal --> (_ => highlightedIndex.set(-1)),
    state.selection.signal --> (_ => highlightedIndex.set(-1)),
    // and focus the palette on the command key of the platform + K while
    // this panel is mounted: ⌘K on a Mac, Ctrl+K on Windows and Linux. On a
    // Mac, Ctrl+K stays with the focused field, where it deletes to the end
    // of the line. A key with Shift or Alt also stays: Shift+⌘K deletes the
    // line in CodeMirror.
    documentEvents(_.onKeyDown).filter(e =>
      e.key.toLowerCase == "k" && !e.shiftKey && !e.altKey && Platform.commandKey(e.metaKey, e.ctrlKey, isMac)
    ) --> { e =>
      e.preventDefault()
      focusSearch.emit(true)
    },
    // The palette's front door, shaped like what it is: a search box. Promoted
    // from an anonymous "Command.." stub per the top-bar study — ⌘K is the
    // intended home for every occasional action, so it has to read as one.
    InputBox(
      InputVariant.xs,
      cls := "px-1.5 w-44 transition-all duration-200 ease-in-out mt-[-3px] no-outline",
      i(cls := "bi bi-search opacity-40 text-xs"),
      inContext { thisNode =>
        input(
          typ         := "search",
          cls         := "grow",
          placeholder := "Search commands…",
          onFocus.mapTo(true) --> searchHasFocus,
          onBlur.mapTo(false) --> searchHasFocus,
          onFocus --> thisNode.ref.classList.add("w-60"),
          onBlur --> thisNode.ref.classList.remove("w-60"),
          onInput.mapToValue --> searchTerm,
          focus <-- focusSearch.events,
          // Handle keyboard navigation directly in the input
          onKeyDown.compose(_.withCurrentValueOf(sections)) --> { (e, sections) =>
            val visibleCmds = sections.flatMap(_._2)
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
      kbd(cls := "kbd kbd-xs opacity-60 mr-[-5px]", if isMac then "⌘" else "Ctrl"),
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
