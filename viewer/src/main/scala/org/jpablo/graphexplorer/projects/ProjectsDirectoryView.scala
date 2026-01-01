package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.RouterCommands
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.{Button, primary, small}
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.InternalPhases
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState.minimalGraphText
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry

import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

enum SortOption derives CanEqual:
  case LastModified, Title, CreationDate

  def label: String = this match
    case LastModified => "Last Modified"
    case Title        => "Title"
    case CreationDate => "Creation Date"

def ProjectsDirectoryView(graphviz: Graphviz, router: Router, routerCmds: RouterCommands) =
  val sortOptionVar = Var[SortOption](SortOption.CreationDate)
  val searchTermVar = Var("")

  div(
    idAttr := "projects-view",
    onMountCallback: _ =>
      val t0 = Telemetry.nowMs()
      val navDtMs = Telemetry.consumeNavigationStartMs("/")
      Telemetry.log(
        "home.mount",
        "dtSinceNavMs" -> navDtMs.getOrElse(-1.0)
      )
      dom.window.requestAnimationFrame(_ => Telemetry.log("home.raf1", "dtSinceMountMs" -> (Telemetry.nowMs() - t0)))
    ,
    onUnmountCallback: _ =>
      Telemetry.log("home.unmount"),
    div(
      cls := "navbar bg-base-100",
      div(
        cls := "flex-1 flex items-center gap-2 ml-2",
        img(src := "/favicon.svg", cls := "h-6 w-6"),
        a(cls   := "btn btn-ghost text-xl pl-1", "Graph Explorer")
      ),
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
        // left side
        div(
          cls := "flex-1",
          h1(
            cls := "text-2xl font-bold gap-2 flex",
            span().folderIcon,
            "Library"
          )
        ),
        // right side
        div(
          cls := "flex flex-wrap md:flex-nowrap justify-end items-center gap-2", // Wrap on small, no-wrap on medium+
          // Search input
          label(
            cls := "input input-sm",
            i(cls := "bi bi-search"),
            input(
              cls         := "grow",
              tpe         := "search",
              placeholder := "Search library...",
              controlled(
                value <-- searchTermVar,
                onInput.mapToValue --> searchTermVar
              )
            )
          ),
          // Sort dropdown
          select(
            cls := "select select-sm h-8",
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
            "Create Diagram",
            onClick --> routerCmds.createProject.execute(Some(Some(minimalGraphText)))
          ).primary.small
        )
      ),

      // Projects grid with search filter
      div(
        idAttr := "projects-grid",
        children <-- {
          val debouncedSearch = searchTermVar.signal.changes
            .debounce(300)
            .startWith("")

          ProjectStorage.directory
            .combineWithFn(debouncedSearch, sortOptionVar.signal): (directory, searchTerm, sortOption) =>
              Telemetry.time(
                "home.projects.computeCards",
                "projectsTotal" -> directory.projects.size,
                "searchLen"     -> searchTerm.length,
                "sort"          -> sortOption.toString
              ):
                val filteredProjects = directory.projects.filter(_.name.toLowerCase.contains(searchTerm.toLowerCase))
                val sorted =
                  sortOption match
                    case SortOption.LastModified => filteredProjects.sortBy(-_.lastModified)
                    case SortOption.Title        => filteredProjects.sortBy(_.name.toLowerCase)
                    case SortOption.CreationDate => filteredProjects.sortBy(-_.createdAt)
                Telemetry.log("home.projects.cardsReady", "projectsShown" -> sorted.size)
                sorted.map(projectCard(graphviz, router))
        }
      ),

      // -------- Examples Section --------
      div(
        idAttr := "examples-grid",
        // Title
        h1(
          cls := "text-2xl font-bold gap-2 flex mb-4",
          span().folderIcon,
          "Examples"
        ),
        // Examples grid
        div(
          cls := "grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4",
          children <-- Signal.fromValue(
            DotExamples.examples
              .filterNot { case (_, example) =>
                example.path == DotExamples.emptyGraph.path || example.path == DotExamples.emptyMermaidGraph.path
              }
              .toSeq
              .map { case (name, example) =>
                exampleCard(graphviz, routerCmds, name, example)
              }
          )
        )
      )
    )
  )

