package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def LeftPanel(state: ViewerState, router: Router) =
  div(
    idAttr := "left-panel",
    cls := "bg-base-100 p-2 gap-3 z-10 w-64 flex-shrink-0 h-full flex flex-col overflow-x-hidden print:hidden border-r border-base-300",
    
    // Header
    div(
      cls := "flex items-center gap-2 p-2 border-b border-base-300",
      span().folderIcon,
      h2(cls := "text-lg font-bold", "Projects")
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
        directory.projects.sortBy(-_.lastModified).map { project =>
          div(
            cls := "flex items-center gap-2 p-2 hover:bg-base-200 cursor-pointer rounded-lg transition-colors",
            cls("bg-primary/10") <-- state.project.signal.map(_.id == project.id),
            
            // Project icon and name
            div(
              cls := "flex items-center gap-2 flex-grow overflow-hidden",
              span().boxSeamIcon,
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