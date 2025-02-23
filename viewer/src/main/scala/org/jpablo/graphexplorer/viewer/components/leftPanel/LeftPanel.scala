package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.tiny

def LeftPanel(state: ViewerState, router: Router) =
  val isExpanded = Var(true)

  div(
    cls := "relative", // Container for absolute positioning

    // Toggle button (always visible)
    button(
      cls := "btn btn-ghost absolute top-4 left-2 z-20",
      title := "Toggle Library",
      cls("btn-active") <-- isExpanded,
      i(cls := "bi bi-layout-sidebar"),
      onClick --> { _ => isExpanded.update(!_) }
    ).tiny,

    // Panel content
    div(
      idAttr := "left-panel",
      cls := "bg-base-100 z-10 flex-shrink-0 h-full flex flex-col overflow-hidden print:hidden border-r border-base-300 transition-all duration-200",
      cls <-- isExpanded.signal.map(if _ then "w-64 p-2 gap-3 opacity-100 visible" else "w-0 p-0 gap-0 opacity-0 invisible"),

      // Header
      div(
        cls := "flex items-center gap-2 p-2 border-b border-base-300",
        div(
          cls := "flex items-center gap-2 flex-grow overflow-hidden pl-8", // Add padding to account for the button
          h2(
            cls := "text-lg font-bold",
            "Library"
          )
        )
      ),

      // Search box
      div(
        cls := "form-control",
        div(
          cls := "input-group input-group-sm",
          span(cls := "px-3", i(cls := "bi bi-search")),
          input(
            cls := "input input-sm input-bordered w-full",
            placeholder := "Search projects..."
          )
        )
      ),

      // Projects list
      div(
        cls := "flex-grow overflow-y-auto",
        children <-- ProjectStorage.directory.map { directory =>
          directory.projects.sortBy(-_.createdAt).map { project =>
            div(
              cls := "flex items-center gap-2 p-2 hover:bg-base-200 cursor-pointer rounded-lg transition-colors",
              cls("bg-primary/10") <-- state.project.signal.map(_.id == project.id),

              // Project icon and name
              div(
                cls := "flex items-center gap-2 flex-grow overflow-hidden",
                div(
                  cls := "truncate",
                  project.name
                )
              ),

              // Click handler
              onClick --> { _ =>
                router.navigateTo(Route.ProjectDetail(project.id.value))
              }
            )
          }
        }
      )
    )
  )
