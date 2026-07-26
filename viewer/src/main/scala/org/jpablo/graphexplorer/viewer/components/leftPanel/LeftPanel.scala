package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState.minimalGraphText
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.IconButton

/** Display titles for the projects in the library, by the same rule the library page uses:
  * the stored name, or the diagram's own declared title while the project is unnamed.
  *
  * Keyed on the SET of project ids, not on the directory value: the directory is rewritten
  * on every keystroke (a lastModified bump), and each name costs a payload read plus a
  * title scan. The open project is the only one whose title can change while you type, and
  * the list above reads that one straight from `state.displayTitle`.
  */
private def displayNames(state: ViewerState): Signal[Map[ProjectId, String]] =
  ProjectStorage.directory
    .map(_.projects.map(_.id))
    .distinct
    .map: ids =>
      ids.flatMap(id => ProjectStorage.projectCardInfo(id, state.languages).map(id -> _.displayName)).toMap

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
              commands.routerCmds.createProject.execute(Some(Some(minimalGraphText)))
            )
          )
        ),

        // Projects list - now takes remaining height
        div(
          cls := "grow overflow-y-auto",
          ul(
            cls := "menu menu-sm w-full",
            children <-- ProjectStorage.directory.combineWithFn(displayNames(state), state.displayTitle, state.project.signal):
              (directory, names, openTitle, openProject) =>
                directory.projects.sortBy(-_.createdAt).map { project =>
                  // The open project's title can change as you type, so it reads live;
                  // the rest come from the snapshot taken when the library last changed.
                  val shown =
                    if project.id == openProject.id then openTitle
                    else names.getOrElse(project.id, project.name)
                  li(
                    a(
                      cls := "flex items-center gap-2",
                      // Not daisyUI's `menu-active`: that inverts the row to near-black, which
                      // shouts where the rest of the chrome marks "current" with a quiet tint.
                      cls("gx-row-active") <-- state.project.signal.map(_.id == project.id),
                      div(cls := "truncate", shown),
                      onClick --> router.navigateTo(Route.ProjectDetail(project.id.value))
                    )
                  )
                }
          )
        )
      )
    )
  )
