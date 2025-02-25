package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.{HTMLAnchorElement, window}
import com.raquo.laminar.nodes.ReactiveHtmlElement

case class MenuEntry(
    title:    String,
    shortcut: Option[String],
    action:   Modifier[ReactiveHtmlElement[HTMLAnchorElement]]
)

case class MenuSection(
    title:   String,
    entries: List[MenuEntry]
)

def SelectionSidebar(state: ViewerState) =
  import state.eventHandlers.*

  val menuSections = List(
    MenuSection(
      "selection",
      List(
        MenuEntry("Hide", Some("h"), onMouseDown.hideSelectedNodes),
        MenuEntry("Hide others", Some("Shift+h"), onMouseDown.hideNonSelectedNodes),
        MenuEntry("Add node", Some("n"), onMouseDown --> state.addNode()),
        MenuEntry("Delete", Some("Del"), onMouseDown.deleteSelectedNodes),
        MenuEntry("Group", Some("g"), onMouseDown.groupSelectedNodes),
        MenuEntry("Clear selection", Some("Esc"), onMouseDown.clearSelection),
        MenuEntry("Copy as SVG", None, onMouseDown.copySelectionAsSVG(window.navigator.clipboard.writeText))
      )
    ),
    MenuSection(
      "successors",
      List(
        MenuEntry("Show all successors", None, onMouseDown.showAllSuccessors),
        MenuEntry("Show direct successors", None, onMouseDown.showDirectSuccessors),
        MenuEntry("Select all successors", None, onMouseDown.selectSuccessors),
        MenuEntry("Select direct successors", None, onMouseDown.selectDirectSuccessors)
      )
    ),
    MenuSection(
      "predecessors",
      List(
        MenuEntry("Show all predecessors", None, onMouseDown.showAllPredecessors),
        MenuEntry("Show direct predecessors", None, onMouseDown.showDirectPredecessors),
        MenuEntry("Select all predecessors", None, onMouseDown.selectPredecessors),
        MenuEntry("Select direct predecessors", None, onMouseDown.selectDirectPredecessors)
      )
    )
  )

  val searchTerm = Var("")
  val searchHasFocus = Var(false)
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
        typ         := "search",
        cls         := "input input-bordered input-xs w-full px-2",
        placeholder := "Enter command...",
        onFocus.mapTo(true) --> searchHasFocus,
        onBlur.mapTo(false) --> searchHasFocus,
        onInput.mapToValue --> searchTerm
      ),
      // Restore when the functionality is implemented
//      kbd(cls := "kbd kbd-sm opacity-60", "⌘"),
//      kbd(cls := "kbd kbd-sm opacity-60", "K")
    ),
    // menu container
    div(
      idAttr := "selection-sidebar-menu-container",
      display <-- menuShouldBeVisible.map(if _ then "block" else "none"),
      ul(
        cls := "menu menu-sm rounded-box bg-transparent",
        children <-- searchTerm.signal.map: term =>
          for
            section <- menuSections
            titleElement = li(cls := "menu-title", h1(section.title), hr())
            entries = section.entries
              .filter(e => e.title.toLowerCase.contains(term.toLowerCase))
              .map: entry =>
                li(a(
                  cls := "flex justify-between",
                  span(entry.title),
                  entry.shortcut.map(s => kbd(cls := "kbd kbd-sm opacity-60", s)),
                  entry.action
                ))
            entry <- if term.isEmpty || entries.nonEmpty then titleElement +: entries else Nil
          yield entry
      )
    )
  )
