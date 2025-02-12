package org.jpablo.graphexplorer.projects

import org.jpablo.graphexplorer.viewer.widgets.Button
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.widgets.primary
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import com.raquo.laminar.api.features.unitArrows
import scala.compiletime.asMatchable

import scala.scalajs.js
import org.jpablo.graphexplorer.viewer.widgets.small

enum SortOption:
  case LastModified, Title, CreationDate

  def label: String = this match
    case LastModified => "Last Modified"
    case Title        => "Title"
    case CreationDate => "Creation Date"

def ProjectsDirectoryView(router: Router) =
  val sortOptionVar = Var[SortOption](SortOption.LastModified)
  val searchTermVar = Var("")

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
        cls := "navbar px-6",
        div(
          cls := "flex-1",
          h1(
            cls := "text-2xl font-bold gap-2 flex",
            span().folderIcon,
            "Projects"
          )
        ),
        div(
          cls := "flex-none gap-2",
          // Search input
          div(
            cls := "form-control",
            div(
              cls := "input-group input-group-sm",
              span(cls := "px-3", i(cls := "bi bi-search")),
              input(
                cls         := "input input-sm input-bordered w-48",
                placeholder := "Search projects...",
                controlled(
                  value <-- searchTermVar,
                  onInput.mapToValue --> searchTermVar
                )
              )
            )
          ),
          // Sort dropdown
          select(
            cls := "select select-sm select-bordered h-8",
            SortOption.values.toSeq.map { opt =>
              option(
                value := opt.toString,
                opt.label
              )
            },
            value <-- sortOptionVar.signal.map(_.toString),
            onChange.mapToValue.map(SortOption.valueOf) --> sortOptionVar
          ),
          Button(
            cls := "h-8",
            span().plusCircleIcon,
            "Create Project",
            onClick --> { _ =>
              // Add a new entry to the project directory and navigate to it
              // This will create a new project with a default name.
              val id = ProjectStorage.createProjectDirectoryEntry("Untitled")
              router.navigateTo(Route.ProjectDetail(id.value))
            }
          ).primary.small
        )
      ),

      // Projects grid with search filter
      div(
        idAttr := "projects-grid",
        cls    := "w-full grid grid-cols-[repeat(auto-fit,minmax(24rem,1fr))] gap-6 px-6",
        children <-- {
          val debouncedSearch = searchTermVar.signal.changes
            .debounce(300)
            .startWith("")

          ProjectStorage.directory
            .combineWith(debouncedSearch, sortOptionVar.signal)
            .map: (directory, searchTerm, sortOption) =>
              val filteredProjects = directory.projects.filter(_.name.toLowerCase.contains(searchTerm.toLowerCase))
              val sorted = 
                sortOption match
                  case SortOption.LastModified => filteredProjects.sortBy(-_.lastModified)
                  case SortOption.Title        => filteredProjects.sortBy(_.name.toLowerCase)
                  case SortOption.CreationDate => filteredProjects.sortBy(-_.createdAt)
              sorted.map(projectCard(router))
        }
      )
    )
  )

private def projectCard(router: Router)(project: ProjectInfo) =
  div(
    cls := "card bg-base-100 shadow-xl w-full",
    div(
      cls := "card-body p-4",

      // Header with title and delete button
      div(
        cls := "flex items-center justify-between",
        h2(
          cls := "card-title",
          a(
            href := s"#/${project.id.value}",
            cls  := "flex items-center gap-2 hover:text-primary transition-colors",
            span().boxSeamIcon,
            project.name,
            onClick.preventDefault --> router.navigateTo(Route.ProjectDetail(project.id.value))
          )
        ),
        Button(
          cls := "btn btn-xs hover:bg-warning/20 hover:text-warning transition-colors",
          i(cls := "bi bi-trash"),
          onClick --> { _ =>
            if dom.window.confirm("Are you sure you want to delete this project?") then
              ProjectStorage.deleteProject(project.id)
          }
        )
      ),

      // Preview SVG
      div(
        cls := "w-full h-48 overflow-hidden bg-base-200 rounded-lg mb-4 flex items-center justify-center",
        child <-- ProjectStorage
          .getProjectContent(project.id)
          .map(content => DotText(content).toSvg)
          .map: svgSignal =>
            div(
              cls := "w-full h-full p-4 flex items-center justify-center",
              child <-- svgSignal.map: svgElement =>
                svgElement.setAttribute("preserveAspectRatio", "xMidYMid meet")
                div(
                  cls := "w-full h-full relative",
                  div(
                    cls := "absolute inset-0 w-full h-full",
                    foreignSvgElement(svg.svg, svgElement)
                  )
                )
            )
      ),

      // Last modified and created at dates
      div(
        cls := "text-sm text-base-content/70 flex flex-col gap-1",
        div(
          cls := "flex items-center gap-1",
          span().listIcon,
          s"Last modified: ${formatDate(project.lastModified)}"
        ),
        div(
          cls := "flex items-center gap-1",
          span().fileCodeIcon,
          s"Created: ${formatDate(project.createdAt)}"
        )
      )
    )
  )

private def formatDate(timestamp: Long): String =
  val date = new js.Date(timestamp.toDouble)
  date.toLocaleDateString()