private def exampleCard(
    graphviz:   Graphviz,
    routerCmds: RouterCommands,
    name:       String,
    example:    DotExamples.ExampleSource
) = {

  val previewEnabled = Var(false)
  var observerOpt: Option[js.Dynamic] = None

  def enablePreview(): Unit =
    if !previewEnabled.now() then
      Telemetry.log("home.exampleThumb.visible", "example" -> name, "path" -> example.path)
      previewEnabled.set(true)

  div(
    cls := "example-card card card-compact",
    figure(
      div(
        cls := "w-full h-32 overflow-hidden bg-base-200 flex items-center justify-center cursor-pointer",
        onMountCallback: ctx =>
          val ioCtor = js.Dynamic.global.selectDynamic("IntersectionObserver")
          if js.typeOf(ioCtor) == "function" then
            var obs: js.Dynamic = null
            obs = js.Dynamic.newInstance(ioCtor)(
              (entries: js.Array[js.Dynamic], _: js.Dynamic) =>
                val entry0 = entries(0)
                if entry0 != null && entry0.selectDynamic("isIntersecting").asInstanceOf[Boolean] then
                  enablePreview()
                  if obs != null then obs.disconnect()
            )
            obs.observe(ctx.thisNode.ref)
            observerOpt = Some(obs)
          else
            enablePreview()
        ,
        onUnmountCallback: _ =>
          observerOpt.foreach(_.disconnect())
          observerOpt = None
        ,
        // --- Generate SVG preview (lazy) ---
        child <-- previewEnabled.signal.map:
          case false =>
            div(
              cls := "w-full h-full p-1 flex items-center justify-center text-base-content/40 text-xs",
              "Loading preview…",
              title := "Click to create a new diagram with this example (copied to clipboard)",
              onClick.flatMap(_ => FetchStream.get(example.path)) --> (str => routerCmds.createProject.execute(Some(Some(str))))
            )
          case true =>
            div(
              child <--
                FetchStream
                  .get(example.path)
                  .flatMapSwitch: str =>
                    Telemetry.log("home.exampleThumb.start", "example" -> name, "path" -> example.path, "sourceChars" -> str.length)
                    InternalPhases
                      .processDotText(
                        graphviz,
                        DotText(str),
                        telemetryContext = Seq(
                          "example" -> name,
                          "path"    -> example.path
                        )
                      )
                      .map((_, str))
                  .map: (svgElement, str) =>
                    div(
                      cls := "w-full h-full p-1 flex items-center justify-center",
                      div(
                        cls := "w-full h-full relative",
                        div(
                          cls := "absolute inset-0 w-full h-full",
                          svgElement.amend(
                            svg.width               := "100%",
                            svg.height              := "100%",
                            svg.preserveAspectRatio := "xMidYMid meet"
                          )
                        )
                      ),
                      title := "Click to create a new diagram with this example (copied to clipboard)",
                      onClick --> routerCmds.createProject.execute(Some(Some(str)))
                    )
            )
      )
    ),
    div(
      cls := "card-body p-2",
      h2(
        cls := "card-title text-sm justify-center",
        name
      )
    )
  )
}

private def projectCard(graphviz: Graphviz, router: Router)(project: ProjectInfo) =
  val previewEnabled = Var(false)
  var observerOpt: Option[js.Dynamic] = None

  def enablePreview(): Unit =
    if !previewEnabled.now() then
      Telemetry.log("home.thumb.visible", "projectId" -> project.id.value, "name" -> project.name)
      previewEnabled.set(true)

  div(
    cls := "project-card card",
    figure(
      // Preview SVG
      div(
        cls := "w-full h-48 overflow-hidden bg-base-200 flex items-center justify-center cursor-pointer",
        onMountCallback: ctx =>
          val ioCtor = js.Dynamic.global.selectDynamic("IntersectionObserver")
          if js.typeOf(ioCtor) == "function" then
            var obs: js.Dynamic = null
            obs = js.Dynamic.newInstance(ioCtor)(
              (entries: js.Array[js.Dynamic], _: js.Dynamic) =>
                val entry0 = entries(0)
                if entry0 != null && entry0.selectDynamic("isIntersecting").asInstanceOf[Boolean] then
                  enablePreview()
                  if obs != null then obs.disconnect()
            )
            obs.observe(ctx.thisNode.ref)
            observerOpt = Some(obs)
          else
            enablePreview()
        ,
        onUnmountCallback: _ =>
          observerOpt.foreach(_.disconnect())
          observerOpt = None
        ,
        child <-- previewEnabled.signal.flatMapSwitch:
          case false =>
            Signal.fromValue(
              div(
                cls := "w-full h-full p-1 flex items-center justify-center text-base-content/40 text-xs",
                "Loading preview…"
              )
            )
          case true =>
            ProjectStorage.getProjectContent(project.id).distinct
              .flatMapSwitch: str =>
                Telemetry.log(
                  "home.thumb.start",
                  "projectId"   -> project.id.value,
                  "name"        -> project.name,
                  "sourceChars" -> str.length
                )
                InternalPhases.processDotText(
                  graphviz,
                  DotText(str),
                  telemetryContext = Seq(
                    "projectId" -> project.id.value,
                    "name"      -> project.name
                  )
                )
              .map: svgElement =>
                div(
                  cls := "w-full h-full p-1 flex items-center justify-center",
                  div(
                    cls := "w-full h-full relative",
                    div(
                      cls := "absolute inset-0 w-full h-full",
                      svgElement.amend(
                        svg.width               := "100%",
                        svg.height              := "100%",
                        svg.preserveAspectRatio := "xMidYMid meet"
                      )
                    )
                  )
                )
        ,
        onClick.preventDefault --> router.navigateTo(Route.ProjectDetail(project.id.value))
      )
    ),
    div(
      cls := "card-body p-3 pt-2",

      // Header with title and delete button
      div(
        cls := "flex items-center justify-between",
        h2(
          cls := "card-title",
          a(
            href := s"#/${project.id.value}",
            cls  := "flex items-center gap-2 hover:text-primary transition-colors",
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
