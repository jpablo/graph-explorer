package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

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
              cls := "text-lg font-bold flex-1",
              "Library"
            ),
            button(
              cls   := "btn btn-ghost btn-xs",
              title := "Create Diagram",
              span().plusCircleIcon,
              onClick --> commands.routerCmds.createProject.execute()
            )
          )
        ),

        // Projects list - now takes remaining height
        div(
          cls := "grow overflow-y-auto",
          ul(
            cls := "menu menu-sm w-full",
            children <-- ProjectStorage.directory.map { directory =>
              directory.projects.sortBy(-_.createdAt).map { project =>
                li(
                  a(
                    cls := "flex items-center gap-2",
                    cls("menu-active") <-- state.project.signal.map(_.id == project.id),
                    div(cls := "truncate", project.name),
                    onClick --> router.navigateTo(Route.ProjectDetail(project.id.value))
                  )
                )
              }
            }
          )
        )
      )
    )
  )
