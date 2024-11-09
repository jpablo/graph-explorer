package org.jpablo.graphexplorer.projects

import org.jpablo.graphexplorer.viewer.widgets.Button
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.navigateToProject
import org.jpablo.graphexplorer.viewer.widgets.primary
import com.raquo.laminar.api.features.unitArrows

import scala.scalajs.js

def ProjectsView() =
  div(
    idAttr := "projects-view",
    div(
      cls := "navbar bg-base-100",
      div(cls := "flex-1", a(cls := "btn btn-ghost text-xl", "Graph Explorer")),
      div(
        cls := "flex-none",
        a(
          cls    := "btn btn-xs",
          href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
          target := "_blank",
          i(cls := "bi bi-github")
        )
      )
    ),
    div(
      idAttr := "projects-body",
      // Projects navbar with background
      div(
        cls := "navbar", // Changed to bg-base-200 for subtle contrast
        div(
          cls := "flex-1",
          h1(
            cls := "text-2xl font-bold",
            "Projects"
          )
        ),
        div(
          cls := "flex-none",
          Button(
            span.plusCircleIcon,
            "Create Project",
            onClick --> { _ =>
              val id = ProjectStorage.createProject("Untitled")
              navigateToProject(id)
            }
          ).primary
        )
      ),

      // Projects grid (rest remains the same)
      div(
        idAttr := "projects-grid",
        cls    := "flex flex-wrap gap-4 p-4", // Added padding
        children <-- ProjectStorage.directory.map { dir =>
          dir.projects.sortBy(-_.lastModified).map { project =>
            projectCard(project)
          }
        }
      )
    )
  )

private def projectCard(project: ProjectInfo) =
  div(
    cls := "card bg-base-100 w-96 shadow-xl",
    div(
      cls := "card-body",

      // Header with title and delete button
      div(
        cls := "flex items-center justify-between",
        h2(
          cls := "card-title",
          a(
            href := s"#/${project.id.value}",
            cls  := "flex items-center gap-2 hover:text-primary transition-colors",
            span.folderIcon,
            project.name,
            onClick.preventDefault --> navigateToProject(project.id)
          )
        ),
        Button(
          span.closeIcon,
          onClick --> { _ =>
            if dom.window.confirm("Are you sure you want to delete this project?") then
              ProjectStorage.deleteProject(project.id)
          }
        ) // .ghost.tiny
      ),

      // Last modified
      div(
        cls := "text-sm text-base-content/70 flex items-center gap-1",
        span.listIcon,
        s"Last modified: ${formatDate(project.lastModified)}"
      )
    )
  )

private def formatDate(timestamp: Long): String =
  val date = new js.Date(timestamp.toDouble)
  date.toLocaleDateString()
