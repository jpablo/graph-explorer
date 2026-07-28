package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.RouterCommands
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.{Button, IconButton, IconLink, primary, small}
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ThumbnailRenderer}
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

/** Badge marking a card's diagram kind, so DOT and Mermaid documents are
  * distinguishable at a glance in the library and the examples gallery.
  * Text comes from the format itself; only the accent color is per-format,
  * with a neutral default so a new backend is badged without editing here.
  */
private val badgeColorByFormat = Map[DiagramFormat, String](
  DiagramFormat.DOT     -> "badge-neutral",
  DiagramFormat.Mermaid -> "badge-secondary"
)

private def formatBadge(format: DiagramFormat) =
  val color = badgeColorByFormat.getOrElse(format, "badge-neutral")
  span(cls := s"badge badge-xs $color badge-outline shrink-0", format.toString)

def ProjectsDirectoryView(graphviz: Graphviz, router: Router, routerCmds: RouterCommands) =
  val sortOptionVar = Var[SortOption](SortOption.CreationDate)
  val searchTermVar = Var("")
  // None = all kinds. Enumerated from DiagramFormat.values, so a new backend is
  // filterable without touching this component.
  val kindFilterVar = Var[Option[DiagramFormat]](None)

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
        // The app's name, not a control: it carried `btn btn-ghost` and lit up on hover
        // while having no href and no click handler — an affordance promising nothing.
        span(cls := "text-xl font-semibold", "Graph Explorer")
      ),
      div(
        cls := "flex-none mr-2",
        IconLink("bi-github", "Source on GitHub", "https://github.com/jpablo/graph-explorer/tree/viewer")
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
          // Kind filter dropdown ("" encodes All kinds)
          select(
            cls := "select select-sm h-8",
            option(value := "", "All kinds"),
            DiagramFormat.values.toSeq.map { format =>
              option(
                value := format.toString,
                format.toString
              )
            },
            value <-- kindFilterVar.signal.map(_.fold("")(_.toString)),
            onChange.mapToValue.map(v => Option.when(v.nonEmpty)(DiagramFormat.valueOf(v))) --> kindFilterVar
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

          val languages = DefaultDiagramLanguages(graphviz)

          // Keyed on the directory alone: each entry is a synchronous localStorage read
          // plus a full-document JSON parse, so it must NOT sit inside the search/sort/
          // filter combine below (every debounce tick would rescan the whole library).
          // Thumbnails stay lazy behind the IntersectionObserver regardless.
          val cardInfoSignal: Signal[Map[ProjectId, ProjectCardInfo]] =
            ProjectStorage.directory.map: dir =>
              dir.projects.flatMap(p => ProjectStorage.projectCardInfo(p.id, languages).map(p.id -> _)).toMap

          ProjectStorage.directory
            .combineWithFn(debouncedSearch, sortOptionVar.signal, kindFilterVar.signal, cardInfoSignal):
              (directory, searchTerm, sortOption, kindFilter, infos) =>
                Telemetry.time(
                  "home.projects.computeCards",
                  "projectsTotal" -> directory.projects.size,
                  "searchLen"     -> searchTerm.length,
                  "sort"          -> sortOption.toString,
                  "kind"          -> kindFilter.fold("AllKinds")(_.toString)
                ):
                  // Search and Title-sort see what the card shows (the diagram's title for
                  // never-renamed projects), but the stored name keeps matching too.
                  def shownName(p: ProjectInfo) = infos.get(p.id).map(_.displayName).getOrElse(p.name)
                  val filteredProjects = directory.projects
                    .filter(p => s"${p.name} ${shownName(p)}".toLowerCase.contains(searchTerm.toLowerCase))
                    .filter(p => kindFilter.forall(k => infos.get(p.id).exists(_.format == k)))
                  val sorted =
                    sortOption match
                      case SortOption.LastModified => filteredProjects.sortBy(-_.lastModified)
                      case SortOption.Title        => filteredProjects.sortBy(shownName(_).toLowerCase)
                      case SortOption.CreationDate => filteredProjects.sortBy(-_.createdAt)
                  Telemetry.log("home.projects.cardsReady", "projectsShown" -> sorted.size)
                  sorted.map(p => projectCard(graphviz, router, infos.get(p.id))(p))
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
    cls := "example-card card card-sm", // v5: card-compact became card-sm
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
              cls := "w-full h-full",
              child <--
                FetchStream
                  .get(example.path)
                  .flatMapSwitch: str =>
                    Telemetry.log("home.exampleThumb.start", "example" -> name, "path" -> example.path, "sourceChars" -> str.length)
                    ThumbnailRenderer
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
        cls := "card-title text-sm justify-center gap-2",
        name,
        formatBadge(example.format)
      )
    )
  )
}

private def projectCard(graphviz: Graphviz, router: Router, info: Option[ProjectCardInfo])(project: ProjectInfo) =
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
                ThumbnailRenderer.processDotText(
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
            info.map(_.displayName).getOrElse(project.name),
            onClick.preventDefault --> router.navigateTo(Route.ProjectDetail(project.id.value))
          ),
          info.map(i => formatBadge(i.format))
        ),
        // Neutral until you reach for it: a wall of cards should not read as a wall of red
        // buttons, but the hover has to say plainly that this one destroys something.
        IconButton("bi-trash", "Delete diagram", "tooltip-left", cls := "danger")(
          if dom.window.confirm("Are you sure you want to delete this project?") then
            ProjectStorage.deleteProject(project.id)
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
