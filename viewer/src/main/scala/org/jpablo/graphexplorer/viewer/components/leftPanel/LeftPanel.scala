package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot.minimalGraphText
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.IconButton

/** The titles to show for the diagrams in the library. The rule is the same as on the
  * library page: the stored name, or the title that the diagram declares while it has no name.
  *
  * The key is the SET of diagram ids, not the value of the library index. Each keystroke
  * writes the index again (a new lastModified). Each name costs a payload read and a title
  * scan. Only the title of the open diagram can change while a person types, and the list
  * above reads that title directly from `state.displayTitle`.
  */
private def displayNames(state: ViewerState): Signal[Map[DiagramId, String]] =
  Library.index
    .map(_.diagrams.map(_.id))
    .distinct
    .map: ids =>
      ids.flatMap(id => Library.diagramCardInfo(id, state.languages).map(id -> _.displayName)).toMap

def LeftPanel(state: ViewerState, router: Router, commands: Commands) =
  div(
    cls := "border-r border-base-300",
    // Panel content
    div(
      idAttr := "left-panel",
      cls <-- state.leftPanelVisible.signal.map(if _ then "w-[16rem] opacity-100 visible"
      else "w-0 opacity-0 invisible"),
      styleAttr <-- state.leftPanelVisible.signal.map(visible =>
        if visible then "--left-panel-width: 16rem; --left-panel-border-width: 0px;"
        else "--left-panel-width: 0px; --left-panel-border-width: 0px;"
      ),

      // Header section with margin-top to accommodate selection sidebar
      div(
        cls := "header-section flex flex-col h-full",
        div(
          cls := "flex items-center border-b border-base-300 px-2 pb-1",
          div(
            cls := "flex items-center justify-between w-full ml-2 mt-1.5",
            h2(
              // Same weight and size as the right panel's heading: the two panels frame the
              // canvas, so they should read as a matched pair rather than two designs.
              cls := "text-lg font-semibold flex-1",
              "Library"
            ),
            IconButton("bi-plus-circle", "Create Diagram")(
              commands.routerCmds.createDiagram.execute(Some(Some(minimalGraphText)))
            )
          )
        ),

        // The list of the diagrams. It takes the rest of the height.
        div(
          cls := "grow overflow-y-auto",
          ul(
            cls := "menu menu-sm w-full",
            children <-- Library.index.combineWithFn(displayNames(state), state.displayTitle):
              (index, names, openTitle) =>
                index.diagrams.sortBy(-_.createdAt).map { diagram =>
                  val isOpen = diagram.id == state.diagramId
                  // The title of the open diagram can change while a person types, so
                  // the list reads it live. The other titles come from the snapshot of
                  // the last library change.
                  val shown =
                    if isOpen then openTitle
                    else names.getOrElse(diagram.id, diagram.name)
                  li(
                    a(
                      cls := "flex items-center gap-2",
                      // Not daisyUI's `menu-active`: that inverts the row to near-black, which
                      // shouts where the rest of the chrome marks "current" with a quiet tint.
                      cls("gx-row-active") := isOpen,
                      div(cls := "truncate", shown),
                      onClick --> router.navigateTo(Route.Diagram(diagram.id.value))
                    )
                  )
                }
          )
        )
      )
    )
  )
