package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.tiny

def LeftPanel(state: ViewerState, router: Router) =
  div(
    cls := "relative", // Container for absolute positioning

    // Toggle button (always visible)
    button(
      cls   := "btn btn-ghost absolute top-4 left-2 z-20",
      title := "Toggle Library",
      cls("btn-active") <-- state.leftPanelVisible,
      i(cls := "bi bi-layout-sidebar"),
      onClick --> { _ => state.leftPanelVisible.update(!_) }
    ).tiny,

    // Panel content
    div(
      idAttr := "left-panel",
      // cls := "bg-base-100 z-10 flex-shrink-0 h-full flex flex-col overflow-hidden print:hidden border-r border-base-300 transition-all duration-200",
      cls <-- state.leftPanelVisible.signal.map(if _ then "w-[16rem] p-2 gap-3 opacity-100 visible"
      else "w-0 p-0 gap-0 opacity-0 invisible"),
      styleAttr <-- state.leftPanelVisible.signal.map(visible => 
        if visible then "--left-panel-width: 16rem;" // 16rem = w-64
        else "--left-panel-width: 0px;"
      ),

      // Header section with margin-top to accommodate selection sidebar
      div(
        cls := "header-section",
        div(
          cls := "flex items-center gap-2 border-b border-base-300",
          div(
            cls := "flex items-center gap-2 flex-grow overflow-hidden", // Add padding to account for the button
            h2(
              cls := "text-lg font-bold",
              "Library"
            )
          )
        ),

        // Projects list
        div(
          cls := "flex-grow overflow-y-auto",
          table(
            cls := "table table-xs",
            tbody(
              children <-- ProjectStorage.directory.map { directory =>
                directory.projects.sortBy(-_.createdAt).map { project =>
                  tr(
                    cls := "flex items-center hover:bg-base-200 cursor-pointer rounded-lg transition-colors group relative",
                    cls("bg-primary/10") <-- state.project.signal.map(_.id == project.id),
                    td(
                      cls := "flex items-center flex-grow overflow-hidden",
                      div(cls := "truncate", project.name),
                    ),
                    onClick --> { _ => router.navigateTo(Route.ProjectDetail(project.id.value)) }
                  )
                }
              }
            )
          )
        )
      )
    )
  )
